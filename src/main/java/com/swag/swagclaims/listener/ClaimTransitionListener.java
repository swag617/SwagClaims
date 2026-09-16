package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.config.ClaimsConfig;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import com.swag.swagclaims.model.TrustLevel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single shared "did this player just cross a claim boundary" detector, used both by
 * {@link ClaimFlagListener}'s playertime/playerweather/enteractionbar effects and by this class's
 * own claim-enter/exit title display (replicating the GriefPreventionEnterTitles addon).
 *
 * <p>Each player's current "context" is tracked as a string — {@code "claim:<id>"} or
 * {@code "world:<name>"} for wilderness — rather than just a claim id, specifically so that
 * walking from wilderness in one world into wilderness in another (e.g. through a portal) still
 * counts as a change: two different worlds can have different world-level flags even though
 * neither has a claim there. {@link ClaimFlagListener#refreshEnvironmentEffects} is re-run on
 * every transition unconditionally (cheap — a couple of map lookups); the title/enter-actionbar
 * side only fires when the context string actually changed.
 */
public class ClaimTransitionListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;
    private final ClaimFlagListener flagListener;

    private final Map<UUID, String> lastContext = new ConcurrentHashMap<>();

    public ClaimTransitionListener(SwagClaimsPlugin plugin, ClaimManager claimManager, ClaimFlagListener flagListener) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.flagListener = flagListener;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        handleTransition(event.getPlayer(), event.getPlayer().getLocation(), false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;
        // Claims only care about X/Z (see Claim#contains) — a pure Y or sub-block move can't
        // change which claim applies, so skip the lookup entirely for those.
        if (from.getWorld().equals(to.getWorld()) && from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        handleTransition(event.getPlayer(), to, false);
    }

    /**
     * Runs at LOW priority — before {@link #onMove} (default NORMAL) — so a denied crossing is
     * cancelled before the transition (titles, playertime/weather refresh, enter/exit messages)
     * ever fires for it. See {@link #gateTransition} for what's enforced: claim bans (always win,
     * overriding trust — see /claimban), noenter, and noexit.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMoveGate(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;
        if (from.getWorld().equals(to.getWorld()) && from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (!gateTransition(event.getPlayer(), from, to)) {
            event.setCancelled(true);
        }
    }

    /** Same gate as {@link #onMoveGate}, for teleport-based crossings (commands, plugins, ender pearls, chorus fruit, etc.). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleportGate(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;
        if (!gateTransition(event.getPlayer(), event.getFrom(), to)) {
            event.setCancelled(true);
        }
    }

    /**
     * Returns false if this crossing should be denied: entering a claim the player is banned from
     * (via /claimban — this overrides trust entirely, see {@link ClaimManager#isBanned}), entering
     * a claim flagged {@code noenter} without at least ACCESS trust, or leaving a claim flagged
     * {@code noexit} without at least ACCESS trust. No-ops (returns true) if the crossing doesn't
     * actually change which claim applies.
     */
    private boolean gateTransition(Player player, Location from, Location to) {
        Claim toClaim = claimManager.getClaimAt(to);
        Claim fromClaim = claimManager.getClaimAt(from);
        if (sameClaim(fromClaim, toClaim)) return true;

        FlagManager flagManager = plugin.getFlagManager();

        if (toClaim != null && claimManager.isBanned(toClaim, player.getUniqueId()) && !isBanExempt(player, toClaim)) {
            plugin.getMessages().send(player, "ban.entry-denied");
            return false;
        }

        if (toClaim != null && flagManager.isSet(toClaim, ClaimFlags.NO_ENTER)
                && !claimManager.hasPermission(player, to, TrustLevel.ACCESS)) {
            plugin.getMessages().send(player, "flag.no-enter-denied");
            return false;
        }

        if (fromClaim != null && flagManager.isSet(fromClaim, ClaimFlags.NO_EXIT)
                && !claimManager.hasPermission(player, from, TrustLevel.ACCESS)) {
            plugin.getMessages().send(player, "flag.no-exit-denied");
            return false;
        }

        return true;
    }

    private boolean sameClaim(Claim a, Claim b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.getId() == b.getId();
    }

    /** Owners, admin.claims/ignoreclaims bypass holders, and players with the ignoreClaims data flag are never blocked by a claim ban. */
    private boolean isBanExempt(Player player, Claim claim) {
        if (player.hasPermission("swagclaims.admin.ignoreclaims")) return true;
        if (player.hasPermission("swagclaims.admin.claims")) return true;
        if (claim.getOwnerUuid() != null && claim.getOwnerUuid().equals(player.getUniqueId())) return true;
        return claimManager.getPlayerData(player.getUniqueId()).isIgnoreClaims();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        handleTransition(event.getPlayer(), event.getPlayer().getLocation(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        handleTransition(event.getPlayer(), event.getRespawnLocation(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastContext.remove(event.getPlayer().getUniqueId());
    }

    private void handleTransition(Player player, Location newLoc, boolean dimensionSwitch) {
        if (newLoc == null || newLoc.getWorld() == null) return;

        UUID uuid = player.getUniqueId();
        Claim newClaim = claimManager.getClaimAt(newLoc);
        String newContext = newClaim != null ? "claim:" + newClaim.getId() : "world:" + newLoc.getWorld().getName().toLowerCase();
        String oldContext = lastContext.put(uuid, newContext);

        // Always re-evaluate playertime/playerweather for the destination, even when the context
        // string below turns out unchanged — cheap (a couple of map lookups), and this is the only
        // hook that ever re-applies these effects, so it must run on every join/move/world-change.
        flagListener.refreshEnvironmentEffects(player);

        if (newContext.equals(oldContext)) return; // no actual claim/wilderness-world change

        Claim oldClaim = null;
        if (oldContext != null && oldContext.startsWith("claim:")) {
            oldClaim = claimManager.getClaim(Long.parseLong(oldContext.substring("claim:".length())));
        }

        if (newClaim != null) {
            flagListener.showEnterActionBar(player, newClaim);
            flagListener.showEnterMessage(player, newClaim);
        }
        if (oldClaim != null) {
            flagListener.showExitMessage(player, oldClaim);
        }

        showTransitionTitles(player, oldClaim, newClaim, newLoc, dimensionSwitch);
    }

    // ── Titles (GriefPreventionEnterTitles replication) ─────────────────────

    private void showTransitionTitles(Player player, Claim oldClaim, Claim newClaim, Location newLoc, boolean dimensionSwitch) {
        ClaimsConfig config = plugin.getClaimsConfig();
        if (dimensionSwitch && !config.isShowTitlesOnDimensionSwitch()) return;

        if (config.isWatchedWorldsEnabled()) {
            String world = newLoc.getWorld().getName();
            boolean watched = config.getWatchedWorlds().stream().anyMatch(w -> w.equalsIgnoreCase(world));
            if (!watched) return;
        }

        if (oldClaim != null && (!oldClaim.isAdminClaim() || config.isShowTitlesOnAdminClaim())) {
            showRenderedTitle(player, config.getTitleExitTitle(), config.getTitleExitSubtitle(),
                    config.getTitleExitActionbar(), oldClaim);
        }
        if (newClaim != null && (!newClaim.isAdminClaim() || config.isShowTitlesOnAdminClaim())) {
            showRenderedTitle(player, config.getTitleEnterTitle(), config.getTitleEnterSubtitle(),
                    config.getTitleEnterActionbar(), newClaim);
        }
    }

    private void showRenderedTitle(Player player, String titleTemplate, String subtitleTemplate,
                                    String actionbarTemplate, Claim contextClaim) {
        boolean hasTitle = titleTemplate != null && !titleTemplate.isEmpty();
        boolean hasSubtitle = subtitleTemplate != null && !subtitleTemplate.isEmpty();
        boolean hasActionbar = actionbarTemplate != null && !actionbarTemplate.isEmpty();
        if (!hasTitle && !hasSubtitle && !hasActionbar) return;

        String ownerName = resolveOwnerName(contextClaim);
        String claimName = resolveClaimNickname(contextClaim, ownerName);

        if (hasTitle || hasSubtitle) {
            Component titleComp = renderMiniMessage(titleTemplate, ownerName, claimName);
            Component subtitleComp = renderMiniMessage(subtitleTemplate, ownerName, claimName);
            Title title = Title.title(titleComp, subtitleComp,
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500)));
            player.showTitle(title);
        }
        if (hasActionbar) {
            player.sendActionBar(renderMiniMessage(actionbarTemplate, ownerName, claimName));
        }
    }

    private String resolveOwnerName(Claim claim) {
        if (claim == null) return "";
        if (claim.isAdminClaim() || claim.getOwnerUuid() == null) return "Administrator";
        var offline = Bukkit.getOfflinePlayer(claim.getOwnerUuid());
        return offline.getName() != null ? offline.getName() : "Unknown";
    }

    private String resolveClaimNickname(Claim claim, String ownerFallback) {
        if (claim == null) return ownerFallback;
        return (claim.getName() != null && !claim.getName().isBlank()) ? claim.getName() : ownerFallback;
    }

    /** Substitutes %player%/%claim% (this feature's own placeholder style, not this plugin's {token} convention) then parses as MiniMessage, falling back to plain text if parsing throws. */
    private Component renderMiniMessage(String template, String playerToken, String claimToken) {
        if (template == null || template.isEmpty()) return Component.empty();
        String substituted = template.replace("%player%", playerToken).replace("%claim%", claimToken);
        try {
            return MiniMessage.miniMessage().deserialize(substituted);
        } catch (Exception e) {
            return Component.text(substituted);
        }
    }
}
