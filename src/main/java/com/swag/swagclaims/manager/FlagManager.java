package com.swag.swagclaims.manager;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.database.ClaimDatabaseManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.FlagValue;
import org.bukkit.Location;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * In-memory authority for world-level flags (per-claim flags live directly on {@link Claim}
 * itself, loaded alongside trust in {@code ClaimDatabaseManager#loadAllClaims}). Resolves the
 * effective flag for any claim or location with claim-level flags always taking precedence over
 * a world-level default, mirroring GPFlags' own claim-then-world lookup order.
 */
public class FlagManager {

    private final SwagClaimsPlugin plugin;
    private final ClaimDatabaseManager db;
    private final ClaimManager claimManager;

    private final Map<String, Map<String, FlagValue>> worldFlags = new ConcurrentHashMap<>();

    public FlagManager(SwagClaimsPlugin plugin, ClaimDatabaseManager db, ClaimManager claimManager) {
        this.plugin = plugin;
        this.db = db;
        this.claimManager = claimManager;
    }

    /** Loads every world-level flag from the database into the cache. Call once at startup and join() it. */
    public CompletableFuture<Void> loadAll() {
        return db.loadAllWorldFlags().thenAccept(loaded -> {
            worldFlags.clear();
            worldFlags.putAll(loaded);
            plugin.getLogger().info("Loaded world-level flags for " + worldFlags.size() + " world(s).");
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load world flags at startup!", ex);
            return null;
        });
    }

    // ── Resolution ──────────────────────────────────────────────────────────

    /**
     * Resolves the effective flag entry for a claim: the claim's own override if it has one,
     * otherwise that claim's world default. Returns null if neither is set.
     */
    public FlagValue resolve(Claim claim, String flagKey) {
        if (claim == null) return null;
        String key = flagKey.toLowerCase();
        FlagValue own = claim.getFlag(key);
        if (own != null) return own;
        return getWorldFlag(claim.getWorld(), key);
    }

    /**
     * Resolves the effective flag entry at a bare location: the claim there (if any, claim-level
     * override then world fallback), or the world-level default if the location is wilderness.
     */
    public FlagValue resolveAt(Location loc, String flagKey) {
        if (loc == null) return null;
        Claim claim = claimManager.getClaimAt(loc);
        if (claim != null) {
            FlagValue resolved = resolve(claim, flagKey);
            if (resolved != null) return resolved;
        }
        if (loc.getWorld() == null) return null;
        return getWorldFlag(loc.getWorld().getName(), flagKey);
    }

    public boolean isSet(Claim claim, String flagKey) {
        FlagValue v = resolve(claim, flagKey);
        return v != null && v.isValue();
    }

    public boolean isSetAt(Location loc, String flagKey) {
        FlagValue v = resolveAt(loc, flagKey);
        return v != null && v.isValue();
    }

    /** Nullable — the flag's params, resolved the same claim-then-world way as {@link #resolve}. */
    public String getParams(Claim claim, String flagKey) {
        FlagValue v = resolve(claim, flagKey);
        return v != null ? v.getParams() : null;
    }

    public String getParamsAt(Location loc, String flagKey) {
        FlagValue v = resolveAt(loc, flagKey);
        return v != null ? v.getParams() : null;
    }

    private FlagValue getWorldFlag(String world, String flagKey) {
        if (world == null) return null;
        Map<String, FlagValue> byKey = worldFlags.get(world.toLowerCase());
        return byKey != null ? byKey.get(flagKey.toLowerCase()) : null;
    }

    // ── Mutation ────────────────────────────────────────────────────────────

    public void setClaimFlag(Claim claim, String flagKey, String params, boolean value) {
        String key = flagKey.toLowerCase();
        claim.setFlag(key, new FlagValue(params, value));
        db.saveClaimFlag(claim.getId(), key, params, value);
    }

    public void removeClaimFlag(Claim claim, String flagKey) {
        String key = flagKey.toLowerCase();
        claim.removeFlag(key);
        db.deleteClaimFlag(claim.getId(), key);
    }

    public void setWorldFlag(String world, String flagKey, String params, boolean value) {
        String w = world.toLowerCase();
        String key = flagKey.toLowerCase();
        worldFlags.computeIfAbsent(w, x -> new ConcurrentHashMap<>()).put(key, new FlagValue(params, value));
        db.saveWorldFlag(w, key, params, value); // lowercased — must match whatever removeWorldFlag deletes by
    }

    public void removeWorldFlag(String world, String flagKey) {
        String w = world.toLowerCase();
        String key = flagKey.toLowerCase();
        Map<String, FlagValue> byKey = worldFlags.get(w);
        if (byKey != null) byKey.remove(key);
        db.deleteWorldFlag(w, key);
    }
}
