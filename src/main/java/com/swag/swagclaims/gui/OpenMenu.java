package com.swag.swagclaims.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * A currently-open custom inventory GUI, tracked by {@link com.swag.swagclaims.listener.GUIListener}
 * in a {@code Map<UUID, OpenMenu>} keyed by the viewing player's UUID (not by inventory title —
 * title-string matching is fragile when GUIs get renamed/paginated). Every click while a menu is
 * registered for that player is cancelled and delegated to {@link #onClick}; every concrete GUI is
 * responsible for re-registering itself (via {@code GUIListener#register}) immediately after every
 * {@code player.openInventory(...)} call it makes — including its own internal page/screen
 * navigation — since opening a new inventory view always fires an {@link InventoryCloseEvent} for
 * whatever was previously open first, which would otherwise evict the registration.
 */
public interface OpenMenu {

    /** Called for every click while this menu is the one registered for the clicking player. */
    void onClick(InventoryClickEvent event);

    /** Called once when this menu's inventory is closed and no replacement has re-registered yet. */
    default void onClose(InventoryCloseEvent event) {
    }
}
