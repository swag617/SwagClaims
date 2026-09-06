package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import com.swag.swagclaims.model.FlagValue;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PvP toggles and combat-adjacent protections that don't fit {@link ClaimProtectionListener}'s
 * "does this block-level action have claim trust" shape: per-world PvP enable/disable, fresh-spawn
 * immunity, combat-logout punishment, blocked commands while tagged in combat, pet protection, and
 * lava/flint-and-steel restrictions near other players outside claims.
 */
public class PvPProtectionListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    /** Players currently tagged "in combat" — cleared automatically after combat-timeout-seconds. */
    private final Set<UUID> inCombat = ConcurrentHashMap.newKeySet();

    /** Player -> timestamp (millis) their post-respawn PvP immunity ends. */
    private final Map<UUID, Long> freshSpawnUntil = new ConcurrentHashMap<>();

    public PvPProtectionListener(SwagClaimsPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            handlePetDamage(event);
            return;
        }

        Player attacker = resolveAttackingPlayer(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;

        if (!isPvpAllowed(victim)) {
            event.setCancelled(true);
            return;
        }

        if (plugin.getClaimsConfig().isProtectFreshSpawns()) {
            Long until = freshSpawnUntil.get(victim.getUniqueId());
            if (until != null && until > System.currentTimeMillis()) {
                event.setCancelled(true);
                return;
            }
        }

        tagInCombat(victim.getUniqueId());
        tagInCombat(attacker.getUniqueId());
    }

    /** Tamed animals can't be damaged inside a claim without BUILD trust there. */
    private void handlePetDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Tameable tameable) || !tameable.isTamed()) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player attacker = resolveAttackingPlayer(event.getDamager());
        if (attacker == null) return;
        if (attacker.hasPermission("swagclaims.admin.ignoreclaims")) return;

        Location loc = event.getEntity().getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(attacker, loc, TrustLevel.BUILD)) {
            event.setCancelled(true);
            plugin.getMessages().send(attacker, "protection.no-pet-damage");
        }
    }

    /**
     * A claim's (or its world's) {@code pvp} flag, if explicitly set, overrides the world-mode
     * default from {@link com.swag.swagclaims.config.ClaimsConfig#isPvpEnabled}. Resolved at the
     * victim's location — matches every other check in this method already using the victim's
     * position as the source of truth.
     */
    private boolean isPvpAllowed(Player victim) {
        FlagValue override = plugin.getFlagManager().resolveAt(victim.getLocation(), ClaimFlags.PVP);
        if (override != null) return override.isValue();
        return plugin.getClaimsConfig().isPvpEnabled(victim.getWorld().getName());
    }

    private Player resolveAttackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void tagInCombat(UUID uuid) {
        inCombat.add(uuid);
        long delayTicks = plugin.getClaimsConfig().getCombatTimeoutSeconds() * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> inCombat.remove(uuid), delayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.getClaimsConfig().isProtectFreshSpawns()) return;
        long until = System.currentTimeMillis() + plugin.getClaimsConfig().getFreshSpawnProtectionSeconds() * 1000L;
        freshSpawnUntil.put(event.getPlayer().getUniqueId(), until);
    }

    /** Kills a player who disconnects while tagged in combat — the simplest correct combat-logout punishment. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getClaimsConfig().isPunishLogout()) return;
        Player player = event.getPlayer();
        if (inCombat.remove(player.getUniqueId()) && player.isOnline() && player.getHealth() > 0) {
            player.setHealth(0.0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!inCombat.contains(player.getUniqueId())) return;

        String[] parts = event.getMessage().substring(1).split(" ", 2);
        String cmd = parts[0].toLowerCase();
        for (String blocked : plugin.getClaimsConfig().getBlockedCombatCommands()) {
            if (cmd.equalsIgnoreCase(blocked)) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "pvp.command-blocked-combat");
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLavaBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!plugin.getClaimsConfig().isPreventLavaDumpingNearPlayers()) return;
        if (event.getBucket() != Material.LAVA_BUCKET) return;

        Location loc = event.getBlockClicked().getLocation();
        if (claimManager.getClaimAt(loc) != null) return; // claims already gate their own build protection

        if (isNearOtherPlayer(event.getPlayer(), loc)) {
            event.setCancelled(true);
            plugin.getMessages().send(event.getPlayer(), "pvp.no-lava-near-players");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlintAndSteel(PlayerInteractEvent event) {
        if (!plugin.getClaimsConfig().isPreventFlintAndSteelNearPlayers()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FLINT_AND_STEEL) return;

        var clicked = event.getClickedBlock();
        if (clicked == null || event.getBlockFace() == null) return;

        Location loc = clicked.getRelative(event.getBlockFace()).getLocation();
        if (claimManager.getClaimAt(loc) != null) return;

        if (isNearOtherPlayer(event.getPlayer(), loc)) {
            event.setCancelled(true);
            plugin.getMessages().send(event.getPlayer(), "pvp.no-fire-near-players");
        }
    }

    private boolean isNearOtherPlayer(Player source, Location loc) {
        if (loc.getWorld() == null) return false;
        double radius = plugin.getClaimsConfig().getPvpDangerRadius();
        double radiusSquared = radius * radius;
        for (Player other : loc.getWorld().getPlayers()) {
            if (other.equals(source)) continue;
            if (other.getLocation().distanceSquared(loc) <= radiusSquared) return true;
        }
        return false;
    }
}
