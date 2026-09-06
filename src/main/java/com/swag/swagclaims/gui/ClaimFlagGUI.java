package com.swag.swagclaims.gui;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import com.swag.swagclaims.model.FlagValue;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QoL panel for {@code /claimflag}, so managing GPFlags-style flags doesn't require typing exact
 * flag keys and params by hand. One item per flag (17 total, all fit in the content area without
 * pagination), green when on / red when off. Left-click toggles; right-click on the five flags
 * that take a free-text parameter cycles through a small preset list instead — Bukkit inventories
 * can't take free-text input, so a preset cycle is the simplest correct substitute (documented
 * below). Power users can still set an arbitrary params string with the unchanged text command,
 * {@code /claimflag <flag> <params>}.
 *
 * <p><b>Presets (judgment call — pick sensible values, not exhaustive):</b>
 * <ul>
 *     <li>{@link ClaimFlags#PLAYER_TIME}: day → noon → night → midnight → off (off removes the
 *     flag entirely, matching how {@code /claimflag playertime off} already works from chat).</li>
 *     <li>{@link ClaimFlags#PLAYER_WEATHER}: sun → rain (loops).</li>
 *     <li>{@link ClaimFlags#ENTER_ACTION_BAR}: three canned messages (loops).</li>
 *     <li>{@link ClaimFlags#HEALTH_REGEN}: 0.5 → 1.0 → 2.0 → 5.0 health/sec (loops).</li>
 *     <li>{@link ClaimFlags#NO_HUNGER}: "full" (no params — fully blocks drain) → 2 → 4 → 10
 *     (the 1-in-N chance divisor documented on {@link ClaimFlags#NO_HUNGER}); "full" is stored as
 *     a null params value, not the literal string.</li>
 * </ul>
 */
public class ClaimFlagGUI implements OpenMenu {

    // Content area: two full rows (9-17, 18-26) plus one more (27-34) - 17 flags fit in 26 slots.
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30
    };
    private static final int SLOT_CLOSE = 49;

    private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();
    private static final Map<String, String[]> PRESETS = new HashMap<>();

    static {
        DISPLAY_NAMES.put(ClaimFlags.NO_MONSTERS, "No Monsters");
        DISPLAY_NAMES.put(ClaimFlags.NO_MONSTER_SPAWNS, "No Monster Spawns");
        DISPLAY_NAMES.put(ClaimFlags.NO_HUNGER, "No Hunger");
        DISPLAY_NAMES.put(ClaimFlags.NO_FALL_DAMAGE, "No Fall Damage");
        DISPLAY_NAMES.put(ClaimFlags.NO_FIRE_DAMAGE, "No Fire Damage");
        DISPLAY_NAMES.put(ClaimFlags.NO_EXPLOSION_DAMAGE, "No Explosion Damage");
        DISPLAY_NAMES.put(ClaimFlags.NO_ITEM_DAMAGE, "No Item Damage");
        DISPLAY_NAMES.put(ClaimFlags.NO_PLAYER_DAMAGE_BY_MONSTER, "No Player Damage By Monster");
        DISPLAY_NAMES.put(ClaimFlags.NO_FLUID_FLOW, "No Fluid Flow");
        DISPLAY_NAMES.put(ClaimFlags.NO_CORAL_DEATH, "No Coral Death");
        DISPLAY_NAMES.put(ClaimFlags.NO_BLOCK_GRAVITY, "No Block Gravity");
        DISPLAY_NAMES.put(ClaimFlags.KEEP_INVENTORY, "Keep Inventory");
        DISPLAY_NAMES.put(ClaimFlags.HEALTH_REGEN, "Health Regen");
        DISPLAY_NAMES.put(ClaimFlags.PLAYER_TIME, "Player Time");
        DISPLAY_NAMES.put(ClaimFlags.PLAYER_WEATHER, "Player Weather");
        DISPLAY_NAMES.put(ClaimFlags.ENTER_ACTION_BAR, "Enter Action Bar");
        DISPLAY_NAMES.put(ClaimFlags.PVP, "PvP");

        PRESETS.put(ClaimFlags.PLAYER_TIME, new String[]{"day", "noon", "night", "midnight", "off"});
        PRESETS.put(ClaimFlags.PLAYER_WEATHER, new String[]{"sun", "rain"});
        PRESETS.put(ClaimFlags.ENTER_ACTION_BAR, new String[]{
                "&aWelcome to the claim!", "&eYou are entering a protected area.", "&cKeep out!"});
        PRESETS.put(ClaimFlags.HEALTH_REGEN, new String[]{"0.5", "1.0", "2.0", "5.0"});
        PRESETS.put(ClaimFlags.NO_HUNGER, new String[]{"full", "2", "4", "10"});
    }

    private final SwagClaimsPlugin plugin;
    private final Player viewer;
    private final Claim claim;

    public ClaimFlagGUI(SwagClaimsPlugin plugin, Player viewer, Claim claim) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.claim = claim;
    }

    public void open() {
        String claimName = claim.getName() != null && !claim.getName().isEmpty() ? claim.getName() : "Claim #" + claim.getId();
        Inventory inv = Bukkit.createInventory(null, 54, color("&2&lFlags: " + claimName));

        ItemStack filler = createNamedItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }

        for (int i = 0; i < CONTENT_SLOTS.length && i < ClaimFlags.ALL.size(); i++) {
            inv.setItem(CONTENT_SLOTS[i], createFlagItem(ClaimFlags.ALL.get(i)));
        }

        inv.setItem(SLOT_CLOSE, createNamedItem(Material.BARRIER, "&cClose", null));

        viewer.openInventory(inv);
        plugin.getGuiListener().register(viewer.getUniqueId(), this);
    }

    private ItemStack createFlagItem(String key) {
        FlagValue value = claim.getFlag(key);
        boolean on = value != null && value.isValue();

        Material material = on ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        String display = DISPLAY_NAMES.getOrDefault(key, key);

        List<String> lore = new ArrayList<>();
        lore.add(color(on ? "&aON" : "&7OFF"));
        if (on && value.getParams() != null && !value.getParams().isEmpty()) {
            lore.add(color("&7Params: &f" + value.getParams()));
        }
        lore.add("");
        lore.add(color("&aLeft-click &7to toggle"));
        if (PRESETS.containsKey(key)) {
            lore.add(color("&eRight-click &7to cycle presets"));
        }

        return createNamedItem(material, (on ? "&a" : "&c") + display, lore);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }

        String key = null;
        for (int i = 0; i < CONTENT_SLOTS.length && i < ClaimFlags.ALL.size(); i++) {
            if (CONTENT_SLOTS[i] == slot) {
                key = ClaimFlags.ALL.get(i);
                break;
            }
        }
        if (key == null) return;

        if (event.getClick().isRightClick() && PRESETS.containsKey(key)) {
            cyclePreset(key);
        } else {
            toggle(key);
        }
        refresh();
    }

    private void toggle(String key) {
        FlagValue current = claim.getFlag(key);
        if (current != null && current.isValue()) {
            plugin.getFlagManager().removeClaimFlag(claim, key);
        } else {
            String defaultParams = PRESETS.containsKey(key) ? toStoredParams(key, PRESETS.get(key)[0]) : null;
            plugin.getFlagManager().setClaimFlag(claim, key, defaultParams, true);
        }
    }

    private void cyclePreset(String key) {
        String[] presets = PRESETS.get(key);
        FlagValue current = claim.getFlag(key);
        String currentParam = current != null ? current.getParams() : null;

        int currentIndex = indexOfPreset(key, presets, currentParam, current != null && current.isValue());
        String next = presets[(currentIndex + 1) % presets.length];

        if (key.equals(ClaimFlags.PLAYER_TIME) && next.equals("off")) {
            plugin.getFlagManager().removeClaimFlag(claim, key);
            return;
        }
        plugin.getFlagManager().setClaimFlag(claim, key, toStoredParams(key, next), true);
    }

    /** "full" is NO_HUNGER's sentinel for "no params" (fully blocks drain) — never stored literally. */
    private String toStoredParams(String key, String preset) {
        if (key.equals(ClaimFlags.NO_HUNGER) && preset.equals("full")) return null;
        return preset;
    }

    private int indexOfPreset(String key, String[] presets, String currentParam, boolean isOn) {
        if (!isOn) return -1; // so the first preset (index 0) is picked next
        String comparable = (key.equals(ClaimFlags.NO_HUNGER) && (currentParam == null || currentParam.isEmpty()))
                ? "full" : currentParam;
        for (int i = 0; i < presets.length; i++) {
            if (presets[i].equalsIgnoreCase(comparable)) return i;
        }
        return -1;
    }

    private void refresh() {
        open();
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
