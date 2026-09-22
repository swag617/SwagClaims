package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.command.ClaimCommandUtil;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimType;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles the two claim tools: the modification tool (create/resize claims) and the
 * investigation tool (inspect a claim in chat). Both tools act purely on the item in the
 * player's main hand — no special permission node beyond {@code swagclaims.create}/{@code use}
 * is required to hold and swing them, matching GriefPrevention's UX.
 */
public class ClaimToolListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    /** Player -> first corner clicked while in "create a new claim" mode. */
    private final Map<UUID, Location> pendingFirstCorner = new HashMap<>();

    /** Player -> in-progress resize: which existing claim, and the corner opposite the one being moved. */
    private final Map<UUID, ResizeSession> pendingResize = new HashMap<>();

    /** Players who toggled /subdivideclaims on — their next two clicks carve a subdivision. */
    private final Set<UUID> subdivisionMode = new HashSet<>();

    /** Players who toggled /adminclaims on — their next completed claim is an administrator claim. */
    private final Set<UUID> adminClaimMode = new HashSet<>();

    /** Player -> the top-level claim id their pending first corner was placed inside, in subdivision mode. */
    private final Map<UUID, Long> pendingSubdivisionParent = new HashMap<>();

    private record ResizeSession(long claimId, int fixedX, int fixedZ) {
    }

    public ClaimToolListener(SwagClaimsPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    /** Toggles subdivision mode for a player (and turns off admin mode, since the two are mutually exclusive). Returns the new state. */
    public boolean toggleSubdivisionMode(UUID uuid) {
        adminClaimMode.remove(uuid);
        if (subdivisionMode.remove(uuid)) {
            pendingSubdivisionParent.remove(uuid);
            return false;
        }
        subdivisionMode.add(uuid);
        return true;
    }

    /** Toggles admin claim mode for a player (and turns off subdivision mode). Returns the new state. */
    public boolean toggleAdminClaimMode(UUID uuid) {
        subdivisionMode.remove(uuid);
        pendingSubdivisionParent.remove(uuid);
        if (adminClaimMode.remove(uuid)) return false;
        adminClaimMode.add(uuid);
        return true;
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.RIGHT_CLICK_AIR && action != Action.LEFT_CLICK_AIR) {
            return;
        }

        Material modTool = plugin.getClaimsConfig().getModificationTool();
        Material investigateTool = plugin.getClaimsConfig().getInvestigationTool();

        if (item.getType() == modTool) {
            event.setCancelled(true); // prevent vanilla tool behavior (e.g. tilling farmland)
            handleModificationTool(event, player, action);
        } else if (item.getType() == investigateTool) {
            if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
                event.setCancelled(true);
                handleInvestigationTool(player, event.getClickedBlock());
            }
        }
    }

    private void handleModificationTool(PlayerInteractEvent event, Player player, Action action) {
        if (!player.hasPermission("swagclaims.create")) {
            plugin.getMessages().send(player, "general.no-permission");
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) return; // ignore air-clicks for this tool — need a block reference point

        if (action == Action.LEFT_CLICK_BLOCK) {
            handleLeftClick(player, clicked);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            handleRightClick(player, clicked);
        }
    }

    private void handleLeftClick(Player player, Block clicked) {
        UUID uuid = player.getUniqueId();
        Claim existing = claimManager.getClaimAt(clicked.getLocation());

        // Show the boundary of whatever claim is here on every tool click, not just after a
        // successful create/resize — matches GriefPrevention's UX (reported bug: no borders
        // ever appeared just from holding/clicking the tool inside an existing claim).
        if (existing != null) {
            plugin.getClaimVisualizer().show(player, existing);
        }

        if (existing != null && existing.isCorner(clicked.getX(), clicked.getZ())
                && claimManager.hasPermission(player, clicked.getLocation(), TrustLevel.MANAGE)) {
            if (plugin.getFlagManager().isSet(existing, com.swag.swagclaims.model.ClaimFlags.NO_RESIZING)
                    && !player.hasPermission("swagclaims.admin.claims")) {
                plugin.getMessages().send(player, "flag.no-resizing");
                return;
            }
            int fixedX = (clicked.getX() == existing.getMinX()) ? existing.getMaxX() : existing.getMinX();
            int fixedZ = (clicked.getZ() == existing.getMinZ()) ? existing.getMaxZ() : existing.getMinZ();
            pendingResize.put(uuid, new ResizeSession(existing.getId(), fixedX, fixedZ));
            pendingFirstCorner.remove(uuid);
            pendingSubdivisionParent.remove(uuid);
            plugin.getMessages().send(player, "tool.resize-corner-selected");
            return;
        }

        if (subdivisionMode.contains(uuid)) {
            if (existing == null || !existing.isTopLevel() || !ClaimCommandUtil.canManage(plugin, player, existing)) {
                plugin.getMessages().send(player, "subdivide.must-be-in-claim");
                return;
            }
            pendingSubdivisionParent.put(uuid, existing.getId());
            pendingResize.remove(uuid);
            pendingFirstCorner.put(uuid, clicked.getLocation());

            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("x", String.valueOf(clicked.getX()));
            ph.put("y", String.valueOf(clicked.getY()));
            ph.put("z", String.valueOf(clicked.getZ()));
            plugin.getMessages().send(player, "tool.first-corner-set", ph);
            return;
        }

        pendingResize.remove(uuid);
        pendingSubdivisionParent.remove(uuid);
        pendingFirstCorner.put(uuid, clicked.getLocation());

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("x", String.valueOf(clicked.getX()));
        placeholders.put("y", String.valueOf(clicked.getY()));
        placeholders.put("z", String.valueOf(clicked.getZ()));
        plugin.getMessages().send(player, "tool.first-corner-set", placeholders);
    }

    private void handleRightClick(Player player, Block clicked) {
        UUID uuid = player.getUniqueId();

        ResizeSession resize = pendingResize.get(uuid);
        if (resize != null) {
            pendingResize.remove(uuid);
            Claim claim = claimManager.getClaim(resize.claimId());
            if (claim == null) {
                plugin.getMessages().send(player, "tool.no-first-corner");
                return;
            }
            ClaimManager.ClaimResult result = claimManager.resizeClaim(
                    claim, resize.fixedX(), claim.getMinY(), resize.fixedZ(),
                    clicked.getX(), claim.getMaxY(), clicked.getZ());
            handleResult(player, result, "tool.claim-resized");
            return;
        }

        Location first = pendingFirstCorner.remove(uuid);
        if (first == null) {
            plugin.getMessages().send(player, "tool.no-first-corner");
            return;
        }

        if (!first.getWorld().equals(clicked.getWorld())) {
            plugin.getMessages().send(player, "tool.no-first-corner");
            return;
        }

        Long subdivisionParentId = pendingSubdivisionParent.remove(uuid);
        if (subdivisionParentId != null) {
            Claim parent = claimManager.getClaim(subdivisionParentId);
            if (parent == null) {
                plugin.getMessages().send(player, "tool.no-first-corner");
                return;
            }
            Claim atSecondCorner = claimManager.getClaimAt(clicked.getLocation());
            if (atSecondCorner == null || topLevelIdOf(atSecondCorner) != parent.getId()) {
                plugin.getMessages().send(player, "subdivide.must-be-in-claim");
                return;
            }
            ClaimManager.ClaimResult result = claimManager.createSubdivision(parent,
                    first.getBlockX(), parent.getMinY(), first.getBlockZ(),
                    clicked.getX(), parent.getMaxY(), clicked.getZ());
            handleResult(player, result, "subdivide.created");
            return;
        }

        boolean adminMode = adminClaimMode.contains(uuid) && player.hasPermission("swagclaims.admin.claims");
        ClaimType type = adminMode ? ClaimType.ADMIN : ClaimType.BASIC;
        UUID owner = adminMode ? null : uuid;

        ClaimManager.ClaimResult result = claimManager.createClaim(
                owner, type, clicked.getWorld().getName(),
                first.getBlockX(), first.getWorld().getMinHeight(), first.getBlockZ(),
                clicked.getX(), first.getWorld().getMaxHeight() - 1, clicked.getZ());
        handleResult(player, result, "tool.claim-created");
    }

    private long topLevelIdOf(Claim claim) {
        if (claim.isTopLevel()) return claim.getId();
        Long parentId = claim.getParentId();
        return parentId != null ? parentId : claim.getId();
    }

    private void handleResult(Player player, ClaimManager.ClaimResult result, String successKey) {
        switch (result.status) {
            case SUCCESS -> {
                plugin.getMessages().send(player, successKey);
                plugin.getClaimVisualizer().show(player, result.claim);
            }
            case TOO_SMALL_WIDTH -> {
                Map<String, String> ph = new HashMap<>();
                ph.put("min", String.valueOf(plugin.getClaimsConfig().getMinimumWidth()));
                plugin.getMessages().send(player, "tool.claim-too-small-width", ph);
            }
            case TOO_SMALL_AREA -> {
                Map<String, String> ph = new HashMap<>();
                ph.put("min", String.valueOf(plugin.getClaimsConfig().getMinimumArea()));
                plugin.getMessages().send(player, "tool.claim-too-small-area", ph);
            }
            case OVERLAP -> {
                Map<String, String> ph = new HashMap<>();
                String ownerName = "someone";
                if (result.conflictingClaim.isAdminClaim()) {
                    ownerName = "an administrator";
                } else if (result.conflictingClaim.getOwnerUuid() != null) {
                    var offline = plugin.getServer().getOfflinePlayer(result.conflictingClaim.getOwnerUuid());
                    if (offline.getName() != null) ownerName = offline.getName();
                }
                ph.put("owner", ownerName);
                plugin.getMessages().send(player, "tool.claim-overlap", ph);
            }
            case NOT_ENOUGH_BLOCKS -> {
                Map<String, String> ph = new HashMap<>();
                ph.put("needed", String.valueOf(result.blocksNeeded));
                plugin.getMessages().send(player, "tool.not-enough-blocks", ph);
            }
            case NO_PERMISSION -> plugin.getMessages().send(player, "tool.no-permission-resize");
            case WORLD_DISABLED -> plugin.getMessages().send(player, "tool.claims-disabled-world");
            case OUT_OF_PARENT_BOUNDS -> plugin.getMessages().send(player, "subdivide.out-of-bounds");
        }
    }

    /**
     * Called on a fixed interval (see {@code SwagClaimsPlugin#startBorderPreviewTask}) for every
     * online player holding either claim tool: renders every nearby claim's boundary to them via
     * per-player particles (see {@link com.swag.swagclaims.util.ClaimVisualizer#showBorderParticles}).
     * A linear scan over every loaded claim per tool-holding player — the same "fine for the
     * claim counts a single server accumulates" reasoning {@link ClaimManager#getClaimAt} already
     * relies on, bounded further here since it only runs for players actually holding a tool and
     * only considers claims within {@code tool-border-preview.radius} blocks.
     */
    public void renderBorderPreviews() {
        Material modTool = plugin.getClaimsConfig().getModificationTool();
        Material investigateTool = plugin.getClaimsConfig().getInvestigationTool();
        double radius = plugin.getClaimsConfig().getBorderPreviewRadius();
        double radiusSquared = radius * radius;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Material held = player.getInventory().getItemInMainHand().getType();
            if (held != modTool && held != investigateTool) continue;

            Location loc = player.getLocation();
            String world = player.getWorld().getName();
            for (Claim claim : claimManager.getAllClaims()) {
                if (!claim.getWorld().equalsIgnoreCase(world)) continue;
                if (!withinRadius(claim, loc, radiusSquared)) continue;
                plugin.getClaimVisualizer().showBorderParticles(player, claim);
            }
        }
    }

    /** True if the nearest point of the claim's horizontal bounds is within {@code radiusSquared} of {@code loc}. */
    private boolean withinRadius(Claim claim, Location loc, double radiusSquared) {
        double x = loc.getX();
        double z = loc.getZ();
        double clampedX = Math.max(claim.getMinX(), Math.min(x, claim.getMaxX() + 1.0));
        double clampedZ = Math.max(claim.getMinZ(), Math.min(z, claim.getMaxZ() + 1.0));
        double dx = x - clampedX;
        double dz = z - clampedZ;
        return (dx * dx + dz * dz) <= radiusSquared;
    }

    private void handleInvestigationTool(Player player, Block clicked) {
        if (!player.hasPermission("swagclaims.use")) {
            plugin.getMessages().send(player, "general.no-permission");
            return;
        }

        Location loc = clicked != null ? clicked.getLocation() : player.getLocation();
        Claim claim = claimManager.getClaimAt(loc);

        if (claim == null) {
            plugin.getMessages().send(player, "tool.investigate-wilderness");
            return;
        }

        plugin.getClaimVisualizer().show(player, claim);
        com.swag.swagclaims.command.ClaimCommandUtil.sendClaimInfo(plugin, player, claim);
    }
}
