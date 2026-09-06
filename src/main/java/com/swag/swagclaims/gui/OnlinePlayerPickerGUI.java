package com.swag.swagclaims.gui;

import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
import java.util.function.Consumer;

/**
 * Reusable "pick a currently-online player" screen, shared by {@link TrustManagerGUI}'s
 * "add player" flow and {@link ClaimBlockSendGUI}'s "specific player" target picker. Bukkit
 * inventories can't take free-text input, and listing every offline player who's ever joined
 * isn't practical in a menu, so this is deliberately limited to who's online right now — trusting
 * or sending blocks to an offline player still has to go through the existing text commands
 * ({@code /trust <name>}), which is noted in both call sites.
 */
public class OnlinePlayerPickerGUI implements OpenMenu {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_CANCEL = 49;
    private static final int SLOT_NEXT = 53;

    private final SwagClaimsPlugin plugin;
    private final Player viewer;
    private final Consumer<Player> onSelect;
    private final Runnable onCancel;

    private final Map<Integer, UUID> slotPlayers = new HashMap<>();
    private List<Player> candidates = new ArrayList<>();
    private int page = 0;

    /**
     * @param onSelect invoked with the chosen online player; the picker does not close/re-open
     *                 anything itself afterward — that's the caller's job (usually opening the
     *                 next screen or performing an action then re-registering itself).
     * @param onCancel invoked when the viewer clicks the cancel button (e.g. to reopen the
     *                 screen that opened this picker).
     */
    public OnlinePlayerPickerGUI(SwagClaimsPlugin plugin, Player viewer, Consumer<Player> onSelect, Runnable onCancel) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.onSelect = onSelect;
        this.onCancel = onCancel;
    }

    public void open() {
        openPage(0);
    }

    private void openPage(int newPage) {
        candidates = Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .<Player>map(p -> p)
                .toList();

        int maxPage = Math.max(0, (candidates.size() - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(newPage, maxPage));

        Inventory inv = Bukkit.createInventory(null, 54,
                color("&b&lSelect a Player &7(Page " + (page + 1) + "/" + (maxPage + 1) + ")"));

        slotPlayers.clear();
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, candidates.size());
        for (int i = start; i < end; i++) {
            Player target = candidates.get(i);
            int slot = i - start;
            inv.setItem(slot, createHead(target));
            slotPlayers.put(slot, target.getUniqueId());
        }

        if (candidates.isEmpty()) {
            inv.setItem(22, createNamedItem(Material.BARRIER, "&cNo other players are online", null));
        }

        ItemStack filler = createNamedItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }
        if (page > 0) inv.setItem(SLOT_PREV, createNamedItem(Material.ARROW, "&a← Previous Page", null));
        if (page < maxPage) inv.setItem(SLOT_NEXT, createNamedItem(Material.ARROW, "&aNext Page →", null));
        inv.setItem(SLOT_CANCEL, createNamedItem(Material.BARRIER, "&cCancel", null));

        viewer.openInventory(inv);
        plugin.getGuiListener().register(viewer.getUniqueId(), this);
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
        if (slot == SLOT_CANCEL) {
            onCancel.run();
            return;
        }
        if (slot >= 45) return;

        UUID targetUuid = slotPlayers.get(slot);
        if (targetUuid == null) return;
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            // Left between render and click — just refresh the list instead of erroring out.
            openPage(page);
            return;
        }
        onSelect.accept(target);
    }

    private ItemStack createHead(Player target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.setDisplayName(color("&f" + target.getName()));
            item.setItemMeta(meta);
        }
        return item;
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
