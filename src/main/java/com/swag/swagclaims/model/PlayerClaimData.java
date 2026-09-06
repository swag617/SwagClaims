package com.swag.swagclaims.model;

import java.util.Collection;
import java.util.UUID;

/**
 * A player's claim-block economy state. Mutable POJO — cached by {@code ClaimManager} and
 * persisted by {@code ClaimDatabaseManager}.
 *
 * <p>"Used" blocks aren't stored here — they're derived on demand from the player's current
 * top-level claims (subdivisions don't cost blocks of their own; they carve space out of
 * their parent), which is why {@link #getUsedBlocks(Collection)} and
 * {@link #getRemainingBlocks(Collection)} both take the player's claim list as a parameter
 * rather than caching a count that could drift out of sync with the claim cache.
 */
public class PlayerClaimData {

    private final UUID uuid;
    private long accruedBlocks;
    private long bonusBlocks;
    private long lastLogin;
    private boolean ignoreClaims;

    public PlayerClaimData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }

    public long getAccruedBlocks() {
        return accruedBlocks;
    }

    public void setAccruedBlocks(long accruedBlocks) {
        this.accruedBlocks = Math.max(0, accruedBlocks);
    }

    public long getBonusBlocks() {
        return bonusBlocks;
    }

    public void setBonusBlocks(long bonusBlocks) {
        this.bonusBlocks = Math.max(0, bonusBlocks);
    }

    public long getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(long lastLogin) {
        this.lastLogin = lastLogin;
    }

    public boolean isIgnoreClaims() {
        return ignoreClaims;
    }

    public void setIgnoreClaims(boolean ignoreClaims) {
        this.ignoreClaims = ignoreClaims;
    }

    /** Total claim blocks this player has to spend, before subtracting what's already used. */
    public long getTotalBlocks() {
        return accruedBlocks + bonusBlocks;
    }

    /** Sum of the horizontal area of every top-level (non-subdivision) claim this player owns. */
    public long getUsedBlocks(Collection<Claim> ownedClaims) {
        long used = 0;
        for (Claim claim : ownedClaims) {
            if (claim.isTopLevel() && !claim.isAdminClaim()) {
                used += claim.getArea();
            }
        }
        return used;
    }

    public long getRemainingBlocks(Collection<Claim> ownedClaims) {
        return getTotalBlocks() - getUsedBlocks(ownedClaims);
    }
}
