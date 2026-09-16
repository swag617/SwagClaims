package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
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
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Denies build/container/interact actions inside a claim when the acting player doesn't hold
 * sufficient trust there. No-ops entirely in the wilderness (no claim at that location) — this
 * plugin never restricts unclaimed land.
 */
public class ClaimProtectionListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    /**
     * Players currently viewing a container opened only because of the {@code chestviewing} flag
     * (i.e. they lack real CONTAINER trust) — every click inside that inventory view is cancelled
     * for them so they can look but not take/place items. Cleared on {@link InventoryCloseEvent}.
     */
    private final Set<UUID> viewOnlyViewers = ConcurrentHashMap.newKeySet();

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
        Location loc;
        if (holder instanceof Container container) {
            loc = container.getLocation();
        } else if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            // A double chest's InventoryHolder is a composite wrapper, not itself a Container/
            // BlockState — this check used to only match single chests, leaving every double
            // chest on the server (by far the more common player-storage case) completely
            // unprotected regardless of trust level (reported: "chests aren't protected").
            InventoryHolder side = doubleChest.getLeftSide() != null ? doubleChest.getLeftSide() : doubleChest.getRightSide();
            loc = side instanceof Container sideContainer ? sideContainer.getLocation() : null;
        } else {
            return; // only gate real block containers
        }

        if (loc == null) return;
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.CONTAINER)) {
            // chestviewing: let them look (don't cancel the open), but every click inside gets
            // cancelled by onContainerClick below — "view, not take from".
            if (plugin.getFlagManager().isSet(claim, ClaimFlags.CHEST_VIEWING)) {
                viewOnlyViewers.add(player.getUniqueId());
                return;
            }
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-container");
        }
    }

    /** See {@link #viewOnlyViewers}. */
    @EventHandler(ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!viewOnlyViewers.contains(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onContainerClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            viewOnlyViewers.remove(player.getUniqueId());
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

        if (isPubliclyAllowed(claim, block.getType())) return; // allowlevers/allowdoors/allowtrapdoors opt-in

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-access");
        }
    }

    /**
     * Gates pressure plates — unlike levers/doors/trapdoors/fence gates/buttons above, plates fire
     * {@code Action.PHYSICAL} (stepping on them), not a right-click, so they need their own handler.
     * Blocked by default for non-trusted players (a new baseline protection introduced alongside
     * this flag), with {@code allowpressureplates} as the opt-in override making them public.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPressurePlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;
        Block block = event.getClickedBlock();
        if (block == null || !Tag.PRESSURE_PLATES.isTagged(block.getType())) return;

        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = block.getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (plugin.getFlagManager().isSet(claim, ClaimFlags.ALLOW_PRESSURE_PLATES)) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
        }
    }

    /** True if the claim's allowlevers/allowdoors/allowtrapdoors flag opts this specific block category into public (no-trust-required) use. */
    private boolean isPubliclyAllowed(Claim claim, Material type) {
        FlagManager flagManager = plugin.getFlagManager();
        if (type == Material.LEVER) return flagManager.isSet(claim, ClaimFlags.ALLOW_LEVERS);
        if (Tag.DOORS.isTagged(type)) return flagManager.isSet(claim, ClaimFlags.ALLOW_DOORS);
        if (Tag.TRAPDOORS.isTagged(type)) return flagManager.isSet(claim, ClaimFlags.ALLOW_TRAPDOORS);
        return false;
    }

    /**
     * Item frames are entities, not blocks — {@link #onInteract} (which only fires on
     * {@code Action.RIGHT_CLICK_BLOCK}) never sees a right-click on one, so rotating the item
     * inside a frame or inserting a new item was completely unprotected regardless of trust
     * level. Gated at ACCESS, matching the other non-container interactables above.
     */
    @EventHandler(ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;

        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = frame.getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-access");
        }
    }

    /** Removing the item from a frame (or breaking the frame itself) via left-click — same gap and same gate as {@link #onFrameInteract}. */
    @EventHandler(ignoreCancelled = true)
    public void onFrameDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (bypasses(player)) return;

        Location loc = frame.getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-access");
        }
    }

    /** Belt-and-suspenders: also cover the break-event path directly, in case something removes the frame without an EntityDamageByEntityEvent (e.g. explosions already handled elsewhere, but a direct hanging break by a player should still be gated). */
    @EventHandler(ignoreCancelled = true)
    public void onFrameBreak(HangingBreakByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        if (!(event.getRemover() instanceof Player player)) return;
        if (bypasses(player)) return;

        Location loc = frame.getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-access");
        }
    }

    /**
     * Taking/placing armor (and items in the main/off hand) on an armor stand — reported: players
     * without any trust could strip armor off display stands in other players'/admins' claims
     * (e.g. FleaVault), since nothing gated this event at all. Same ACCESS gate as item frames.
     */
    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = event.getRightClicked().getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-access");
        }
    }

    /**
     * Breaking ItemsAdder custom furniture — reported: players without trust could break down
     * (and thereby steal) custom furniture in claimed areas, since furniture is entity-based
     * (not a real block) and vanilla BlockBreakEvent protection never covered it. Gated at BUILD,
     * matching normal block-break's trust level.
     */
    @EventHandler(ignoreCancelled = true)
    public void onFurnitureBreak(dev.lone.itemsadder.api.Events.FurnitureBreakEvent event) {
        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = event.getBukkitEntity().getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.BUILD)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-build");
        }
    }

    /** Sitting on / rotating / otherwise interacting with custom furniture — same ACCESS gate as item frames. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnitureInteract(dev.lone.itemsadder.api.Events.FurnitureInteractEvent event) {
        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = event.getBukkitEntity().getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.ACCESS)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-access");
        }
    }

    /**
     * Editing sign text — reported: players without trust could change the text on existing signs
     * in claimed areas. Vanilla doesn't gate re-editing an unwaxed sign by permission at all, so
     * this was wide open. Gated at BUILD, matching block-place/break's trust level.
     */
    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (bypasses(player)) return;

        Location loc = event.getBlock().getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        if (!claimManager.hasPermission(player, loc, TrustLevel.BUILD)) {
            event.setCancelled(true);
            sendDenied(player, claim, "protection.no-build");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getClaimsConfig().isBlockExplosionsInClaims()) return;
        event.blockList().removeIf(this::isProtectedFromExplosion);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getClaimsConfig().isBlockExplosionsInClaims()) return;
        event.blockList().removeIf(this::isProtectedFromExplosion);
    }

    /** True unless the claim here has opted OUT of the default blanket explosion protection via {@code allowexplosions}. */
    private boolean isProtectedFromExplosion(Block block) {
        Claim claim = claimManager.getClaimAt(block.getLocation());
        if (claim == null) return false;
        return !plugin.getFlagManager().isSet(claim, ClaimFlags.ALLOW_EXPLOSIONS);
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
        Claim claim = claimManager.getClaimAt(event.getBlock().getLocation());

        // nofirespread is a per-claim override that blocks spread even when the global
        // protection.fire-spreads config would otherwise allow it here.
        if (claim != null && plugin.getFlagManager().isSet(claim, ClaimFlags.NO_FIRE_SPREAD)) {
            event.setCancelled(true);
            return;
        }

        if (plugin.getClaimsConfig().isFireSpreads()) return;
        if (claim != null) {
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
