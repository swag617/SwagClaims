package com.swag.swagclaims;

import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.PlayerClaimData;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Small static-accessor facade for other Swag617 plugins to read SwagClaims state without
 * touching its internals ({@code ClaimManager}, {@code ClaimDatabaseManager}, etc.) directly.
 * Mirrors the shape of {@code SwagJobsAPI} — a thin set of static methods delegating to the
 * live plugin instance, set once in {@link SwagClaimsPlugin#onEnable()} and cleared in
 * {@link SwagClaimsPlugin#onDisable()}.
 *
 * <p>This is the class intended for later use by Chunk Hopper's {@code GPHook}, SwagFishing,
 * SwagWeather, and LunarItems during their ecosystem-cutover phase — not wired up to them yet,
 * just built and ready to compile against.</p>
 *
 * <p>Every method is defensively null/empty-safe if called before {@link SwagClaimsPlugin} has
 * finished enabling (or after it has been disabled) — they return {@code null}/empty/{@code false}
 * rather than throwing, so a caller doesn't need to guard every call with {@link #isEnabled()}
 * itself (though checking it first is still good practice for anything more than a single read).</p>
 *
 * <h3>Event-bus events (see {@code ClaimManager} for the exact call sites)</h3>
 * <p>SwagClaims also publishes claim lifecycle events through SwagAPI's {@code IEventBusService}
 * (channel names, all prefixed {@code "swagclaims:"}, source plugin {@code "SwagClaims"}) for
 * plugins that want to react to claim changes rather than poll this facade:</p>
 * <ul>
 *   <li><b>{@code swagclaims:claim_created}</b> — fired once a newly created top-level claim or
 *       subdivision has been assigned its real database id. Payload: {@code claimId} (Long),
 *       {@code owner} (String UUID, or {@code null} for an admin claim), {@code claimType}
 *       (String — {@code BASIC}/{@code ADMIN}/{@code SUBDIVISION}), {@code world} (String),
 *       {@code minX}/{@code minZ}/{@code maxX}/{@code maxZ} (Integer), {@code area} (Long),
 *       {@code parentId} (Long, or {@code null} for a top-level claim). Event's
 *       {@code playerUuid} is the claim owner (null for admin claims).</li>
 *   <li><b>{@code swagclaims:claim_deleted}</b> — fired once per claim row actually removed
 *       (abandoning a top-level claim with subdivisions fires this once per subdivision plus once
 *       for the top-level claim itself). Payload: {@code claimId} (Long), {@code owner} (String
 *       UUID or {@code null}), {@code claimType} (String), {@code world} (String), {@code area}
 *       (Long), {@code blocksReturned} (Long — 0 for subdivisions/admin claims, since those never
 *       cost claim blocks). Event's {@code playerUuid} is the claim owner.</li>
 *   <li><b>{@code swagclaims:trust_granted}</b> / <b>{@code swagclaims:trust_revoked}</b> — fired
 *       when a claim's trust map changes. Payload: {@code claimId} (Long), {@code owner} (String
 *       UUID or {@code null} for the claim's owner), {@code target} (String — a player UUID
 *       string, the literal {@code "public"}, or {@code "g:<group>"}), and (granted only)
 *       {@code trustLevel} (String — {@code ACCESS}/{@code CONTAINER}/{@code BUILD}/
 *       {@code MANAGE}). Event's {@code playerUuid} is the trusted player's UUID when
 *       {@code target} parses as one, otherwise {@code null} (public/group targets).</li>
 *   <li><b>{@code swagclaims:claim_transferred}</b> — fired when an admin transfers ownership
 *       of a top-level BASIC claim (via {@code /transferclaim}) to another player. Payload:
 *       {@code claimId} (Long), {@code previousOwner} (String UUID), {@code newOwner} (String
 *       UUID), {@code claimType} (String — always {@code BASIC}), {@code world} (String),
 *       {@code area} (Long), {@code subdivisionsTransferred} (Integer — every direct
 *       subdivision of the transferred claim is re-owned along with it, since a subdivision
 *       stores its own owner copy rather than deriving it from its parent). The claim's trust
 *       list is left untouched by a transfer. Event's {@code playerUuid} is the new owner.</li>
 * </ul>
 */
public final class SwagClaimsAPI {

    private static SwagClaimsPlugin plugin;

    private SwagClaimsAPI() {
    }

    /** Called once from {@link SwagClaimsPlugin#onEnable()} after every manager is constructed. */
    static void init(SwagClaimsPlugin instance) {
        plugin = instance;
    }

    /** Called from {@link SwagClaimsPlugin#onDisable()}. */
    static void shutdown() {
        plugin = null;
    }

    /** True if SwagClaims has finished enabling and this facade is safe to use. */
    public static boolean isEnabled() {
        return plugin != null;
    }

    /**
     * Returns the most specific claim containing this location, or {@code null} if it's
     * wilderness (or if SwagClaims isn't enabled).
     */
    public static Claim getClaimAt(Location location) {
        if (plugin == null || location == null) return null;
        return plugin.getClaimManager().getClaimAt(location);
    }

    /**
     * True if {@code player} may act at {@code location} with at least {@code required} trust.
     * Delegates to the same permission resolution used internally by every protection listener —
     * wilderness and the claim owner always pass; players with the ignore-claims admin bypass
     * (permission or toggle) always pass; otherwise resolves the player's effective trust
     * (individual, public, permission-group, and — for a subdivision — the parent claim).
     */
    public static boolean hasTrust(Player player, Location location, TrustLevel required) {
        if (plugin == null || player == null || location == null || required == null) return false;
        return plugin.getClaimManager().hasPermission(player, location, required);
    }

    /**
     * UUID-only overload for offline/entity-less callers. If the player is online, this defers
     * to the same full resolution {@link #hasTrust(Player, Location, TrustLevel)} uses. If
     * offline, permission-group trust ({@code g:<group>}) can't be resolved — Vault's
     * {@code Permission#playerInGroup} needs a live {@link Player} — so only claim ownership,
     * individual UUID trust, and public trust are checked. This is a documented limitation, not
     * a bug: an offline player who is only trusted via a permission group will resolve to "not
     * trusted" here even though they would pass while online.
     */
    public static boolean hasTrust(UUID uuid, Location location, TrustLevel required) {
        if (plugin == null || uuid == null || location == null || required == null) return false;

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return hasTrust(online, location, required);
        }

        Claim claim = getClaimAt(location);
        if (claim == null) return true; // wilderness
        if (uuid.equals(claim.getOwnerUuid())) return true;
        if (claim.isAdminClaim()) return false; // admin bypass requires a live Player permission check

        TrustLevel best = null;
        TrustLevel individual = claim.getTrust(uuid.toString());
        if (individual != null) best = individual;
        TrustLevel publicTrust = claim.getTrust(Claim.PUBLIC_TARGET);
        if (publicTrust != null && (best == null || publicTrust.implies(best))) best = publicTrust;

        return best != null && best.implies(required);
    }

    /** Every claim (top-level and subdivisions) owned by {@code owner}, or an empty list. */
    public static List<Claim> getClaimsByOwner(UUID owner) {
        if (plugin == null || owner == null) return Collections.emptyList();
        return plugin.getClaimManager().getClaimsByOwner(owner);
    }

    /**
     * The player's claim-block economy state (accrued/bonus blocks, ignore-claims toggle).
     * Returns a freshly-initialized (not yet persisted) default if the player has never been
     * loaded, matching {@code ClaimManager#getPlayerData}'s own behavior — never {@code null}
     * while SwagClaims is enabled.
     */
    public static PlayerClaimData getPlayerClaimData(UUID uuid) {
        if (plugin == null || uuid == null) return null;
        return plugin.getClaimManager().getPlayerData(uuid);
    }
}
