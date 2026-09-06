package com.swag.swagclaims.gui;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.command.ClaimCommandUtil;
import com.swag.swagclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Paginated claim list + teleport GUI, replacing the "MyClaimsGUI" addon on the live server.
 * Three entry points share this one class via {@link Mode}: viewing your own claims, viewing
 * another player's claims (admin-only, mirrors {@code /claimslist <player> --gui}), and an
 * admin-claims-only view. Only top-level claims are listed (subdivisions don't carry their own
 * claim-block cost and aren't teleport destinations in their own right) — same filter
 * {@code ClaimsListCommand} already applies to its chat output.
 *
 * <p>Left-click teleports to the claim (after a destination-safety scan — see
 * {@link #findSafeTeleportLocation(Claim)}); right-click prints the same chat info block
 * {@code /claiminfo} does (plus its boundary visualization) rather than duplicating that logic
 * in a second GUI, per the phase plan's explicit "or just reuse /claiminfo" allowance.
 */
public class ClaimListGUI implements OpenMenu {

    public enum Mode { OWN, OTHER, ADMIN }

    private static final int PAGE_SIZE = 45; // slots 0-44; 45-53 is the nav row
    private static final int SLOT_PREV = 45;
    private static final int SLOT_CLOSE = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;

    private final SwagClaimsPlugin plugin;
    private final Player viewer;
    private final Mode mode;
    private final UUID targetUuid;
    private final String targetName;

    private List<Claim> claims = new ArrayList<>();
    private int page = 0;

    public ClaimListGUI(SwagClaimsPlugin plugin, Player viewer, Mode mode, UUID targetUuid, String targetName) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.mode = mode;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
    }

    public static ClaimListGUI forOwn(SwagClaimsPlugin plugin, Player viewer) {
        return new ClaimListGUI(plugin, viewer, Mode.OWN, viewer.getUniqueId(), viewer.getName());
    }

    public static ClaimListGUI forOther(SwagClaimsPlugin plugin, Player viewer, UUID targetUuid, String targetName) {
        return new ClaimListGUI(plugin, viewer, Mode.OTHER, targetUuid, targetName);
    }

    public static ClaimListGUI forAdmin(SwagClaimsPlugin plugin, Player viewer) {
        return new ClaimListGUI(plugin, viewer, Mode.ADMIN, null, "Administrator");
    }

    public void open() {
        openPage(0);
    }

    private void openPage(int newPage) {
        refreshClaims();
        int maxPage = Math.max(0, (claims.size() - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(newPage, maxPage));

        String header = mode == Mode.OWN ? "Your Claims"
                : mode == Mode.ADMIN ? "Administrator Claims"
                : targetName + "'s Claims";
        Inventory inv = Bukkit.createInventory(null, 54, color("&2&l" + header + " &7(Page " + (page + 1) + "/" + (maxPage + 1) + ")"));

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, claims.size());
        for (int i = start; i < end; i++) {
            inv.setItem(i - start, createClaimItem(claims.get(i)));
        }

        fillNavRow(inv);

        viewer.openInventory(inv);
        plugin.getGuiListener().register(viewer.getUniqueId(), this);
    }

    private void refreshClaims() {
        List<Claim> result;
        if (mode == Mode.ADMIN) {
            result = plugin.getClaimManager().getAllClaims().stream()
                    .filter(Claim::isTopLevel)
                    .filter(Claim::isAdminClaim)
                    .collect(java.util.stream.Collectors.toList());
        } else {
            result = plugin.getClaimManager().getClaimsByOwner(targetUuid).stream()
                    .filter(Claim::isTopLevel)
                    .collect(java.util.stream.Collectors.toList());
        }
        result.sort(Comparator.comparingLong(Claim::getCreatedAt));
        this.claims = result;
    }

    private void fillNavRow(Inventory inv) {
        ItemStack filler = createPane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }

        if (page > 0) {
            inv.setItem(SLOT_PREV, createNamedItem(Material.ARROW, "&a← Previous Page", null));
        }
        int maxPage = Math.max(0, (claims.size() - 1) / PAGE_SIZE);
        if (page < maxPage) {
            inv.setItem(SLOT_NEXT, createNamedItem(Material.ARROW, "&aNext Page →", null));
        }

        inv.setItem(SLOT_CLOSE, createNamedItem(Material.BARRIER, "&cClose", null));

        long area = claims.stream().mapToLong(Claim::getArea).sum();
        List<String> infoLore = List.of(
                color("&7Claims: &f" + claims.size()),
                color("&7Total area: &f" + area + " blocks")
        );
        inv.setItem(SLOT_INFO, createNamedItem(Material.MAP, "&e" + (mode == Mode.OWN ? "Your Claims" : mode == Mode.ADMIN ? "Administrator Claims" : targetName + "'s Claims"), infoLore));
    }

    private ItemStack createClaimItem(Claim claim) {
        Material material = claim.isAdminClaim() ? Material.RED_WOOL : Material.LIME_WOOL;
        String name = claim.getName() != null && !claim.getName().isEmpty() ? claim.getName() : "Claim #" + claim.getId();

        SimpleDateFormat format = new SimpleDateFormat(plugin.getClaimsConfig().getGuiDateFormat());

        List<String> lore = new ArrayList<>();
        lore.add(color("&7World: &f" + claim.getWorld()));
        lore.add(color("&7Coords: &f[" + claim.getMinX() + "," + claim.getMinZ() + "] to [" + claim.getMaxX() + "," + claim.getMaxZ() + "]"));
        lore.add(color("&7Size: &f" + claim.getWidthX() + "x" + claim.getWidthZ() + " &7(" + claim.getArea() + " blocks)"));
        lore.add(color("&7Type: &f" + claim.getClaimType().name()));
        lore.add(color("&7Created: &f" + format.format(new Date(claim.getCreatedAt()))));
        lore.add(color("&7Last Active: &f" + format.format(new Date(claim.getLastActiveAt()))));
        lore.add("");
        lore.add(color("&aLeft-click &7to teleport"));
        lore.add(color("&eRight-click &7for info"));

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&f" + name));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        if (slot == SLOT_PREV) {
            openPage(page - 1);
            return;
        }
        if (slot == SLOT_NEXT) {
            openPage(page + 1);
            return;
        }
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }
        if (slot >= 45) return; // rest of the nav row isn't interactive

        int index = page * PAGE_SIZE + slot;
        if (index < 0 || index >= claims.size()) return;
        Claim claim = claims.get(index);

        switch (event.getClick()) {
            case RIGHT, SHIFT_RIGHT -> ClaimCommandUtil.sendClaimInfo(plugin, viewer, claim);
            default -> teleportTo(claim);
        }
    }

    private void teleportTo(Claim claim) {
        Location destination = findSafeTeleportLocation(claim);
        if (destination == null) {
            plugin.getMessages().send(viewer, "gui.claimlist.unsafe-teleport");
            return;
        }
        viewer.closeInventory();
        viewer.teleport(destination);
        plugin.getMessages().send(viewer, "gui.claimlist.teleported");
    }

    /**
     * Scans downward from just above the claim's center-column highest block for the first
     * standing spot with two passable blocks (feet/head, non-lava) over solid, non-hazardous
     * ground — the "don't teleport into a block or off a cliff into lava" safety check called
     * out in the phase plan. Returns null if nothing safe is found in range, in which case the
     * teleport is aborted rather than dropping the player somewhere dangerous.
     */
    private Location findSafeTeleportLocation(Claim claim) {
        World world = Bukkit.getWorld(claim.getWorld());
        if (world == null) return null;

        int x = (claim.getMinX() + claim.getMaxX()) / 2;
        int z = (claim.getMinZ() + claim.getMaxZ()) / 2;

        int top = Math.min(world.getHighestBlockYAt(x, z) + 3, world.getMaxHeight() - 2);
        int bottom = world.getMinHeight() + 1;
        for (int y = top; y >= bottom; y--) {
            if (isSafeStandingLocation(world, x, y, z)) {
                return new Location(world, x + 0.5, y, z + 0.5, viewer.getLocation().getYaw(), viewer.getLocation().getPitch());
            }
        }
        return null;
    }

    private boolean isSafeStandingLocation(World world, int x, int y, int z) {
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        if (feet.isSolid() || head.isSolid()) return false;
        if (feet == Material.LAVA || head == Material.LAVA) return false;
        return ground.isSolid() && ground != Material.LAVA && ground != Material.MAGMA_BLOCK;
    }

    private ItemStack createPane(Material material, String name) {
        return createNamedItem(material, name, null);
    }

    private ItemStack createNamedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
