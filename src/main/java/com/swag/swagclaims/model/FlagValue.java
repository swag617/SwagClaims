package com.swag.swagclaims.model;

/**
 * A single resolved flag entry: whether it's on, and its optional string parameter (a hunger
 * drain divisor, a time-of-day keyword, an action bar message, etc. — meaning is entirely
 * flag-specific, see {@link ClaimFlags}). Immutable — flags are replaced wholesale on change,
 * never mutated in place.
 */
public class FlagValue {

    private final String params;
    private final boolean value;

    public FlagValue(String params, boolean value) {
        this.params = params;
        this.value = value;
    }

    /** Nullable — most flags don't use a parameter. */
    public String getParams() {
        return params;
    }

    public boolean isValue() {
        return value;
    }
}
