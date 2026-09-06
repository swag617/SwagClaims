package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Denies build/container/interact actions inside a claim when the acting player doesn't hold
 * sufficient trust there. No-ops entirely in the wilderness (no claim at that location) — this
 * plugin never restricts unclaimed land.
 */
public class ClaimProtectionListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    public ClaimProtectionListener(SwagClaimsPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = event.getBlock().getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return; // wilderness — not our concern

        if (!claimManager.hasPermission(player, loc, TrustLevel.BUILD)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-build");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = event.getBlockPlaced().getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.BUILD)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-build");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (bypasses(player)) return;

        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Container container)) return; // only gate real block containers

        Location loc = container.getLocation();
        if (loc == null) return;
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.CONTAINER)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-container");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || !isGatedInteractable(block.getType())) return;
        if (block.getState() instanceof Container) return; // containers are gated by onInventoryOpen instead

        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = block.getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-access");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getClaimsConfig().isBlockExplosionsInClaims()) return;
        event.blockList().removeIf(block -> claimManager.getClaimAt(block.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getClaimsConfig().isBlockExplosionsInClaims()) return;
        event.blockList().removeIf(block -> claimManager.getClaimAt(block.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.getClaimsConfig().isPistonClaimsOnly()) return;
        Claim pistonClaim = claimManager.getClaimAt(event.getBlock().getLocation());
        for (Block moved : event.getBlocks()) {
            Claim movedClaim = claimManager.getClaimAt(moved.getLocation());
            Claim destinationClaim = claimManager.getClaimAt(moved.getRelative(event.getDirection()).getLocation());
            if (!sameTopLevelClaim(pistonClaim, movedClaim) || !sameTopLevelClaim(pistonClaim, destinationClaim)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.getClaimsConfig().isPistonClaimsOnly()) return;
        Claim pistonClaim = claimManager.getClaimAt(event.getBlock().getLocation());
        for (Block moved : event.getBlocks()) {
            Claim movedClaim = claimManager.getClaimAt(moved.getLocation());
            if (!sameTopLevelClaim(pistonClaim, movedClaim)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** True if both locations resolve to the same claim (or both to wilderness), compared by top-level id so a piston inside a subdivision can freely push within its parent's bounds. */
    private boolean sameTopLevelClaim(Claim a, Claim b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        long aTop = a.isTopLevel() ? a.getId() : (a.getParentId() != null ? a.getParentId() : a.getId());
        long bTop = b.isTopLevel() ? b.getId() : (b.getParentId() != null ? b.getParentId() : b.getId());
        return aTop == bTop;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.SPREAD) return;
        if (plugin.getClaimsConfig().isFireSpreads()) return;
        if (claimManager.getClaimAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (plugin.getClaimsConfig().isFireDestroys()) return;
        if (claimManager.getClaimAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        boolean isEnderman = event.getEntity() instanceof Enderman && plugin.getClaimsConfig().isPreventEndermanGriefing();
        boolean isSilverfish = event.getEntity() instanceof Silverfish && plugin.getClaimsConfig().isPreventSilverfishGriefing();
        if (!isEnderman && !isSilverfish) return;
        if (claimManager.getClaimAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks non-player creatures (rabbits, etc.) from trampling crops inside a claim. Player-caused
     * farmland trampling is out of scope for this phase — it fires as a separate
     * {@code PlayerInteractEvent} (Action.PHYSICAL) that this plugin doesn't currently gate.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCropTrample(EntityInteractEvent event) {
        if (!plugin.getClaimsConfig().isPreventCropTrampling()) return;
        if (event.getEntity() instanceof Player) return;
        if (event.getBlock().getType() != Material.FARMLAND) return;
        if (claimManager.getClaimAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    private boolean bypasses(Player player) {
        return player.hasPermission("swagclaims.admin.ignoreclaims");
    }

    private boolean isGatedInteractable(Material type) {
        return Tag.DOORS.isTagged(type)
                || Tag.TRAPDOORS.isTagged(type)
                || Tag.FENCE_GATES.isTagged(type)
                || Tag.BUTTONS.isTagged(type)
                || type == Material.LEVER;
    }

    private void sendDenied(Player player, Claim claim, String messageKey) {
        Map<String, String> placeholders = new HashMap<>();
        String owner;
        if (claim.isAdminClaim() || claim.getOwnerUuid() == null) {
            owner = plugin.getMessages().get("protection.admin-claim-owner");
        } else {
            var offline = plugin.getServer().getOfflinePlayer(claim.getOwnerUuid());
            owner = offline.getName() != null ? offline.getName() : "someone";
        }
        placeholders.put("owner", owner);
        plugin.getMessages().send(player, messageKey, placeholders);
    }
}
