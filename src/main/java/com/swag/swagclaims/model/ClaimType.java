package com.swag.swagclaims.model;

/** The kind of claim a {@link Claim} represents. */
public enum ClaimType {

    /** A normal player-owned claim, paid for out of the owner's claim block pool. */
    BASIC,

    /** An admin-owned claim (ownerUuid is null) — not counted against any player's block pool. */
    ADMIN,

    /** A claim nested inside another claim (parentId is set). Used to subdivide a parent claim. */
    SUBDIVISION;

    /** Case-insensitive lookup; returns null if the name doesn't match a known claim type. */
    public static ClaimType fromString(String name) {
        if (name == null) return null;
        try {
            return ClaimType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
