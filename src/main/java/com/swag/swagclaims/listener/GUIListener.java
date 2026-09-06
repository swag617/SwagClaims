package com.swag.swagclaims.listener;

import com.swag.swagclaims.gui.OpenMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single dispatch point for every SwagClaims GUI. Tracks open menus in a {@code Map<UUID, OpenMenu>}
 * keyed by the viewing player rather than matching on inventory titles (see {@link OpenMenu}'s
 * javadoc for why) — every concrete GUI class calls {@link #register} right after it opens an
 * inventory for a player, and this listener cleans the entry up automatically when that inventory
 * closes.
 */
public class GUIListener implements Listener {

    private final Map<UUID, OpenMenu> openMenus = new ConcurrentHashMap<>();

    /** Registers (or replaces) the menu currently open for a player. Call this after every {@code openInventory}. */
    public void register(UUID uuid, OpenMenu menu) {
        openMenus.put(uuid, menu);
    }

    /** Removes whatever menu is registered for a player, if any. */
    public void unregister(UUID uuid) {
        openMenus.remove(uuid);
    }

    public OpenMenu get(UUID uuid) {
        return openMenus.get(uuid);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        OpenMenu menu = openMenus.get(player.getUniqueId());
        if (menu == null) return;

        // Every SwagClaims GUI is click-driven and fully re-rendered on state change — nothing in
        // these menus should ever move a real item, so cancel unconditionally before delegating.
        event.setCancelled(true);
        menu.onClick(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        OpenMenu menu = openMenus.remove(player.getUniqueId());
        if (menu != null) {
            menu.onClose(event);
        }
    }
}
