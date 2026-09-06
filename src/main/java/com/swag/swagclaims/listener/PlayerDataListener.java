package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.model.ClaimType;
import com.swag.swagclaims.model.PlayerClaimData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Loads a player's claim-block data from the database on join (giving brand-new players the
 * configured {@code initial-blocks} in the meantime, per house style's "Load on player join,
 * save on player quit and periodically") and saves it on quit.
 */
public class PlayerDataListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    public PlayerDataListener(SwagClaimsPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Seed a default so the player isn't left without claim blocks while the async load runs.
        PlayerClaimData placeholder = claimManager.getPlayerData(uuid);
        placeholder.setLastLogin(System.currentTimeMillis());

        plugin.getDatabaseManager().loadPlayerData(uuid).thenAccept(loaded -> {
            if (loaded == null) {
                // Never persisted before — save the freshly-seeded default now so it exists next time.
                PlayerClaimData fresh = claimManager.getPlayerData(uuid);
                fresh.setLastLogin(System.currentTimeMillis());
                plugin.getDatabaseManager().savePlayerData(fresh);
                return;
            }
            loaded.setLastLogin(System.currentTimeMillis());
            claimManager.cachePlayerData(loaded);
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to load claim data for " + player.getName() + ": " + ex.getMessage());
            return null;
        });

        // Runs synchronously against the already-loaded claim cache (no need to wait on the
        // async player-data load above) — claims are all loaded at startup, not per-player.
        grantStarterClaimIfNeeded(player);
    }

    /**
     * Grants a small BASIC claim centered on the player's spawn location on their very first join,
     * sized by config's {@code automatic-claims-radius} (0 = disabled). Silently skipped — never
     * an error — if the world's claim mode is DISABLED, the radius is 0, or the starter claim would
     * overlap an existing claim; this is a courtesy gift, not a guarantee.
     */
    private void grantStarterClaimIfNeeded(Player player) {
        int radius = plugin.getClaimsConfig().getAutomaticClaimsRadius();
        if (radius <= 0) return;
        if (player.hasPlayedBefore()) return;

        UUID uuid = player.getUniqueId();
        if (!claimManager.getClaimsByOwner(uuid).isEmpty()) return;

        Location loc = player.getLocation();
        if (loc.getWorld() == null) return;

        int centerX = loc.getBlockX();
        int centerZ = loc.getBlockZ();
        int minY = loc.getWorld().getMinHeight();
        int maxY = loc.getWorld().getMaxHeight() - 1;

        ClaimManager.ClaimResult result = claimManager.createClaim(
                uuid, ClaimType.BASIC, loc.getWorld().getName(),
                centerX - radius, minY, centerZ - radius,
                centerX + radius, maxY, centerZ + radius,
                true); // bypass minimum-size checks — this is a courtesy grant, not a manual claim

        if (result.status == ClaimManager.ClaimResult.Status.SUCCESS) {
            plugin.getMessages().send(player, "starter.granted");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerClaimData data = claimManager.getPlayerData(uuid);
        plugin.getDatabaseManager().savePlayerData(data);

        // Only evict from cache if the player is truly gone (not just switching servers/hopping
        // in a network with instant rejoin) — a short delay avoids losing in-memory state to a
        // near-instant rejoin racing the save.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(uuid) == null) {
                claimManager.uncachePlayerData(uuid);
            }
        }, 20L * 30);
    }
}
