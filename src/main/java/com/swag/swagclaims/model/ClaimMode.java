package com.swag.swagclaims.model;

/**
 * Per-world claim mode, mirroring GriefPrevention's {@code Claims.Mode} config. Read from
 * config.yml's {@code worlds.<world>.claim-mode} (falling back to {@code worlds.Default}).
 */
public enum ClaimMode {

    /** The claim tool works normally — players can create/resize claims in this world. */
    SURVIVAL,

    /** New top-level claims are rejected in this world; the claim tool tells the player why. */
    DISABLED;

    /** Case-insensitive lookup; returns null if the name doesn't match a known mode. */
    public static ClaimMode fromString(String name) {
        if (name == null) return null;
        try {
            return ClaimMode.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
