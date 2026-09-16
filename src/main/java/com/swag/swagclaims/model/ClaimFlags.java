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
 *
 * <p><b>2026-09-15 flag batch</b> (44-item spec, one duplicate): every key below
 * {@link #PVP} was added in this batch. Enforcement lives in {@code ClaimFlagListener} for
 * at-location checks (damage/interact/environment/entity flags), and in
 * {@code ClaimTransitionListener} for boundary-crossing checks (enter/exit, bans, enter/exit
 * messages) — see each class's javadoc. A few notes on judgment calls made for this batch:
 * <ul>
 *     <li>The spec's item "No Fire Damage" (#42) is a verified duplicate of the pre-existing
 *     {@link #NO_FIRE_DAMAGE} flag (players not taking fire/lava damage) — no new key was added
 *     for it; {@link #NO_FIRE_DAMAGE}'s existing enforcement in {@code ClaimFlagListener#onEntityDamage}
 *     was reviewed and confirmed correct.</li>
 *     <li>{@link #NO_MOB_DAMAGE} is functionally identical to the pre-existing
 *     {@link #NO_PLAYER_DAMAGE_BY_MONSTER} (both mean "hostile mobs can't damage players") — kept
 *     as its own key since the spec asked for it by name, but both flags gate the exact same check
 *     in {@code ClaimFlagListener#onEntityDamageByEntity}, mirroring this class's existing
 *     nomonsters/nomonsterspawns precedent of two flag keys sharing one enforcement path.</li>
 *     <li>{@link #NO_HOMES_SET}, {@link #NO_WARPS_SET}, {@link #NO_BACK}, {@link #NO_TOP} and
 *     {@link #NO_PLAYER_WARPS} are enforced by intercepting the relevant command name via
 *     {@code PlayerCommandPreprocessEvent} — SwagCore (which owns {@code /sethome}, {@code /setwarp},
 *     {@code /back}, {@code /top}) publishes no dedicated Bukkit event for any of these actions, and
 *     no "PlayerWarps"-style plugin exists anywhere in this ecosystem to hook directly. This is a
 *     documented limitation: command-name matching only catches the literal command (not e.g. a
 *     GUI-driven equivalent), and breaks if SwagCore's command names ever change.</li>
 *     <li>{@link #NO_ARMOR_HIDE} has no real enforcement — nothing in this ecosystem (no
 *     cosmetic-armor plugin, no vanilla "hide own armor" mechanic) exists for it to hook into. The
 *     flag key is registered (settable, listed, shown in the GUI) but
 *     {@code ClaimFlagListener} intentionally has no handler for it — see the comment at the
 *     bottom of that class.</li>
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

    // ── 2026-09-15 batch ────────────────────────────────────────────────────

    public static final String NO_ENTER = "noenter";
    public static final String NO_EXIT = "noexit";
    public static final String NO_FLY = "nofly";
    public static final String NO_CHORUS_FRUIT = "nochorusfruit";
    public static final String NO_ENDER_PEARL = "noenderpearl";
    public static final String NO_HOMES_SET = "nohomesset";
    public static final String NO_WARPS_SET = "nowarpsset";
    public static final String NO_PLAYER_WARPS = "noplayerwarps";
    public static final String PROTECT_NAMED_MOBS = "protectnamedmobs";
    public static final String NO_ANIMAL_DAMAGE = "noanimaldamage";
    public static final String NO_BACK = "noback";
    public static final String NO_ELYTRA = "noelytra";
    public static final String NO_VILLAGER_TRADES = "novillagertrades";
    public static final String NO_ITEM_DROP = "noitemdrop";
    public static final String NO_ITEM_PICKUP = "noitempickup";
    public static final String NO_INVISIBILITY = "noinvisibility";
    public static final String ALLOW_PRESSURE_PLATES = "allowpressureplates";
    public static final String EXIT_MESSAGE = "exitmessage";
    public static final String ENTER_MESSAGE = "entermessage";
    public static final String ALLOW_LEVERS = "allowlevers";
    public static final String ALLOW_DOORS = "allowdoors";
    public static final String ALLOW_TRAPDOORS = "allowtrapdoors";
    public static final String NO_MOB_DAMAGE = "nomobdamage";
    public static final String NO_MOB_FALL_DAMAGE = "nomobfalldamage";
    public static final String NO_THORN_DAMAGE = "nothorndamage";
    public static final String NO_TOP = "notop";
    public static final String NO_POTION_EFFECTS = "nopotioneffects";
    public static final String NO_RESIZING = "noresizing";
    public static final String NO_WINDCHARGE = "nowindcharge";
    public static final String NO_ARMOR_HIDE = "noarmorhide";
    public static final String CHEST_VIEWING = "chestviewing";
    public static final String NO_COPPER_OXIDIZATION = "nocopperoxidization";
    public static final String NO_FIRE_SPREAD = "nofirespread";
    public static final String NO_GRASS_SPREAD = "nograssspread";
    public static final String NO_GROWTH = "nogrowth";
    public static final String NO_VINE_GROWTH = "novinegrowth";
    public static final String NO_ICE_FORM = "noiceform";
    public static final String NO_ICE_MELT = "noicemelt";
    public static final String NO_LEAF_DECAY = "noleafdecay";
    public static final String NO_SNOW_FORM = "nosnowform";
    public static final String NO_SCULK_SPREAD = "nosculkspread";
    public static final String NO_CONCRETE_FORM = "noconcreteform";
    public static final String ALLOW_EXPLOSIONS = "allowexplosions";

    /** Every known flag key, in catalog order — used for tab-completion and the /claimflag listing. */
    public static final List<String> ALL = List.of(
            NO_MONSTERS, NO_MONSTER_SPAWNS, NO_HUNGER, NO_FALL_DAMAGE, NO_FIRE_DAMAGE,
            NO_EXPLOSION_DAMAGE, NO_ITEM_DAMAGE, NO_PLAYER_DAMAGE_BY_MONSTER, NO_FLUID_FLOW,
            NO_CORAL_DEATH, NO_BLOCK_GRAVITY, KEEP_INVENTORY, HEALTH_REGEN, PLAYER_TIME,
            PLAYER_WEATHER, ENTER_ACTION_BAR, PVP,

            NO_ENTER, NO_EXIT, NO_FLY, NO_CHORUS_FRUIT, NO_ENDER_PEARL, NO_HOMES_SET,
            NO_WARPS_SET, NO_PLAYER_WARPS, PROTECT_NAMED_MOBS, NO_ANIMAL_DAMAGE, NO_BACK,
            NO_ELYTRA, NO_VILLAGER_TRADES, NO_ITEM_DROP, NO_ITEM_PICKUP, NO_INVISIBILITY,
            ALLOW_PRESSURE_PLATES, EXIT_MESSAGE, ENTER_MESSAGE, ALLOW_LEVERS, ALLOW_DOORS,
            ALLOW_TRAPDOORS, NO_MOB_DAMAGE, NO_MOB_FALL_DAMAGE, NO_THORN_DAMAGE, NO_TOP,
            NO_POTION_EFFECTS, NO_RESIZING, NO_WINDCHARGE, NO_ARMOR_HIDE, CHEST_VIEWING,
            NO_COPPER_OXIDIZATION, NO_FIRE_SPREAD, NO_GRASS_SPREAD, NO_GROWTH, NO_VINE_GROWTH,
            NO_ICE_FORM, NO_ICE_MELT, NO_LEAF_DECAY, NO_SNOW_FORM, NO_SCULK_SPREAD,
            NO_CONCRETE_FORM, ALLOW_EXPLOSIONS
    );

    public static boolean isKnown(String key) {
        return key != null && ALL.contains(key.toLowerCase());
    }
}
