package com.swag.swagclaims.model;

import org.bukkit.Location;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;

/**
 * A claimed region of a world. Mutable POJO — the in-memory source of truth lives in
 * {@code ClaimManager}'s cache; {@code ClaimDatabaseManager} persists it.
 *
 * <p>Trust is stored in-memory as a map keyed by "target": either a player's UUID string,
 * the literal string {@code public}, or {@code g:<permission-group-name>}. This mirrors the
 * {@code swagclaims_trust} table's {@code target} column exactly so rows round-trip as-is.
 */
public class Claim {

    /** Special trust target representing "everyone", independent of individual player trust. */
    public static final String PUBLIC_TARGET = "public";

    /** Prefix used for permission-group trust targets, e.g. "g:moderator". */
    public static final String GROUP_TARGET_PREFIX = "g:";

    private long id;
    private Long legacyGpId;
    private String world;
    private int minX, minY, minZ;
    private int maxX, maxY, maxZ;
    private UUID ownerUuid; // null = admin claim
    private Long parentId;  // null = top-level claim
    private ClaimType claimType;
    private String name;
    private boolean inheritNothing;
    private long createdAt;
    private long lastActiveAt;

    private final Map<String, TrustLevel> trust = new ConcurrentHashMap<>();
    private final Map<String, FlagValue> flags = new ConcurrentHashMap<>();

    /** Players banned from this specific claim via /claimban — always denied entry, overriding any trust they hold. */
    private final KeySetView<UUID, Boolean> bannedPlayers = ConcurrentHashMap.newKeySet();

    public Claim() {
    }

    public Claim(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                 UUID ownerUuid, ClaimType claimType) {
        this.world = world;
        setBounds(minX, minY, minZ, maxX, maxY, maxZ);
        this.ownerUuid = ownerUuid;
        this.claimType = claimType;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    /** Normalizes corners so min <= max on every axis, then stores them. */
    public void setBounds(int x1, int y1, int z1, int x2, int y2, int z2) {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public int getWidthX() {
        return (maxX - minX) + 1;
    }

    public int getWidthZ() {
        return (maxZ - minZ) + 1;
    }

    /** Horizontal area in blocks (X * Z), ignoring height — matches GriefPrevention convention. */
    public long getArea() {
        return (long) getWidthX() * (long) getWidthZ();
    }

    public boolean isAdminClaim() {
        return ownerUuid == null && claimType == ClaimType.ADMIN;
    }

    public boolean isSubdivision() {
        return parentId != null;
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    /** True if the given world/x/z (ignoring Y) falls within this claim's horizontal bounds. */
    public boolean contains(String worldName, int x, int z) {
        if (!this.world.equalsIgnoreCase(worldName)) return false;
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        return contains(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    /** True if this claim's horizontal bounds overlap another claim's, in the same world. */
    public boolean overlaps(Claim other) {
        if (other == null || !this.world.equalsIgnoreCase(other.world)) return false;
        return this.minX <= other.maxX && this.maxX >= other.minX
                && this.minZ <= other.maxZ && this.maxZ >= other.minZ;
    }

    /** True if the given x/z block is exactly on this claim's horizontal boundary (for resize detection). */
    public boolean isCorner(int x, int z) {
        boolean onXEdge = (x == minX || x == maxX);
        boolean onZEdge = (z == minZ || z == maxZ);
        return onXEdge && onZEdge && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    // ── Trust ────────────────────────────────────────────────────────────────

    public Map<String, TrustLevel> getTrustMap() {
        return trust;
    }

    public TrustLevel getTrust(String target) {
        return trust.get(target);
    }

    public void setTrust(String target, TrustLevel level) {
        trust.put(target, level);
    }

    public void removeTrust(String target) {
        trust.remove(target);
    }

    // ── Flags ────────────────────────────────────────────────────────────────

    public Map<String, FlagValue> getFlagsMap() {
        return flags;
    }

    public FlagValue getFlag(String key) {
        return flags.get(key.toLowerCase());
    }

    public void setFlag(String key, FlagValue value) {
        flags.put(key.toLowerCase(), value);
    }

    public void removeFlag(String key) {
        flags.remove(key.toLowerCase());
    }

    // ── Claim bans (/claimban, /unclaimban) ─────────────────────────────────

    public Set<UUID> getBannedPlayers() {
        return bannedPlayers;
    }

    public boolean isBanned(UUID uuid) {
        return uuid != null && bannedPlayers.contains(uuid);
    }

    public void banPlayer(UUID uuid) {
        bannedPlayers.add(uuid);
    }

    public void unbanPlayer(UUID uuid) {
        bannedPlayers.remove(uuid);
    }

    // ── Getters / setters ───────────────────────────────────────────────────

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Long getLegacyGpId() {
        return legacyGpId;
    }

    public void setLegacyGpId(Long legacyGpId) {
        this.legacyGpId = legacyGpId;
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public ClaimType getClaimType() {
        return claimType;
    }

    public void setClaimType(ClaimType claimType) {
        this.claimType = claimType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isInheritNothing() {
        return inheritNothing;
    }

    public void setInheritNothing(boolean inheritNothing) {
        this.inheritNothing = inheritNothing;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(long lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
