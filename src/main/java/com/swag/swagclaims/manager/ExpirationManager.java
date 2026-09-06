package com.swag.swagclaims.manager;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.config.ClaimsConfig;
import com.swag.swagclaims.database.ClaimDatabaseManager;
import com.swag.swagclaims.integration.VaultIntegration;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimType;
import com.swag.swagclaims.model.PlayerClaimData;
import com.swag.swagclaims.model.RankExpirationRule;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Runs a repeating sweep that automatically abandons a player's top-level {@code BASIC} claims
 * once they've been inactive longer than their Vault rank's configured threshold — see
 * {@code ClaimsConfig}'s {@code expiration.*} accessors for the policy shape and
 * {@link RankExpirationRule} for how a single rank's rule is resolved.
 *
 * <p><b>Inactivity signal:</b> deliberately {@link PlayerClaimData#getLastLogin()}, NOT
 * {@link Claim#getLastActiveAt()} — the latter is only touched on claim resize (see
 * {@code ClaimManager#resizeClaim}), so using it would incorrectly expire the claims of an owner
 * who logs in regularly but never resizes.
 *
 * <p><b>Threading:</b> the sweep is kicked off via {@code runTaskTimerAsynchronously} since it
 * does one DB read per distinct claim owner ({@link ClaimDatabaseManager#loadPlayerData}) — reading
 * straight from the DB rather than {@code ClaimManager}'s in-memory player-data cache both sidesteps
 * that cache's {@code computeIfAbsent}-seeds-a-default behavior (which would otherwise fabricate
 * fresh, zeroed player data for an offline owner never before touched this session) and avoids
 * adding a new cache-peek method to {@code ClaimManager} for a once-a-day sweep. A few minutes of
 * staleness on an online player's persisted {@code last_login} (the autosave task flushes every 5
 * minutes) is irrelevant at day-granularity. Two further hops happen inside one sweep:
 * <ol>
 *   <li>A main-thread hop to resolve each distinct owner's {@link OfflinePlayer} handle and, through
 *   it, their Vault primary group and bypass-permission check — Vault/LuckPerms group and
 *   permission lookups are called synchronously from the main thread everywhere else in this
 *   codebase (see {@code ClaimManager#isInGroup}), so this sweep follows the same convention rather
 *   than assuming those calls are safe off-thread.</li>
 *   <li>A second main-thread hop to actually expire the claims that were flagged — cache mutation
 *   and the {@code swagclaims:claim_deleted} event {@code ClaimManager#abandonClaim} publishes are
 *   both main-thread-only (a synchronous Bukkit {@code Event} throws {@code IllegalStateException}
 *   if fired off-thread — see {@code GriefPreventionImporter}'s class javadoc for the same
 *   constraint hit during migration).</li>
 * </ol>
 */
public class ExpirationManager {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;
    private final ClaimDatabaseManager db;
    private final ClaimsConfig config;
    private final VaultIntegration vault;

    private BukkitTask task;

    public ExpirationManager(SwagClaimsPlugin plugin, ClaimManager claimManager, ClaimDatabaseManager db,
                              ClaimsConfig config, VaultIntegration vault) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.db = db;
        this.config = config;
        this.vault = vault;
    }

    public void start() {
        if (task != null) return;
        long intervalTicks = 20L * 60 * 60 * config.getExpirationCheckIntervalHours();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::sweep, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ── Sweep ────────────────────────────────────────────────────────────────

    /** One full expiration pass. Safe to call directly (e.g. from an admin command later) — not just from the timer. */
    void sweep() {
        if (!config.isExpirationEnabled()) {
            return;
        }

        List<Claim> candidates = new ArrayList<>();
        for (Claim claim : claimManager.getAllClaims()) {
            if (claim.isTopLevel() && claim.getClaimType() == ClaimType.BASIC && claim.getOwnerUuid() != null) {
                candidates.add(claim);
            }
        }
        if (candidates.isEmpty()) {
            plugin.getLogger().info("[Expiration] Sweep found no top-level BASIC claims to check.");
            return;
        }

        // ── Load each distinct owner's persisted claim-block data (DB read, safe off-thread) ──
        Map<UUID, PlayerClaimData> ownerData = new HashMap<>();
        for (Claim claim : candidates) {
            UUID owner = claim.getOwnerUuid();
            if (ownerData.containsKey(owner)) continue;
            try {
                ownerData.put(owner, db.loadPlayerData(owner).join());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[Expiration] Failed to load player data for " + owner, e);
                ownerData.put(owner, null);
            }
        }

        // ── Resolve each distinct owner's Vault primary group + bypass permission (main thread) ──
        Map<UUID, String> ownerGroup = new HashMap<>();
        Map<UUID, Boolean> ownerBypassPermission = new HashMap<>();
        String bypassPermission = config.getExpirationBypassPermission();
        CompletableFuture<Void> resolved = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                for (UUID owner : ownerData.keySet()) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
                    ownerGroup.put(owner, vault.getPrimaryGroup(op));
                    ownerBypassPermission.put(owner, vault.hasPermission() && vault.hasPermissionOffline(op, bypassPermission));
                }
                resolved.complete(null);
            } catch (Exception e) {
                resolved.completeExceptionally(e);
            }
        });
        try {
            resolved.join();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[Expiration] Failed to resolve owner ranks/permissions — aborting this sweep.", e);
            return;
        }

        // ── Decide which claims are expired ──────────────────────────────────
        long now = System.currentTimeMillis();
        long bypassTotal = config.getExpirationBypassClaimBlocks();
        long bypassBonus = config.getExpirationBypassBonusClaimBlocks();

        int checked = 0, expiredCount = 0, bypassedCount = 0, skippedNoData = 0;
        List<Claim> toExpire = new ArrayList<>();
        List<String> reportLines = new ArrayList<>();

        for (Claim claim : candidates) {
            checked++;
            UUID owner = claim.getOwnerUuid();
            PlayerClaimData data = ownerData.get(owner);
            if (data == null || data.getLastLogin() <= 0) {
                // No persisted claim-block row (or one with no recorded login) at all — we have no
                // reliable inactivity signal, so skip rather than treat "unknown" as "infinitely
                // inactive" and expire on the very first sweep after enabling.
                skippedNoData++;
                continue;
            }

            if (bypassTotal >= 0 && data.getTotalBlocks() >= bypassTotal) {
                bypassedCount++;
                continue;
            }
            if (bypassBonus >= 0 && data.getBonusBlocks() >= bypassBonus) {
                bypassedCount++;
                continue;
            }
            if (Boolean.TRUE.equals(ownerBypassPermission.get(owner))) {
                bypassedCount++;
                continue;
            }

            String group = ownerGroup.get(owner);
            RankExpirationRule rule = config.getRankExpirationRule(group);
            int thresholdDays = rule.resolveDaysFor(claim.getArea());
            long daysInactive = TimeUnit.MILLISECONDS.toDays(now - data.getLastLogin());

            if (daysInactive >= thresholdDays) {
                toExpire.add(claim);
                reportLines.add(String.format(
                        "Claim #%d (world=%s, owner=%s, rank=%s, area=%d) — %d day(s) inactive, threshold was %d day(s)%s",
                        claim.getId(), claim.getWorld(), ownerLabel(owner), group != null ? group : "(fallback)",
                        claim.getArea(), daysInactive, thresholdDays,
                        rule.hasLargeClaimTier() && claim.getArea() >= rule.getLargeClaimThresholdBlocks()
                                ? " [large-claim tier]" : ""));
            }
        }

        if (toExpire.isEmpty()) {
            plugin.getLogger().info(String.format(
                    "[Expiration] Sweep complete: %d checked, 0 expired, %d bypassed, %d skipped (no data).",
                    checked, bypassedCount, skippedNoData));
            return;
        }

        // ── Actually expire — cache mutation + event publish must happen on the main thread ──
        Map<Long, Long> blocksReturnedByClaim = new HashMap<>();
        CompletableFuture<Void> expired = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                for (Claim claim : toExpire) {
                    long returned = claimManager.abandonTopLevelClaim(claim);
                    blocksReturnedByClaim.put(claim.getId(), returned);
                }
                expired.complete(null);
            } catch (Exception e) {
                expired.completeExceptionally(e);
            }
        });
        try {
            expired.join();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[Expiration] Failed to expire flagged claims.", e);
        }

        expiredCount = toExpire.size();
        plugin.getLogger().info(String.format(
                "[Expiration] Sweep complete: %d checked, %d expired, %d bypassed, %d skipped (no data).",
                checked, expiredCount, bypassedCount, skippedNoData));

        writeReport(checked, expiredCount, bypassedCount, skippedNoData, reportLines, blocksReturnedByClaim, toExpire);
    }

    private String ownerLabel(UUID owner) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
        String name = op.getName();
        return name != null ? name : owner.toString();
    }

    // ── Report ───────────────────────────────────────────────────────────────

    private void writeReport(int checked, int expiredCount, int bypassedCount, int skippedNoData,
                              List<String> reportLines, Map<Long, Long> blocksReturnedByClaim, List<Claim> expiredClaims) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        java.io.File reportFile = new java.io.File(plugin.getDataFolder(), "expiration-report-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("SwagClaims Expiration Sweep Report\n");
        sb.append("Generated: ").append(new Date()).append("\n\n");
        sb.append("Claims checked: ").append(checked).append("\n");
        sb.append("Claims expired: ").append(expiredCount).append("\n");
        sb.append("Claims bypassed: ").append(bypassedCount).append("\n");
        sb.append("Claims skipped (no reliable login data): ").append(skippedNoData).append("\n\n");

        if (reportLines.isEmpty()) {
            sb.append("No claims were expired this sweep.\n");
        } else {
            sb.append("Expired claims:\n");
            for (String line : reportLines) {
                sb.append(" - ").append(line).append("\n");
            }
            sb.append("\nBlocks returned per expired claim (top-level claim id -> blocks credited back to owner):\n");
            for (Claim claim : expiredClaims) {
                Long returned = blocksReturnedByClaim.get(claim.getId());
                sb.append("   #").append(claim.getId()).append(" -> ").append(returned != null ? returned : 0).append("\n");
            }
        }

        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            Files.writeString(reportFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write expiration report file", e);
        }
    }
}
