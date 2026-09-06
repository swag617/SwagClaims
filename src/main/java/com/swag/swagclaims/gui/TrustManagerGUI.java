package com.swag.swagclaims.gui;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.command.ClaimCommandUtil;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Interactive trust manager for a single claim, replacing typed {@code /trust}/{@code /untrust}
 * for the common case. Every click persists immediately through
 * {@link com.swag.swagclaims.manager.ClaimManager#grantTrust}/{@code revokeTrust} — same as every
 * other mutation in this plugin — so there's no separate "save" step.
 *
 * <p>Left-click on a trusted target cycles ACCESS → CONTAINER → BUILD → MANAGE → (untrusted).
 * Right-click removes them outright. The "Add Player" button opens {@link OnlinePlayerPickerGUI}
 * to grant a currently-online player ACCESS trust to start from — Bukkit inventories can't take
 * free-text input, so trusting an <em>offline</em> player (who can't be listed in a menu) still
 * has to go through {@code /trust <name>}; this GUI only covers players who are online right now.
 * Existing {@code public}/{@code g:<group>} trust entries are shown and manageable the same way,
 * but this phase doesn't add a way to grant NEW group trust from the GUI (no command exposes that
 * even from chat today — out of scope here).
 */
public class TrustManagerGUI implements OpenMenu {

    private static final int SLOT_ADD_PLAYER = 0;
    private static final int SLOT_INFO = 4;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 44; // inclusive
    private static final int PAGE_SIZE = CONTENT_END - CONTENT_START + 1;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_CLOSE = 48;
    private static final int SLOT_NEXT = 53;

    private final SwagClaimsPlugin plugin;
    private final Player viewer;
    private final Claim claim;

    private final Map<Integer, String> slotTargets = new HashMap<>();
    private List<String> targets = new ArrayList<>();
    private int page = 0;

    public TrustManagerGUI(SwagClaimsPlugin plugin, Player viewer, Claim claim) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.claim = claim;
    }

    public void open() {
        openPage(0);
    }

    private void openPage(int newPage) {
        targets = new ArrayList<>(claim.getTrustMap().keySet());
        targets.sort(Comparator.comparing(t -> ClaimCommandUtil.describeTarget(plugin, t), String.CASE_INSENSITIVE_ORDER));

        int maxPage = Math.max(0, (targets.size() - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(newPage, maxPage));

        String claimName = claim.getName() != null && !claim.getName().isEmpty() ? claim.getName() : "Claim #" + claim.getId();
        Inventory inv = Bukkit.createInventory(null, 54, color("&2&lTrust: " + claimName + " &7(Page " + (page + 1) + "/" + (maxPage + 1) + ")"));

        inv.setItem(SLOT_ADD_PLAYER, createNamedItem(Material.EMERALD, "&a&lAdd Player",
                List.of(color("&7Grants ACCESS trust to an"), color("&7online player you pick."))));
        inv.setItem(SLOT_INFO, createNamedItem(Material.BOOK, "&e" + claimName,
                List.of(color("&7Trusted targets: &f" + targets.size()))));

        slotTargets.clear();
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, targets.size());
        for (int i = start; i < end; i++) {
            String target = targets.get(i);
            int slot = CONTENT_START + (i - start);
            inv.setItem(slot, createTargetItem(target));
            slotTargets.put(slot, target);
        }

        ItemStack filler = createNamedItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }
        if (page > 0) inv.setItem(SLOT_PREV, createNamedItem(Material.ARROW, "&a← Previous Page", null));
        if (page < maxPage) inv.setItem(SLOT_NEXT, createNamedItem(Material.ARROW, "&aNext Page →", null));
        inv.setItem(SLOT_CLOSE, createNamedItem(Material.BARRIER, "&cClose", null));

        viewer.openInventory(inv);
        plugin.getGuiListener().register(viewer.getUniqueId(), this);
    }

    private ItemStack createTargetItem(String target) {
        TrustLevel level = claim.getTrust(target);
        String displayName = ClaimCommandUtil.describeTarget(plugin, target);

        ItemStack item;
        if (target.equals(Claim.PUBLIC_TARGET)) {
            item = createNamedItem(Material.BEACON, "&bEveryone (public)", null);
        } else if (target.startsWith(Claim.GROUP_TARGET_PREFIX)) {
            item = createNamedItem(Material.WHITE_BANNER, "&dGroup: " + target.substring(Claim.GROUP_TARGET_PREFIX.length()), null);
        } else {
            item = new ItemStack(Material.PLAYER_HEAD);
            try {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(UUID.fromString(target));
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                if (meta != null) {
                    meta.setOwningPlayer(offline);
                    meta.setDisplayName(color("&f" + displayName));
                    item.setItemMeta(meta);
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed target string — fall through with a plain head, name set below.
            }
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (!meta.hasDisplayName()) meta.setDisplayName(color("&f" + displayName));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Current trust: &f" + (level != null ? level.name() : "NONE")));
            lore.add("");
            lore.add(color("&aLeft-click &7to cycle trust level"));
            lore.add(color("&cRight-click &7to remove"));
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
        if (slot == SLOT_ADD_PLAYER) {
            openAddPlayerPicker();
            return;
        }
        if (slot < CONTENT_START || slot > CONTENT_END) return;

        String target = slotTargets.get(slot);
        if (target == null) return;

        if (event.getClick().isRightClick()) {
            plugin.getClaimManager().revokeTrust(claim, target);
        } else {
            cycleTrust(target);
        }
        openPage(page);
    }

    private void cycleTrust(String target) {
        TrustLevel current = claim.getTrust(target);
        TrustLevel next = current == null ? TrustLevel.ACCESS
                : switch (current) {
            case ACCESS -> TrustLevel.CONTAINER;
            case CONTAINER -> TrustLevel.BUILD;
            case BUILD -> TrustLevel.MANAGE;
            case MANAGE -> null;
        };

        if (next == null) {
            plugin.getClaimManager().revokeTrust(claim, target);
        } else {
            plugin.getClaimManager().grantTrust(claim, target, next);
        }
    }

    private void openAddPlayerPicker() {
        new OnlinePlayerPickerGUI(plugin, viewer, selected -> {
            if (selected.getUniqueId().equals(viewer.getUniqueId())) {
                plugin.getMessages().send(viewer, "trust.cannot-trust-self");
                openPage(page);
                return;
            }
            plugin.getClaimManager().grantTrust(claim, selected.getUniqueId().toString(), TrustLevel.ACCESS);
            openPage(0);
        }, () -> openPage(page)).open();
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
