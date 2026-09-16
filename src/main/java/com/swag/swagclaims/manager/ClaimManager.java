package com.swag.swagclaims.manager;

import com.SwagDev.SwagAPI.api.IEventBusService;
import com.SwagDev.SwagAPI.events.SwagCrossPluginMessageEvent;
import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.config.ClaimsConfig;
import com.swag.swagclaims.database.ClaimDatabaseManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimMode;
import com.swag.swagclaims.model.ClaimType;
import com.swag.swagclaims.model.PlayerClaimData;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * In-memory authority for all claims and player claim-block data. The database is the
 * persistence layer; this cache is what every listener/command actually reads and mutates.
 *
 * <p><b>Spatial lookup:</b> {@link #getClaimAt(Location)} does a linear scan over every loaded
 * claim, filtered by world name first. This is intentionally simple — fine for the claim counts
 * a single server accumulates. If claim counts ever grow enough for this to show up in profiling,
 * a chunk-bucketed index (Map&lt;ChunkKey, List&lt;Claim&gt;&gt;) is the natural next step, but
 * that's premature for this phase.
 */
public class ClaimManager {

    private final SwagClaimsPlugin plugin;
    private final ClaimDatabaseManager db;
    private final ClaimsConfig config;

    private final Map<Long, Claim> claimsById = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerClaimData> playerData = new ConcurrentHashMap<>();

    // Negative ids are used as placeholders between "claim accepted in memory" and
    // "database assigned it a real id" so protection applies immediately on creation
    // without blocking the main thread on the insert.
    private final AtomicLong tempIdCounter = new AtomicLong(-1);

    public ClaimManager(SwagClaimsPlugin plugin, ClaimDatabaseManager db, ClaimsConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
    }

    /** Loads every claim from the database into the cache. Call once at startup and join() it. */
    public CompletableFuture<Void> loadAll() {
        return db.loadAllClaims().thenAccept(loaded -> {
            claimsById.clear();
            claimsById.putAll(loaded);
            plugin.getLogger().info("Loaded " + claimsById.size() + " claim(s) from the database.");
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load claims at startup!", ex);
            return null;
        });
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    public Claim getClaim(long id) {
        return claimsById.get(id);
    }

    /**
     * Directly registers an already-persisted claim into the in-memory cache, keyed by its real
     * database id, bypassing every validation {@link #createClaim}/{@link #createSubdivision}
     * normally perform (overlap, minimum size, block cost) and skipping the
     * {@code swagclaims:claim_created} event publish those methods do. Used exclusively by
     * {@code GriefPreventionImporter} — a migrated claim's bounds/ownership are historical facts
     * to preserve as-is, not a new player-initiated request to validate, and a bulk import
     * shouldn't flood the event bus one row at a time.
     */
    public void registerMigratedClaim(Claim claim) {
        claimsById.put(claim.getId(), claim);
    }

    public Collection<Claim> getAllClaims() {
        return claimsById.values();
    }

    public List<Claim> getClaimsByOwner(UUID owner) {
        List<Claim> result = new ArrayList<>();
        for (Claim claim : claimsById.values()) {
            if (owner == null ? claim.getOwnerUuid() == null : owner.equals(claim.getOwnerUuid())) {
                result.add(claim);
            }
        }
        return result;
    }

    /**
     * Returns the most specific claim containing this location, or null if it's wilderness.
     * "Most specific" means: if a subdivision and its parent both contain the point, the
     * subdivision wins (smaller area).
     */
    public Claim getClaimAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        Claim best = null;
        for (Claim claim : claimsById.values()) {
            if (!claim.contains(world, x, z)) continue;
            if (best == null || claim.getArea() < best.getArea()) {
                best = claim;
            }
        }
        return best;
    }

    private List<Claim> getTopLevelClaimsInWorld(String world) {
        List<Claim> result = new ArrayList<>();
        for (Claim claim : claimsById.values()) {
            if (claim.isTopLevel() && claim.getWorld().equalsIgnoreCase(world)) {
                result.add(claim);
            }
        }
        return result;
    }

    // ── Player claim-block data ─────────────────────────────────────────────

    /** Synchronous cache read. Returns a fresh default (not yet persisted) if nothing is loaded. */
    public PlayerClaimData getPlayerData(UUID uuid) {
        return playerData.computeIfAbsent(uuid, id -> {
            PlayerClaimData data = new PlayerClaimData(id);
            data.setAccruedBlocks(config.getInitialBlocks());
            return data;
        });
    }

    public void cachePlayerData(PlayerClaimData data) {
        playerData.put(data.getUuid(), data);
    }

    public void uncachePlayerData(UUID uuid) {
        playerData.remove(uuid);
    }

    public Collection<PlayerClaimData> getAllLoadedPlayerData() {
        return playerData.values();
    }

    public long getRemainingBlocks(UUID uuid) {
        return getPlayerData(uuid).getRemainingBlocks(getClaimsByOwner(uuid));
    }

    // ── Claim creation ──────────────────────────────────────────────────────

    /** Outcome of a create/resize attempt, with just enough detail to compose a chat message. */
    public static class ClaimResult {
        public enum Status {
            SUCCESS, TOO_SMALL_WIDTH, TOO_SMALL_AREA, OVERLAP, NOT_ENOUGH_BLOCKS, NO_PERMISSION,
            WORLD_DISABLED, OUT_OF_PARENT_BOUNDS
        }

        public final Status status;
        public final Claim claim;
        public final Claim conflictingClaim;
        public final long blocksNeeded;

        private ClaimResult(Status status, Claim claim, Claim conflictingClaim, long blocksNeeded) {
            this.status = status;
            this.claim = claim;
            this.conflictingClaim = conflictingClaim;
            this.blocksNeeded = blocksNeeded;
        }

        public static ClaimResult success(Claim claim) {
            return new ClaimResult(Status.SUCCESS, claim, null, 0);
        }

        public static ClaimResult tooSmallWidth() {
            return new ClaimResult(Status.TOO_SMALL_WIDTH, null, null, 0);
        }

        public static ClaimResult tooSmallArea() {
            return new ClaimResult(Status.TOO_SMALL_AREA, null, null, 0);
        }

        public static ClaimResult overlap(Claim conflict) {
            return new ClaimResult(Status.OVERLAP, null, conflict, 0);
        }

        public static ClaimResult notEnoughBlocks(long needed) {
            return new ClaimResult(Status.NOT_ENOUGH_BLOCKS, null, null, needed);
        }

        public static ClaimResult worldDisabled() {
            return new ClaimResult(Status.WORLD_DISABLED, null, null, 0);
        }

        public static ClaimResult outOfParentBounds(Claim parent) {
            return new ClaimResult(Status.OUT_OF_PARENT_BOUNDS, null, parent, 0);
        }
    }

    /**
     * Validates and creates a new top-level claim. Runs synchronously against the in-memory
     * cache (fast — no I/O) and, on success, inserts it into the cache immediately under a
     * temporary negative id so protection applies right away; the real database id is patched
     * in asynchronously once the insert completes.
     *
     * @param ownerUuid null for an admin claim
     */
    public ClaimResult createClaim(UUID ownerUuid, ClaimType type, String world,
                                    int x1, int y1, int z1, int x2, int y2, int z2) {
        return createClaim(ownerUuid, type, world, x1, y1, z1, x2, y2, z2, false);
    }

    /**
     * Same as {@link #createClaim(UUID, ClaimType, String, int, int, int, int, int, int)}, with an
     * option to skip the minimum-width/minimum-area checks. Used exclusively for the automatic
     * starter claim granted on first join (see {@code PlayerDataListener}) — it's a courtesy gift
     * sized by {@code automatic-claims-radius}, not a player-initiated claim, so it shouldn't be
     * held to the same minimum-size floor a manually-drawn claim is.
     */
    public ClaimResult createClaim(UUID ownerUuid, ClaimType type, String world,
                                    int x1, int y1, int z1, int x2, int y2, int z2,
                                    boolean bypassMinimumSize) {
        if (config.getClaimMode(world) == ClaimMode.DISABLED) {
            return ClaimResult.worldDisabled();
        }

        Claim candidate = new Claim(world, x1, y1, z1, x2, y2, z2, ownerUuid, type);

        if (!bypassMinimumSize) {
            if (candidate.getWidthX() < config.getMinimumWidth() || candidate.getWidthZ() < config.getMinimumWidth()) {
                return ClaimResult.tooSmallWidth();
            }
            if (candidate.getArea() < config.getMinimumArea()) {
                return ClaimResult.tooSmallArea();
            }
        }

        for (Claim existing : getTopLevelClaimsInWorld(world)) {
            if (candidate.overlaps(existing)) {
                return ClaimResult.overlap(existing);
            }
        }

        if (type == ClaimType.BASIC && ownerUuid != null) {
            long remaining = getRemainingBlocks(ownerUuid);
            if (candidate.getArea() > remaining) {
                return ClaimResult.notEnoughBlocks(candidate.getArea() - remaining);
            }
        }

        long tempId = tempIdCounter.getAndDecrement();
        candidate.setId(tempId);
        claimsById.put(tempId, candidate);

        db.insertClaim(candidate).thenAccept(realId -> {
            claimsById.remove(tempId);
            candidate.setId(realId);
            claimsById.put(realId, candidate);
            // publishClaimCreated fires a Bukkit event, which Paper requires to happen on the
            // main thread — this callback runs on the async DB thread pool.
            Bukkit.getScheduler().runTask(plugin, () -> publishClaimCreated(candidate));
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist newly created claim — rolling back from cache.", ex);
            claimsById.remove(tempId);
            return null;
        });

        return ClaimResult.success(candidate);
    }

    /**
     * Validates and applies a resize of an existing top-level claim (subdivisions aren't
     * resizable through this path this phase). Persists the new bounds asynchronously.
     */
    public ClaimResult resizeClaim(Claim claim, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int widthX = (maxX - minX) + 1;
        int widthZ = (maxZ - minZ) + 1;
        long newArea = (long) widthX * (long) widthZ;

        if (widthX < config.getMinimumWidth() || widthZ < config.getMinimumWidth()) {
            return ClaimResult.tooSmallWidth();
        }
        if (newArea < config.getMinimumArea()) {
            return ClaimResult.tooSmallArea();
        }

        // Build a throwaway candidate with the new bounds to reuse Claim#overlaps.
        Claim candidate = new Claim(claim.getWorld(), x1, y1, z1, x2, y2, z2, claim.getOwnerUuid(), claim.getClaimType());
        for (Claim existing : getTopLevelClaimsInWorld(claim.getWorld())) {
            if (existing.getId() == claim.getId()) continue; // ignore self-overlap
            if (candidate.overlaps(existing)) {
                return ClaimResult.overlap(existing);
            }
        }

        if (claim.getClaimType() == ClaimType.BASIC && claim.getOwnerUuid() != null) {
            long additionalNeeded = newArea - claim.getArea();
            if (additionalNeeded > 0) {
                long remaining = getRemainingBlocks(claim.getOwnerUuid());
                if (additionalNeeded > remaining) {
                    return ClaimResult.notEnoughBlocks(additionalNeeded - remaining);
                }
            }
        }

        claim.setBounds(x1, y1, z1, x2, y2, z2);
        claim.setLastActiveAt(System.currentTimeMillis());
        db.updateClaimBounds(claim);
        return ClaimResult.success(claim);
    }

    /**
     * Validates and creates a subdivision nested inside {@code parent}. Unlike a top-level claim,
     * a subdivision costs no claim blocks (its parent already paid for that area) and is checked
     * for overlap only against its own siblings (other subdivisions of the same parent), plus a
     * containment check that it falls entirely within the parent's bounds. Ownership always
     * matches the parent's owner.
     */
    public ClaimResult createSubdivision(Claim parent, int x1, int y1, int z1, int x2, int y2, int z2) {
        Claim candidate = new Claim(parent.getWorld(), x1, y1, z1, x2, y2, z2, parent.getOwnerUuid(), ClaimType.SUBDIVISION);
        candidate.setParentId(parent.getId());

        if (candidate.getWidthX() < config.getMinimumWidth() || candidate.getWidthZ() < config.getMinimumWidth()) {
            return ClaimResult.tooSmallWidth();
        }
        if (candidate.getArea() < config.getMinimumArea()) {
            return ClaimResult.tooSmallArea();
        }
        if (candidate.getMinX() < parent.getMinX() || candidate.getMaxX() > parent.getMaxX()
                || candidate.getMinZ() < parent.getMinZ() || candidate.getMaxZ() > parent.getMaxZ()) {
            return ClaimResult.outOfParentBounds(parent);
        }

        for (Claim existing : claimsById.values()) {
            if (existing.getClaimType() != ClaimType.SUBDIVISION) continue;
            if (existing.getParentId() == null || existing.getParentId() != parent.getId()) continue;
            if (candidate.overlaps(existing)) {
                return ClaimResult.overlap(existing);
            }
        }

        long tempId = tempIdCounter.getAndDecrement();
        candidate.setId(tempId);
        claimsById.put(tempId, candidate);

        db.insertClaim(candidate).thenAccept(realId -> {
            claimsById.remove(tempId);
            candidate.setId(realId);
            claimsById.put(realId, candidate);
            publishClaimCreated(candidate);
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist newly created subdivision — rolling back from cache.", ex);
            claimsById.remove(tempId);
            return null;
        });

        return ClaimResult.success(candidate);
    }

    // ── Claim blocks accrual ────────────────────────────────────────────────

    /**
     * Credits accrued claim blocks to every currently-online player, prorated per minute from the
     * configured per-hour rate (per-world, falling back to "Default"), capped at that world's
     * configured max. Intended to be called once a minute by a repeating task; matches house style
     * ("online-only", simpler than tracking offline players — see class javadoc in the plugin's
     * accrual task). Does not force an immediate DB write — the existing 5-minute autosave task
     * and the on-quit save both cover persistence, so this only touches the in-memory cache.
     */
    public void accrueBlocksForOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            PlayerClaimData data = getPlayerData(uuid);
            String world = player.getWorld().getName();
            double perHour = config.getAccruedPerHour(world);
            long maxAccrued = config.getMaxAccrued(world);
            if (data.getAccruedBlocks() >= maxAccrued) continue;

            long grant = Math.round(perHour / 60.0);
            if (grant <= 0) continue;

            data.setAccruedBlocks(Math.min(maxAccrued, data.getAccruedBlocks() + grant));
        }
    }

    // ── Claim block gifting (ClaimBlockSendGUI / "GPSend") ──────────────────────

    /**
     * Transfers {@code amount} claim blocks from {@code from} to {@code to}. Debited from
     * {@code from}'s spare (unused) capacity only — bonus blocks first, then accrued blocks for
     * any remainder, mirroring {@link #abandonClaim}'s existing penalty-deduction ordering —
     * and credited to {@code to}'s bonus blocks, the same pool {@code /buyclaimblocks} credits
     * into. Returns false (no mutation at all) if {@code from} doesn't have {@code amount} spare
     * blocks available; callers should check this before showing a success message.
     */
    public boolean sendClaimBlocks(UUID from, UUID to, long amount) {
        if (amount <= 0 || from == null || to == null || from.equals(to)) return false;

        long remaining = getRemainingBlocks(from);
        if (amount > remaining) return false;

        PlayerClaimData fromData = getPlayerData(from);
        long fromBonus = Math.min(amount, fromData.getBonusBlocks());
        fromData.setBonusBlocks(fromData.getBonusBlocks() - fromBonus);
        long fromAccrued = amount - fromBonus;
        if (fromAccrued > 0) {
            fromData.setAccruedBlocks(fromData.getAccruedBlocks() - fromAccrued);
        }
        db.savePlayerData(fromData);

        PlayerClaimData toData = getPlayerData(to);
        toData.setBonusBlocks(toData.getBonusBlocks() + amount);
        db.savePlayerData(toData);
        return true;
    }

    /**
     * Admin grant of {@code amount} bonus claim blocks to {@code target}, offline-safe. Unlike
     * {@link #sendClaimBlocks}, this credits without debiting anyone.
     *
     * <p>Deliberately does NOT use {@link #getPlayerData} directly — that method's
     * {@code computeIfAbsent} would silently default-construct a fresh {@link PlayerClaimData}
     * (wiping any real persisted row) for a target who is offline and hasn't been cached this
     * session. Instead: use the in-memory cache if already warm (the target is online or was
     * recently online this session), otherwise load their real row from the database first —
     * {@code null} only means they truly have no row yet (never played, or never had claim data
     * saved), in which case a fresh default is actually correct.</p>
     */
    public CompletableFuture<Void> giveClaimBlocks(UUID target, long amount) {
        if (target == null || amount == 0) return CompletableFuture.completedFuture(null);

        PlayerClaimData cached = playerData.get(target);
        if (cached != null) {
            cached.setBonusBlocks(cached.getBonusBlocks() + amount);
            db.savePlayerData(cached);
            return CompletableFuture.completedFuture(null);
        }

        return db.loadPlayerData(target).thenAccept(loaded -> {
            PlayerClaimData data = loaded;
            if (data == null) {
                data = new PlayerClaimData(target);
                data.setAccruedBlocks(config.getInitialBlocks());
            }
            data.setBonusBlocks(data.getBonusBlocks() + amount);
            db.savePlayerData(data);
            cachePlayerData(data);
        });
    }

    /**
     * Removes bonus claim blocks previously granted via {@link #giveClaimBlocks} — only ever
     * reduces {@code bonusBlocks}, never a player's naturally accrued (playtime) blocks, and
     * clamps at 0 rather than going negative. Returns how many were actually removed, which may
     * be less than requested if the player didn't have that many bonus blocks.
     */
    public CompletableFuture<Long> takeClaimBlocks(UUID target, long amount) {
        if (target == null || amount <= 0) return CompletableFuture.completedFuture(0L);

        PlayerClaimData cached = playerData.get(target);
        if (cached != null) {
            long actual = Math.min(amount, Math.max(0, cached.getBonusBlocks()));
            cached.setBonusBlocks(cached.getBonusBlocks() - actual);
            db.savePlayerData(cached);
            return CompletableFuture.completedFuture(actual);
        }

        return db.loadPlayerData(target).thenApply(loaded -> {
            PlayerClaimData data = loaded;
            if (data == null) {
                data = new PlayerClaimData(target);
                data.setAccruedBlocks(config.getInitialBlocks());
            }
            long actual = Math.min(amount, Math.max(0, data.getBonusBlocks()));
            data.setBonusBlocks(data.getBonusBlocks() - actual);
            db.savePlayerData(data);
            cachePlayerData(data);
            return actual;
        });
    }

    // ── Trust ────────────────────────────────────────────────────────────────

    public void grantTrust(Claim claim, String target, TrustLevel level) {
        claim.setTrust(target, level);
        db.saveTrust(claim.getId(), target, level);

        Map<String, Object> payload = new HashMap<>();
        payload.put("claimId", claim.getId());
        payload.put("owner", claim.getOwnerUuid() != null ? claim.getOwnerUuid().toString() : null);
        payload.put("target", target);
        payload.put("trustLevel", level.name());
        publishEvent("swagclaims:trust_granted", payload, parseUuidOrNull(target));
    }

    public void revokeTrust(Claim claim, String target) {
        claim.removeTrust(target);
        db.deleteTrust(claim.getId(), target);

        Map<String, Object> payload = new HashMap<>();
        payload.put("claimId", claim.getId());
        payload.put("owner", claim.getOwnerUuid() != null ? claim.getOwnerUuid().toString() : null);
        payload.put("target", target);
        publishEvent("swagclaims:trust_revoked", payload, parseUuidOrNull(target));
    }

    /**
     * Resolves the highest trust level a player effectively holds on a claim: their own UUID
     * entry, the "public" entry, or a "g:&lt;group&gt;" entry for any group they're in — checked
     * on the claim itself, then (if it's a subdivision with inheritNothing=false) walking up to
     * the parent claim as a fallback. Returns null if no trust applies at all.
     */
    public TrustLevel resolveEffectiveTrust(Claim claim, Player player) {
        TrustLevel best = resolveOwnTrust(claim, player);

        if (claim.isSubdivision() && !claim.isInheritNothing()) {
            Claim parent = getClaim(claim.getParentId());
            if (parent != null) {
                TrustLevel parentTrust = resolveEffectiveTrust(parent, player);
                if (parentTrust != null && (best == null || parentTrust.implies(best))) {
                    best = parentTrust;
                }
            }
        }
        return best;
    }

    private TrustLevel resolveOwnTrust(Claim claim, Player player) {
        TrustLevel best = null;

        TrustLevel individual = claim.getTrust(player.getUniqueId().toString());
        if (individual != null) best = individual;

        TrustLevel publicTrust = claim.getTrust(Claim.PUBLIC_TARGET);
        if (publicTrust != null && (best == null || publicTrust.implies(best))) {
            best = publicTrust;
        }

        for (Map.Entry<String, TrustLevel> entry : claim.getTrustMap().entrySet()) {
            if (!entry.getKey().startsWith(Claim.GROUP_TARGET_PREFIX)) continue;
            String group = entry.getKey().substring(Claim.GROUP_TARGET_PREFIX.length());
            if (isInGroup(player, group) && (best == null || entry.getValue().implies(best))) {
                best = entry.getValue();
            }
        }
        return best;
    }

    /**
     * Group-membership check, delegated to Vault's {@code Permission} service when it's hooked
     * (real LuckPerms/permission-plugin group membership); falls back to the
     * "group.&lt;name&gt;" permission-node convention (which LuckPerms and most permission
     * plugins auto-register for every member of a group) if Vault or a Permission provider isn't
     * present.
     */
    private boolean isInGroup(Player player, String groupName) {
        return plugin.getVaultIntegration().isInGroup(player, groupName);
    }

    /**
     * True if the player may act at this location with at least {@code required} trust.
     * Wilderness (no claim) and claim owners always pass. Players with the ignore-claims
     * admin permission, or who have toggled ignoreClaims on their own player data, bypass
     * every check.
     */
    public boolean hasPermission(Player player, Location location, TrustLevel required) {
        if (player.hasPermission("swagclaims.admin.ignoreclaims")) return true;

        PlayerClaimData data = playerData.get(player.getUniqueId());
        if (data != null && data.isIgnoreClaims()) return true;

        Claim claim = getClaimAt(location);
        if (claim == null) return true; // wilderness

        if (claim.isAdminClaim()) {
            if (player.hasPermission("swagclaims.admin.claims")) return true;
        } else if (claim.getOwnerUuid() != null && claim.getOwnerUuid().equals(player.getUniqueId())) {
            return true;
        }

        TrustLevel effective = resolveEffectiveTrust(claim, player);
        return effective != null && effective.implies(required);
    }

    // ── Abandon ─────────────────────────────────────────────────────────────

    /**
     * Deletes a single claim (subdivision or childless top-level). If it's a top-level BASIC
     * claim, credits blocks back to the owner per {@code abandon-return-ratio}. Returns the
     * number of blocks actually credited back (0 for subdivisions/admin claims, since they never
     * cost blocks in the first place).
     *
     * <p><b>Return-ratio bookkeeping (judgment call, kept deliberately simple):</b> freeing a
     * top-level claim already frees its whole area from {@link PlayerClaimData#getUsedBlocks},
     * which alone would always be a 100% refund regardless of the configured ratio. To honor a
     * ratio below 1.0, the *unreturned* portion of the area is permanently deducted from the
     * owner's block pool as a one-time penalty — taken out of bonus blocks first (the "softer"
     * pool), then accrued blocks for any remainder. At the default ratio of 1.0 the penalty is
     * always zero, so nothing is deducted and the refund is exactly the freed area.
     */
    public long abandonClaim(Claim claim) {
        long returned = 0;

        if (claim.isTopLevel() && claim.getClaimType() == ClaimType.BASIC && claim.getOwnerUuid() != null) {
            long area = claim.getArea();
            double ratio = config.getAbandonReturnRatio();
            returned = Math.round(area * ratio);
            long penalty = area - returned;

            if (penalty > 0) {
                PlayerClaimData data = getPlayerData(claim.getOwnerUuid());
                long fromBonus = Math.min(penalty, data.getBonusBlocks());
                data.setBonusBlocks(data.getBonusBlocks() - fromBonus);
                penalty -= fromBonus;
                if (penalty > 0) {
                    data.setAccruedBlocks(data.getAccruedBlocks() - penalty);
                }
                db.savePlayerData(data);
            }
        }

        claimsById.remove(claim.getId());
        db.deleteClaim(claim.getId());
        publishClaimDeleted(claim, returned);
        return returned;
    }

    /** Deletes a top-level claim and every subdivision nested inside it. Returns total blocks credited. */
    public long abandonTopLevelClaim(Claim topLevel) {
        long total = 0;
        for (Claim claim : new ArrayList<>(claimsById.values())) {
            if (claim.getParentId() != null && claim.getParentId() == topLevel.getId()) {
                total += abandonClaim(claim);
            }
        }
        total += abandonClaim(topLevel);
        return total;
    }

    /** Deletes every claim owned by a player (top-level claims take their subdivisions with them). Returns total blocks credited. */
    public long abandonAllClaims(UUID owner) {
        long total = 0;
        for (Claim claim : getClaimsByOwner(owner)) {
            if (claim.isTopLevel()) {
                total += abandonTopLevelClaim(claim);
            }
        }
        return total;
    }

    // ── Ownership transfer / renaming ───────────────────────────────────────

    /**
     * Transfers ownership of a top-level BASIC claim to {@code newOwner}, bringing along every
     * direct subdivision nested inside it. Subdivisions store their own {@code owner_uuid} copy
     * (set from the parent's at creation time — see {@link #createSubdivision}) rather than
     * deriving it dynamically from the parent, and every trust/ownership check
     * ({@link #hasPermission}, {@link ClaimCommandUtil#canManage}-style checks) reads a claim's
     * own {@code ownerUuid} field directly. Leaving a subdivision pointed at the old owner after
     * a transfer would silently keep that owner's full owner-level access to it, so every direct
     * subdivision's owner is updated right alongside the top-level claim.
     *
     * <p>Deliberately does not touch the claim's trust list — the new owner may want to prune it
     * themselves, and pruning it automatically would destroy information the new owner might
     * still want (e.g. builders the previous owner had trusted). Callers (see
     * {@code TransferClaimCommand}) are responsible for validating the claim is a top-level BASIC
     * claim with a real owner before calling this.
     *
     * @return the number of subdivisions transferred along with the top-level claim
     */
    public int transferClaim(Claim claim, UUID newOwner) {
        UUID previousOwner = claim.getOwnerUuid();

        claim.setOwnerUuid(newOwner);
        db.updateClaimOwner(claim.getId(), newOwner);

        int subdivisionsTransferred = 0;
        for (Claim sub : claimsById.values()) {
            if (sub.getParentId() != null && sub.getParentId() == claim.getId()) {
                sub.setOwnerUuid(newOwner);
                db.updateClaimOwner(sub.getId(), newOwner);
                subdivisionsTransferred++;
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("claimId", claim.getId());
        payload.put("previousOwner", previousOwner != null ? previousOwner.toString() : null);
        payload.put("newOwner", newOwner != null ? newOwner.toString() : null);
        payload.put("claimType", claim.getClaimType().name());
        payload.put("world", claim.getWorld());
        payload.put("area", claim.getArea());
        payload.put("subdivisionsTransferred", subdivisionsTransferred);
        publishEvent("swagclaims:claim_transferred", payload, newOwner);

        return subdivisionsTransferred;
    }

    /**
     * Sets (or, with {@code name == null}, clears) a claim's display nickname. Purely a display
     * concern — unlike {@link #transferClaim}, this doesn't touch ownership, trust, or claim
     * blocks at all, so no event is published.
     */
    public void renameClaim(Claim claim, String name) {
        claim.setName(name);
        db.updateClaimName(claim.getId(), name);
    }

    public ClaimsConfig getConfig() {
        return config;
    }

    // ── Claim bans (/claimban, /unclaimban) ─────────────────────────────────

    /** Bans a player from a specific claim — they are denied entry regardless of any trust they hold (see ClaimTransitionListener). */
    public void banPlayer(Claim claim, UUID target) {
        claim.banPlayer(target);
        db.saveBan(claim.getId(), target);
    }

    public void unbanPlayer(Claim claim, UUID target) {
        claim.unbanPlayer(target);
        db.deleteBan(claim.getId(), target);
    }

    public boolean isBanned(Claim claim, UUID uuid) {
        return claim != null && claim.isBanned(uuid);
    }

    // ── SwagAPI event bus publishing ────────────────────────────────────────
    // See SwagClaimsAPI's class javadoc for the full documented payload shape of every channel
    // published below — that's the doc a consuming plugin should read, not this class.

    private void publishClaimCreated(Claim claim) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("claimId", claim.getId());
        payload.put("owner", claim.getOwnerUuid() != null ? claim.getOwnerUuid().toString() : null);
        payload.put("claimType", claim.getClaimType().name());
        payload.put("world", claim.getWorld());
        payload.put("minX", claim.getMinX());
        payload.put("minZ", claim.getMinZ());
        payload.put("maxX", claim.getMaxX());
        payload.put("maxZ", claim.getMaxZ());
        payload.put("area", claim.getArea());
        payload.put("parentId", claim.getParentId());
        publishEvent("swagclaims:claim_created", payload, claim.getOwnerUuid());
    }

    private void publishClaimDeleted(Claim claim, long blocksReturned) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("claimId", claim.getId());
        payload.put("owner", claim.getOwnerUuid() != null ? claim.getOwnerUuid().toString() : null);
        payload.put("claimType", claim.getClaimType().name());
        payload.put("world", claim.getWorld());
        payload.put("area", claim.getArea());
        payload.put("blocksReturned", blocksReturned);
        publishEvent("swagclaims:claim_deleted", payload, claim.getOwnerUuid());
    }

    /** Best-effort UUID parse for a trust "target" string — null for "public" or "g:&lt;group&gt;" targets. */
    private UUID parseUuidOrNull(String target) {
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** No-op if SwagAPI's IEventBusService isn't hooked (soft dependency — see SwagClaimsPlugin#hookSwagAPI). */
    private void publishEvent(String channel, Map<String, Object> payload, UUID playerUuid) {
        IEventBusService bus = plugin.getEventBusService();
        if (bus == null) return;
        bus.publish(new SwagCrossPluginMessageEvent(channel, "SwagClaims", payload, playerUuid));
    }
}
