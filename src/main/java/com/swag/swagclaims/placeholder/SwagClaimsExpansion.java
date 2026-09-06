package com.swag.swagclaims.placeholder;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.PlayerClaimData;
import com.swag.swagclaims.model.TrustLevel;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * PlaceholderAPI expansion exposing claim-block economy and current-location claim state
 * through the {@code %swagclaims_*%} namespace.
 *
 * <p>Supported placeholders (all require an online {@link Player} context — every one of them
 * resolves to an empty string if {@code player} is null, since PAPI allows offline-safe
 * expansions to be queried without one):</p>
 * <ul>
 *   <li>{@code %swagclaims_blocks_accrued%} — claim blocks earned passively over time</li>
 *   <li>{@code %swagclaims_blocks_bonus%} — claim blocks purchased/gifted/admin-granted</li>
 *   <li>{@code %swagclaims_blocks_total%} — accrued + bonus, before subtracting used blocks</li>
 *   <li>{@code %swagclaims_blocks_remaining%} — total minus the area of every top-level claim owned</li>
 *   <li>{@code %swagclaims_claim_name%} — the claim at the player's current location: its custom
 *       name if set, otherwise the owner's name (or "Administrator" for an admin claim), or
 *       "Wilderness" if the player isn't standing in any claim</li>
 *   <li>{@code %swagclaims_claim_owner%} — the owning player's name, "Administrator" for an
 *       admin claim, or "Wilderness" if not in a claim</li>
 *   <li>{@code %swagclaims_trusted%} — "yes"/"no": whether the player may act (at least ACCESS
 *       trust) at their current location. Always "yes" in wilderness, for the claim's own owner,
 *       and for anyone with the ignore-claims admin bypass</li>
 *   <li>{@code %swagclaims_claims_owned%} — total claims the player owns, top-level claims and
 *       subdivisions combined</li>
 * </ul>
 */
public final class SwagClaimsExpansion extends PlaceholderExpansion {

    private final SwagClaimsPlugin plugin;

    public SwagClaimsExpansion(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "swagclaims";
    }

    @Override
    public @NotNull String getAuthor() {
        return "swag617";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** Keeps the expansion registered across a SwagClaims reload, avoiding duplicate registrations. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(@Nullable Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        ClaimManager claimManager = plugin.getClaimManager();
        UUID uuid = player.getUniqueId();

        switch (params.toLowerCase()) {
            case "blocks_accrued" -> {
                return String.valueOf(claimManager.getPlayerData(uuid).getAccruedBlocks());
            }
            case "blocks_bonus" -> {
                return String.valueOf(claimManager.getPlayerData(uuid).getBonusBlocks());
            }
            case "blocks_total" -> {
                return String.valueOf(claimManager.getPlayerData(uuid).getTotalBlocks());
            }
            case "blocks_remaining" -> {
                return String.valueOf(claimManager.getRemainingBlocks(uuid));
            }
            case "blocks_remaining_formatted" -> {
                return String.format("%,d", claimManager.getRemainingBlocks(uuid));
            }
            case "claim_name" -> {
                return resolveClaimName(claimManager.getClaimAt(player.getLocation()));
            }
            case "claim_owner" -> {
                return resolveClaimOwner(claimManager.getClaimAt(player.getLocation()));
            }
            case "trusted" -> {
                return claimManager.hasPermission(player, player.getLocation(), TrustLevel.ACCESS) ? "yes" : "no";
            }
            case "claims_owned" -> {
                return String.valueOf(claimManager.getClaimsByOwner(uuid).size());
            }
            default -> {
                return null; // unknown placeholder — let PAPI handle gracefully
            }
        }
    }

    private String resolveClaimName(@Nullable Claim claim) {
        if (claim == null) {
            return "Wilderness";
        }
        if (claim.getName() != null && !claim.getName().isBlank()) {
            return claim.getName();
        }
        return resolveClaimOwner(claim);
    }

    private String resolveClaimOwner(@Nullable Claim claim) {
        if (claim == null) {
            return "Wilderness";
        }
        if (claim.isAdminClaim() || claim.getOwnerUuid() == null) {
            return "Administrator";
        }
        OfflinePlayer owner = plugin.getServer().getOfflinePlayer(claim.getOwnerUuid());
        String name = owner.getName();
        return name != null ? name : claim.getOwnerUuid().toString();
    }
}
