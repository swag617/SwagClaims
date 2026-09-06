package com.swag.swagclaims.model;

/**
 * The inactivity-expiration policy applied to a single Vault/LuckPerms rank (or to the
 * "ungrouped/unconfigured" fallback), as read from {@code expiration.ranks.<GroupName>} (or
 * synthesized from {@code expiration.fallback-days} when a group has no entry). Immutable —
 * built fresh from config on every read by {@code ClaimsConfig#getRankExpirationRule}, matching
 * this codebase's "typed accessors re-read live from config" convention so a config change (via
 * {@code /claim reload} or the web settings page's targeted save) takes effect immediately.
 */
public final class RankExpirationRule {

    private final int defaultDays;
    private final long largeClaimThresholdBlocks;
    private final int largeClaimDays;

    public RankExpirationRule(int defaultDays, long largeClaimThresholdBlocks, int largeClaimDays) {
        this.defaultDays = defaultDays;
        this.largeClaimThresholdBlocks = largeClaimThresholdBlocks;
        this.largeClaimDays = largeClaimDays;
    }

    public int getDefaultDays() {
        return defaultDays;
    }

    /** {@code <= 0} means the large-claim tier is disabled — every claim under this rank uses {@link #getDefaultDays()}. */
    public long getLargeClaimThresholdBlocks() {
        return largeClaimThresholdBlocks;
    }

    public int getLargeClaimDays() {
        return largeClaimDays;
    }

    public boolean hasLargeClaimTier() {
        return largeClaimThresholdBlocks > 0;
    }

    /** Resolves the expiry threshold (in days) that applies to a claim of the given area under this rule. */
    public int resolveDaysFor(long claimArea) {
        if (hasLargeClaimTier() && claimArea >= largeClaimThresholdBlocks) {
            return largeClaimDays;
        }
        return defaultDays;
    }
}
