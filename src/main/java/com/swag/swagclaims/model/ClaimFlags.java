package com.swag.swagclaims.model;

import java.util.List;

/**
 * The known GPFlags-compatible flag keys, imported verbatim from the live server's real
 * {@code flags.yml} catalog (see the phase spec this class was built from). Every key is stored
 * lowercase in the database and resolved case-insensitively everywhere — {@link FlagManager}
 * lowercases on every read/write.
 *
 * <p>Two flags carry a documented interpretation of their {@code params} column since the source
 * data didn't pin one down precisely:
 * <ul>
 *     <li>{@link #NO_HUNGER} — params, when present and numeric, is a "1-in-N chance the hunger
 *     drain is still allowed through" divisor (bigger N = slower drain). No params (the common
 *     case) fully blocks hunger loss.</li>
 *     <li>{@link #HEALTH_REGEN} — params is health points restored per second (default 1.0 if
 *     absent/unparsable), applied by a repeating task independent of vanilla saturation regen.</li>
 * </ul>
 */
public final class ClaimFlags {

    private ClaimFlags() {
    }

    public static final String NO_MONSTERS = "nomonsters";
    public static final String NO_MONSTER_SPAWNS = "nomonsterspawns";
    public static final String NO_HUNGER = "nohunger";
    public static final String NO_FALL_DAMAGE = "nofalldamage";
    public static final String NO_FIRE_DAMAGE = "nofiredamage";
    public static final String NO_EXPLOSION_DAMAGE = "noexplosiondamage";
    public static final String NO_ITEM_DAMAGE = "noitemdamage";
    public static final String NO_PLAYER_DAMAGE_BY_MONSTER = "noplayerdamagebymonster";
    public static final String NO_FLUID_FLOW = "nofluidflow";
    public static final String NO_CORAL_DEATH = "nocoraldeath";
    public static final String NO_BLOCK_GRAVITY = "noblockgravity";
    public static final String KEEP_INVENTORY = "keepinventory";
    public static final String HEALTH_REGEN = "healthregen";
    public static final String PLAYER_TIME = "playertime";
    public static final String PLAYER_WEATHER = "playerweather";
    public static final String ENTER_ACTION_BAR = "enteractionbar";
    public static final String PVP = "pvp";

    /** Every known flag key, in catalog order — used for tab-completion and the /claimflag listing. */
    public static final List<String> ALL = List.of(
            NO_MONSTERS, NO_MONSTER_SPAWNS, NO_HUNGER, NO_FALL_DAMAGE, NO_FIRE_DAMAGE,
            NO_EXPLOSION_DAMAGE, NO_ITEM_DAMAGE, NO_PLAYER_DAMAGE_BY_MONSTER, NO_FLUID_FLOW,
            NO_CORAL_DEATH, NO_BLOCK_GRAVITY, KEEP_INVENTORY, HEALTH_REGEN, PLAYER_TIME,
            PLAYER_WEATHER, ENTER_ACTION_BAR, PVP
    );

    public static boolean isKnown(String key) {
        return key != null && ALL.contains(key.toLowerCase());
    }
}
