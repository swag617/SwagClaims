package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SculkBloomEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.potion.PotionEffectType;

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

    // ── noplayerdamagebymonster / nomobdamage ───────────────────────────────
    // nomobdamage is functionally identical to noplayerdamagebymonster (both mean "hostile mobs
    // can't damage players") — kept as its own key per the 2026-09-15 spec, gating the same check,
    // mirroring this class's existing nomonsters/nomonsterspawns precedent of two keys sharing one
    // enforcement path.

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!isHostileMonster(damager)) return;

        Location loc = player.getLocation();
        if (flagManager.isSetAt(loc, ClaimFlags.NO_PLAYER_DAMAGE_BY_MONSTER) || flagManager.isSetAt(loc, ClaimFlags.NO_MOB_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    // ── protectnamedmobs ─────────────────────────────────────────────────────

    /** A nametagged mob can't be damaged/killed by a player who doesn't hold at least BUILD trust here. */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerDamagesNamedMob(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim.getCustomName() == null) return;

        Player attacker = resolveDamagerPlayer(event.getDamager());
        if (attacker == null) return;

        Location loc = victim.getLocation();
        if (!flagManager.isSetAt(loc, ClaimFlags.PROTECT_NAMED_MOBS)) return;

        if (!claimManager.hasPermission(attacker, loc, com.swag.swagclaims.model.TrustLevel.BUILD)) {
            event.setCancelled(true);
        }
    }

    // ── noanimaldamage ───────────────────────────────────────────────────────

    /** Passive/animal mobs ({@link Animals}) can't be damaged at all here, regardless of cause or attacker. */
    @EventHandler(ignoreCancelled = true)
    public void onAnimalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Animals)) return;
        if (flagManager.isSetAt(event.getEntity().getLocation(), ClaimFlags.NO_ANIMAL_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    // ── nomobfalldamage ──────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onMobFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof LivingEntity) || event.getEntity() instanceof Player) return;
        if (flagManager.isSetAt(event.getEntity().getLocation(), ClaimFlags.NO_MOB_FALL_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    // ── nothorndamage ────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onThornsDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.THORNS) return;
        if (flagManager.isSetAt(event.getEntity().getLocation(), ClaimFlags.NO_THORN_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    /** Resolves the acting Player behind a damage source — either the entity itself, or (for a projectile) its shooter. */
    private Player resolveDamagerPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
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
     * Cancels the transition of a gravity-affected block into a {@code FallingBlock} entity.
     *
     * <p>This previously listened on {@link BlockPhysicsEvent}, filtering by
     * {@code isGravityAffected(event.getChangedType())} — that never actually stopped anything:
     * cancelling a physics-check event only suppresses that one recheck, not the scheduled block
     * tick that later spawns the falling entity (sand/gravel/concrete powder/anvils don't fall via
     * the physics-check codepath at all), so the flag had zero observable effect in either state
     * (reported: toggling it on didn't stop falling blocks, toggling it off "also" showed no
     * change — both are explained by the old handler never actually intercepting the fall).
     * {@link EntityChangeBlockEvent}, filtered to {@link EntityType#FALLING_BLOCK}, fires at the
     * moment the source block would disappear to spawn the falling entity — cancelling it here
     * leaves the block in place and no entity ever spawns, mirroring the enderman/silverfish
     * griefing guard in {@code ClaimProtectionListener#onEntityChangeBlock} which uses the same
     * event for a different entity-type filter.
     */
    @EventHandler(ignoreCancelled = true)
    public void onFallingBlockForm(EntityChangeBlockEvent event) {
        if (event.getEntityType() != EntityType.FALLING_BLOCK) return;
        if (!isGravityAffected(event.getBlock().getType())) return;
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

        // nofly / noelytra: force off if the player is already flying/gliding when they arrive.
        // Doesn't touch creative/spectator flight — see the nofly section below for why.
        if (flagManager.isSetAt(loc, ClaimFlags.NO_FLY) && player.isFlying()
                && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
        }
        if (flagManager.isSetAt(loc, ClaimFlags.NO_ELYTRA) && player.isGliding()) {
            player.setGliding(false);
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

    // ── entermessage / exitmessage ──────────────────────────────────────────
    // Plain chat messages, unlike enteractionbar's action-bar flash — called by
    // ClaimTransitionListener on the same real enter/exit transitions it already detects.

    public void showEnterMessage(Player player, Claim claim) {
        sendClaimMessage(player, claim, ClaimFlags.ENTER_MESSAGE);
    }

    public void showExitMessage(Player player, Claim claim) {
        sendClaimMessage(player, claim, ClaimFlags.EXIT_MESSAGE);
    }

    private void sendClaimMessage(Player player, Claim claim, String flagKey) {
        if (!flagManager.isSet(claim, flagKey)) return;
        String message = flagManager.getParams(claim, flagKey);
        if (message == null || message.isBlank()) return;
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
    }

    // ── nofly / PlayerToggleFlightEvent ─────────────────────────────────────
    // Deliberately does not touch creative/spectator flight (a landowner still needs to be able to
    // creative-build normally) — this only stops survival/adventure flight granted by another
    // plugin (a "/fly" command, a kit perk, etc.), matching the flag's likely real use case
    // (anti-fly-hack containment) rather than crippling admin/creative workflows.

    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!event.isFlying()) return; // only gate turning flight ON
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (flagManager.isSetAt(player.getLocation(), ClaimFlags.NO_FLY)) {
            event.setCancelled(true);
        }
    }

    // ── noelytra / EntityToggleGlideEvent ────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding()) return; // only gate turning gliding ON
        if (!(event.getEntity() instanceof Player player)) return;
        if (flagManager.isSetAt(player.getLocation(), ClaimFlags.NO_ELYTRA)) {
            event.setCancelled(true);
        }
    }

    // ── nochorusfruit / noenderpearl ─────────────────────────────────────────
    // Absolute blocks (unlike noenter/noexit) — these gate the mechanic itself regardless of the
    // player's trust level, matching the spec's "block chorus fruit teleportation"/"block ender
    // pearl use/landing" wording. Checked at both ends of the teleport so a pearl/fruit thrown from
    // outside can't be used to land inside a flagged claim either.

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        String flagKey = switch (cause) {
            case CHORUS_FRUIT -> ClaimFlags.NO_CHORUS_FRUIT;
            case ENDER_PEARL -> ClaimFlags.NO_ENDER_PEARL;
            default -> null;
        };
        if (flagKey == null) return;

        if (flagManager.isSetAt(event.getFrom(), flagKey) || (event.getTo() != null && flagManager.isSetAt(event.getTo(), flagKey))) {
            event.setCancelled(true);
        }
    }

    // ── novillagertrades ─────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof AbstractVillager villager)) return;
        if (flagManager.isSetAt(villager.getLocation(), ClaimFlags.NO_VILLAGER_TRADES)) {
            event.setCancelled(true);
        }
    }

    // ── noitemdrop / noitempickup ────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (flagManager.isSetAt(event.getPlayer().getLocation(), ClaimFlags.NO_ITEM_DROP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (flagManager.isSetAt(player.getLocation(), ClaimFlags.NO_ITEM_PICKUP)) {
            event.setCancelled(true);
        }
    }

    // ── noinvisibility / nopotioneffects ─────────────────────────────────────
    // noinvisibility targets invisibility specifically (still gated even if nopotioneffects isn't
    // set); nopotioneffects is the broader "block every effect application" flag. Both only gate
    // an effect being added/changed — an effect wearing off (REMOVED/CLEARED) is never blocked.

    @EventHandler(ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.getAction() != EntityPotionEffectEvent.Action.ADDED && event.getAction() != EntityPotionEffectEvent.Action.CHANGED) {
            return;
        }
        Location loc = event.getEntity().getLocation();
        boolean isInvisibility = event.getNewEffect() != null && event.getNewEffect().getType().equals(PotionEffectType.INVISIBILITY);

        if (isInvisibility && flagManager.isSetAt(loc, ClaimFlags.NO_INVISIBILITY)) {
            event.setCancelled(true);
            return;
        }
        if (flagManager.isSetAt(loc, ClaimFlags.NO_POTION_EFFECTS)) {
            event.setCancelled(true);
        }
    }

    // ── nowindcharge ─────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onWindChargeLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof AbstractWindCharge windCharge)) return;
        if (flagManager.isSetAt(windCharge.getLocation(), ClaimFlags.NO_WINDCHARGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWindChargeExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof AbstractWindCharge)) return;
        if (flagManager.isSetAt(event.getLocation(), ClaimFlags.NO_WINDCHARGE)) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    // ── nocopperoxidization / noicemelt ──────────────────────────────────────
    // Both are BlockFadeEvent cases (like nocoraldeath above), differentiated by the block's
    // current material rather than the event type.

    @EventHandler(ignoreCancelled = true)
    public void onCopperOrIceFade(BlockFadeEvent event) {
        Material type = event.getBlock().getType();
        Location loc = event.getBlock().getLocation();

        if (isCopper(type)) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_COPPER_OXIDIZATION)) {
                event.setCancelled(true);
            }
        } else if (type == Material.ICE) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_ICE_MELT)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isCopper(Material type) {
        String name = type.name();
        return name.contains("COPPER") && !name.startsWith("WAXED_");
    }

    // ── nograssspread / novinegrowth / nosculkspread ────────────────────────
    // All three are BlockSpreadEvent cases, differentiated by the spreading block's new material.

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Material newType = event.getNewState().getType();
        Location loc = event.getBlock().getLocation();
        String name = newType.name();

        if (newType == Material.GRASS_BLOCK) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_GRASS_SPREAD)) event.setCancelled(true);
        } else if (name.contains("VINE")) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_VINE_GROWTH)) event.setCancelled(true);
        } else if (name.equals("SCULK") || name.equals("SCULK_VEIN")) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_SCULK_SPREAD)) event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSculkBloom(SculkBloomEvent event) {
        if (flagManager.isSetAt(event.getBlock().getLocation(), ClaimFlags.NO_SCULK_SPREAD)) {
            event.setCancelled(true);
        }
    }

    // ── nogrowth ─────────────────────────────────────────────────────────────
    // Crops/kelp/cactus/sugarcane/bamboo etc. (BlockGrowEvent) and sapling-to-tree growth
    // (StructureGrowEvent) — "all plant growth" per the spec.

    @EventHandler(ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        if (flagManager.isSetAt(event.getBlock().getLocation(), ClaimFlags.NO_GROWTH)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (flagManager.isSetAt(event.getLocation(), ClaimFlags.NO_GROWTH)) {
            event.setCancelled(true);
        }
    }

    // ── noiceform / nosnowform / noconcreteform ──────────────────────────────
    // All three are BlockFormEvent cases (ice from frost walker, snow from weather, concrete from
    // concrete powder touching water), differentiated by the forming block's material.

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Material newType = event.getNewState().getType();
        Location loc = event.getBlock().getLocation();
        String name = newType.name();

        if (newType == Material.ICE || newType == Material.FROSTED_ICE) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_ICE_FORM)) event.setCancelled(true);
        } else if (newType == Material.SNOW) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_SNOW_FORM)) event.setCancelled(true);
        } else if (name.endsWith("_CONCRETE")) {
            if (flagManager.isSetAt(loc, ClaimFlags.NO_CONCRETE_FORM)) event.setCancelled(true);
        }
    }

    // ── noleafdecay ──────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (flagManager.isSetAt(event.getBlock().getLocation(), ClaimFlags.NO_LEAF_DECAY)) {
            event.setCancelled(true);
        }
    }

    // ── noarmorhide (documented limitation — no enforcement) ─────────────────
    // Nothing in this ecosystem provides a "hide armor" mechanic to hook: no cosmetic-armor plugin
    // exists here, and vanilla Minecraft has no player-facing "hide my own armor" toggle (an
    // ArmorStand's equipment visibility is a different, unrelated concept). The flag key is fully
    // registered (settable via /claimflag, listed, shown in ClaimFlagGUI) so it round-trips
    // correctly if a future plugin/mechanic appears to hook it against, but there is intentionally
    // no event handler for it here — see ClaimFlags#NO_ARMOR_HIDE's javadoc.

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
