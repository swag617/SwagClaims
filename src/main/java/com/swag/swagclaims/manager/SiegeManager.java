package com.swag.swagclaims.manager;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified siege system backed by the (previously empty) {@code swagclaims_siege} table. Kept
 * deliberately focused rather than an exhaustive GriefPrevention-parity implementation:
 *
 * <ul>
 *   <li>Only one active siege per top-level claim at a time.</li>
 *   <li>A siege has a fixed duration ({@code siege.door-open-delay-seconds}) rather than ending on
 *       a "the attacker broke in" condition — simpler to reason about and correct for the
 *       configured shape, at the cost of not detecting an early win.</li>
 *   <li>The attacker/defender cooldown is tracked in-memory only; the database row is written for
 *       historical record-keeping but isn't read back on restart, so cooldowns reset on reboot.
 *       Good enough for a first vertical slice — a persisted-cooldown lookup is the natural next
 *       step if that matters in practice.</li>
 * </ul>
 */
public class SiegeManager {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    /** Keyed by the DEFENDING top-level claim's id. */
    private final Map<Long, ActiveSiege> activeSieges = new ConcurrentHashMap<>();

    /** "attackerUuid|defenderUuid" -> timestamp (millis) the cooldown ends. */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public SiegeManager(SwagClaimsPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    /** Mutable record of one in-progress siege. Fields are public — this is a plain data holder shared with SiegeListener. */
    public static final class ActiveSiege {
        public final UUID attacker;
        public final UUID defender;
        public final long claimId;
        public final long startTime;
        volatile long dbId = -1;

        ActiveSiege(UUID attacker, UUID defender, long claimId, long startTime) {
            this.attacker = attacker;
            this.defender = defender;
            this.claimId = claimId;
            this.startTime = startTime;
        }
    }

    public enum SiegeStartResult {
        SUCCESS, WORLD_DISABLED, ATTACKER_IN_CLAIM, DEFENDER_NOT_IN_CLAIM,
        CLAIM_NOT_SIEGABLE, ALREADY_UNDER_SIEGE, ON_COOLDOWN
    }

    public boolean isSiegeEnabledWorld(String world) {
        for (String enabled : plugin.getClaimsConfig().getSiegeEnabledWorlds()) {
            if (enabled.equalsIgnoreCase(world)) return true;
        }
        return false;
    }

    public ActiveSiege getActiveSiege(long topLevelClaimId) {
        return activeSieges.get(topLevelClaimId);
    }

    /** Resolves a (possibly subdivision) claim up to the top-level id used to key active sieges. */
    private long topLevelIdOf(Claim claim) {
        if (claim.isTopLevel()) return claim.getId();
        Long parentId = claim.getParentId();
        return parentId != null ? parentId : claim.getId();
    }

    public boolean isUnderSiege(Claim claim) {
        return activeSieges.containsKey(topLevelIdOf(claim));
    }

    /**
     * Attempts to start a siege by {@code attacker} against whichever claim {@code defender} is
     * currently standing in. GriefPrevention rule set, simplified: the attacker must not be inside
     * any claim themselves, the defender must be standing in a non-admin claim they don't share
     * with the attacker, that claim mustn't already be under siege, and the attacker/defender pair
     * mustn't be on cooldown from a previous siege.
     */
    public SiegeStartResult startSiege(Player attacker, Player defender) {
        String world = attacker.getWorld().getName();
        if (!isSiegeEnabledWorld(world)) return SiegeStartResult.WORLD_DISABLED;

        if (claimManager.getClaimAt(attacker.getLocation()) != null) {
            return SiegeStartResult.ATTACKER_IN_CLAIM;
        }

        Claim defenderClaim = claimManager.getClaimAt(defender.getLocation());
        if (defenderClaim == null) return SiegeStartResult.DEFENDER_NOT_IN_CLAIM;

        Claim topLevel = defenderClaim.isTopLevel() ? defenderClaim : claimManager.getClaim(defenderClaim.getParentId());
        if (topLevel == null || topLevel.isAdminClaim() || topLevel.getOwnerUuid() == null) {
            return SiegeStartResult.CLAIM_NOT_SIEGABLE;
        }
        if (topLevel.getOwnerUuid().equals(attacker.getUniqueId())) {
            return SiegeStartResult.CLAIM_NOT_SIEGABLE;
        }
        if (activeSieges.containsKey(topLevel.getId())) {
            return SiegeStartResult.ALREADY_UNDER_SIEGE;
        }

        String pairKey = pairKey(attacker.getUniqueId(), topLevel.getOwnerUuid());
        Long cooldownEnd = cooldowns.get(pairKey);
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            return SiegeStartResult.ON_COOLDOWN;
        }

        ActiveSiege siege = new ActiveSiege(attacker.getUniqueId(), topLevel.getOwnerUuid(), topLevel.getId(), System.currentTimeMillis());
        activeSieges.put(topLevel.getId(), siege);

        plugin.getDatabaseManager().insertSiege(siege.attacker, siege.defender, siege.claimId, siege.startTime)
                .thenAccept(id -> siege.dbId = id);

        long delayTicks = plugin.getClaimsConfig().getSiegeDoorOpenDelaySeconds() * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> endSiege(topLevel.getId()), delayTicks);

        return SiegeStartResult.SUCCESS;
    }

    /** Ends an active siege (if any) on the given top-level claim id and starts its cooldown. */
    public void endSiege(long topLevelClaimId) {
        ActiveSiege siege = activeSieges.remove(topLevelClaimId);
        if (siege == null) return;

        long cooldownMillis = plugin.getClaimsConfig().getSiegeCooldownMinutes() * 60_000L;
        cooldowns.put(pairKey(siege.attacker, siege.defender), System.currentTimeMillis() + cooldownMillis);

        plugin.getDatabaseManager().updateSiegeEnd(siege.dbId, System.currentTimeMillis());
    }

    private String pairKey(UUID attacker, UUID defender) {
        return attacker + "|" + defender;
    }

    /**
     * True if {@code type} is one of the "soft" blocks a besieger may break through during an
     * active siege (wool, planks, dirt, sand, gravel, glass/glass panes, grass, fern, dead bush —
     * matching GriefPrevention's default BreakableBlocks list). Hardcoded rather than
     * config-driven for this phase — see class javadoc.
     */
    public boolean isSiegeBreakableMaterial(Material type) {
        if (Tag.WOOL.isTagged(type) || Tag.PLANKS.isTagged(type)
                || Tag.DIRT.isTagged(type) || Tag.SAND.isTagged(type)) {
            return true;
        }
        String name = type.name();
        return name.equals("GRAVEL")
                || name.equals("GLASS") || name.endsWith("_STAINED_GLASS")
                || name.equals("GLASS_PANE") || name.endsWith("_STAINED_GLASS_PANE")
                || name.equals("SHORT_GRASS") || name.equals("GRASS") || name.equals("TALL_GRASS")
                || name.equals("FERN") || name.equals("LARGE_FERN")
                || name.equals("DEAD_BUSH");
    }
}
