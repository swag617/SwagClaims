package com.swag.swagclaims.model;

/**
 * Trust levels a player (or the special {@code public} / {@code g:<group>} targets) can hold
 * on a claim, from least to most privileged. Levels are hierarchical: holding a higher level
 * implies every permission granted by the levels below it.
 */
public enum TrustLevel {

    /** Can interact with non-container blocks (doors, levers, buttons, etc.) and ride entities. */
    ACCESS,

    /** ACCESS, plus can open/use containers (chests, furnaces, hoppers, etc.). */
    CONTAINER,

    /** CONTAINER, plus can build (place/break blocks). */
    BUILD,

    /** BUILD, plus can manage the claim itself (grant/revoke trust, resize, subdivide). */
    MANAGE;

    /**
     * Returns true if this trust level grants at least the permissions of {@code other}.
     * MANAGE implies BUILD implies CONTAINER implies ACCESS.
     */
    public boolean implies(TrustLevel other) {
        if (other == null) return false;
        return this.ordinal() >= other.ordinal();
    }

    /** Case-insensitive lookup; returns null if the name doesn't match a known trust level. */
    public static TrustLevel fromString(String name) {
        if (name == null) return null;
        try {
            return TrustLevel.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
