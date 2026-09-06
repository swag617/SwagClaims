package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Item;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Enforces the GPFlags-compatible flag catalog defined in {@link ClaimFlags} against real Bukkit
 * events. Every check goes through {@link FlagManager#isSetAt} / {@code getParamsAt}, which
 * already applies claim-then-world resolution — this class never needs to know whether a given
 * location is inside a claim or relying on a world default.
 *
 * <p>{@code nomonsters} vs {@code nomonsterspawns} (judgment call): {@code nomonsterspawns} only
 * cancels {@link CreatureSpawnEvent} — a hostile mob that wanders in from outside (or is moved in
 * by a player) is left alone. {@code nomonsters} does that AND periodically sweeps away any
 * hostile mobs already present, via {@link #purgeMonsters()}. Both flags gate the exact same
 * "hostile monster" check ({@link #isHostileMonster}, which — since {@code org.bukkit.entity.Monster}
 * doesn't cover slimes/magma cubes/phantoms/ghasts/shulkers — explicitly includes those too).
 */
public class ClaimFlagListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;
    private final FlagManager flagManager;

    public ClaimFlagListener(SwagClaimsPlugin plugin, ClaimManager claimManager, FlagManager flagManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.flagManager = flagManager;

        // nomonsters sweep — every 5 seconds, matching ClaimManager's "simple is fine here" style
        // rather than maintaining a claim/entity index just for this.
        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeMonsters, 100L, 100L);
        // healthregen — every second, independent of vanilla saturation-based regen.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHealthRegen, 20L, 20L);
    }

    // ── nomonsters / nomonsterspawns ────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!isHostileMonster(event.getEntity())) return;
        Location loc = event.getLocation();
        if (flagManager.isSetAt(loc, ClaimFlags.NO_MONSTERS) || flagManager.isSetAt(loc, ClaimFlags.NO_MONSTER_SPAWNS)) {
            event.setCancelled(true);
        }
    }

    private void purgeMonsters() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!isHostileMonster(entity)) continue;
                if (flagManager.isSetAt(entity.getLocation(), ClaimFlags.NO_MONSTERS)) {
                    entity.remove();
                }
            }
        }
    }

    private boolean isHostileMonster(Entity entity) {
        return entity instanceof Monster || entity instanceof Slime || entity instanceof MagmaCube
                || entity instanceof Phantom || entity instanceof Ghast || entity instanceof Shulker;
    }

    // ── nohunger ────────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFoodLevel() >= player.getFoodLevel()) return; // only gate decreases — let eating through

        Location loc = player.getLocation();
        if (!flagManager.isSetAt(loc, ClaimFlags.NO_HUNGER)) return;

        String params = flagManager.getParamsAt(loc, ClaimFlags.NO_HUNGER);
        Integer divisor = parsePositiveInt(params);
        if (divisor == null) {
            event.setCancelled(true); // no params — fully block, the common case
            return;
        }
        if (ThreadLocalRandom.current().nextInt(divisor) != 0) {
            event.setCancelled(true);
        }
    }

    // ── nofalldamage / nofiredamage / noexplosiondamage / noitemdamage ─────

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        Location loc = entity.getLocation();
        EntityDamageEvent.DamageCause cause = event.getCause();

        if (entity instanceof Item) {
            if (isFireOrExplosionCause(cause) && flagManager.isSetAt(loc, ClaimFlags.NO_ITEM_DAMAGE)) {
                event.setCancelled(true);
            }
            return;
        }

        if (!(entity instanceof Player)) return;

        switch (cause) {
            case FALL -> {
                if (flagManager.isSetAt(loc, ClaimFlags.NO_FALL_DAMAGE)) event.setCancelled(true);
            }
            case FIRE, FIRE_TICK, LAVA -> {
                if (flagManager.isSetAt(loc, ClaimFlags.NO_FIRE_DAMAGE)) event.setCancelled(true);
            }
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> {
                if (flagManager.isSetAt(loc, ClaimFlags.NO_EXPLOSION_DAMAGE)) event.setCancelled(true);
            }
            default -> {
            }
        }
    }

    private boolean isFireOrExplosionCause(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION;
    }

    // ── noplayerdamagebymonster ─────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!isHostileMonster(damager)) return;

        if (flagManager.isSetAt(player.getLocation(), ClaimFlags.NO_PLAYER_DAMAGE_BY_MONSTER)) {
            event.setCancelled(true);
        }
    }

    // ── nofluidflow ─────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        Location from = event.getBlock().getLocation();
        Location to = event.getToBlock().getLocation();
        if (flagManager.isSetAt(from, ClaimFlags.NO_FLUID_FLOW) || flagManager.isSetAt(to, ClaimFlags.NO_FLUID_FLOW)) {
            event.setCancelled(true);
        }
    }

    // ── nocoraldeath ────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onCoralFade(BlockFadeEvent event) {
        if (!isCoral(event.getBlock().getType())) return;
        if (flagManager.isSetAt(event.getBlock().getLocation(), ClaimFlags.NO_CORAL_DEATH)) {
            event.setCancelled(true);
        }
    }

    private boolean isCoral(Material type) {
        String name = type.name();
        return name.endsWith("_CORAL") || name.endsWith("_CORAL_FAN") || name.endsWith("_CORAL_BLOCK")
                || name.endsWith("_CORAL_WALL_FAN") || name.equals("DEAD_TUBE_CORAL_BLOCK");
    }

    // ── noblockgravity ──────────────────────────────────────────────────────

    /**
     * Cancels the physics update that turns a gravity-affected block into a falling-block entity.
     * This is the same mechanism GriefPrevention-style plugins use for a "gravity" flag — there is
     * no dedicated "block is about to fall" event, and cancelling {@code EntityChangeBlockEvent}
     * after the fact leaves an orphaned FallingBlock entity hanging in the air.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (!isGravityAffected(event.getChangedType())) return;
        if (flagManager.isSetAt(event.getBlock().getLocation(), ClaimFlags.NO_BLOCK_GRAVITY)) {
            event.setCancelled(true);
        }
    }

    private boolean isGravityAffected(Material type) {
        if (type == Material.SAND || type == Material.RED_SAND || type == Material.GRAVEL
                || type == Material.POINTED_DRIPSTONE || type == Material.SCAFFOLDING) {
            return true;
        }
        String name = type.name();
        return name.endsWith("_CONCRETE_POWDER") || name.contains("ANVIL");
    }

    // ── keepinventory ───────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location loc = player.getLocation();
        if (!flagManager.isSetAt(loc, ClaimFlags.KEEP_INVENTORY)) return;

        // Normalize: params 'true'/empty/anything but literal 'false' all mean "keep inventory",
        // matching the live data's mix of {}, {'true'} and {'false'?} param shapes.
        String params = flagManager.getParamsAt(loc, ClaimFlags.KEEP_INVENTORY);
        if (params != null && params.equalsIgnoreCase("false")) return;

        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    // ── healthregen ─────────────────────────────────────────────────────────

    /**
     * Judgment call: params is interpreted as health points restored per second (default 1.0 when
     * absent/unparsable), applied by this repeating task independent of vanilla saturation-based
     * natural regen (so it works even with hunger/saturation depleted, or alongside {@code nohunger}).
     */
    private void tickHealthRegen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getHealth() <= 0) continue;
            var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
            double max = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
            if (player.getHealth() >= max) continue;

            Location loc = player.getLocation();
            if (!flagManager.isSetAt(loc, ClaimFlags.HEALTH_REGEN)) continue;

            double amount = parsePositiveDouble(flagManager.getParamsAt(loc, ClaimFlags.HEALTH_REGEN), 1.0);
            player.setHealth(Math.min(max, player.getHealth() + amount));
        }
    }

    // ── playertime / playerweather ──────────────────────────────────────────

    /**
     * Recomputes and (re)applies per-player time/weather overrides for the player's *current*
     * location. Called on every claim transition rather than tracking separate enter/exit state —
     * simpler, and self-correcting if a claim's flags change while the player is standing in it.
     */
    public void refreshEnvironmentEffects(Player player) {
        Location loc = player.getLocation();

        String timeParam = flagManager.getParamsAt(loc, ClaimFlags.PLAYER_TIME);
        if (flagManager.isSetAt(loc, ClaimFlags.PLAYER_TIME) && timeParam != null && !timeParam.isBlank()) {
            player.setPlayerTime(parseTimeParam(timeParam), false);
        } else {
            player.resetPlayerTime();
        }

        String weatherParam = flagManager.getParamsAt(loc, ClaimFlags.PLAYER_WEATHER);
        if (flagManager.isSetAt(loc, ClaimFlags.PLAYER_WEATHER) && weatherParam != null && !weatherParam.isBlank()) {
            player.setPlayerWeather(weatherParam.equalsIgnoreCase("rain") ? WeatherType.DOWNFALL : WeatherType.CLEAR);
        } else if (player.getPlayerWeather() != null) {
            player.resetPlayerWeather();
        }
    }

    /** "day"/"night" keywords match EssentialsX's /time convention; anything else is parsed as a raw tick value. */
    private long parseTimeParam(String param) {
        return switch (param.trim().toLowerCase()) {
            case "day" -> 1000L;
            case "night" -> 13000L;
            case "noon" -> 6000L;
            case "midnight" -> 18000L;
            default -> {
                try {
                    yield Long.parseLong(param.trim());
                } catch (NumberFormatException e) {
                    yield 1000L;
                }
            }
        };
    }

    // ── enteractionbar ──────────────────────────────────────────────────────

    /** Fired once per actual claim-enter transition (not on every move tick) by {@code ClaimTransitionListener}. */
    public void showEnterActionBar(Player player, Claim claim) {
        if (!flagManager.isSet(claim, ClaimFlags.ENTER_ACTION_BAR)) return;
        String message = flagManager.getParams(claim, ClaimFlags.ENTER_ACTION_BAR);
        if (message == null || message.isBlank()) return;

        String colorized = org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(colorized));
    }

    // ── parsing helpers ─────────────────────────────────────────────────────

    private Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parsePositiveDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            double value = Double.parseDouble(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
