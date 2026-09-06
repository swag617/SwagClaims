package com.swag.swagclaims.gui;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.command.ClaimCommandUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Gift claim blocks" flow, replacing the "GPSend" addon. Two screens: pick a target (a specific
 * online player, or every online player at once), then pick an amount with +/- buttons and
 * confirm. Blocks are debited from the sender's own spare (unused) pool and credited to the
 * recipient's bonus blocks — see {@link com.swag.swagclaims.manager.ClaimManager#sendClaimBlocks}
 * for the exact debit ordering, which mirrors {@code abandonClaim}'s existing bonus-then-accrued
 * penalty bookkeeping.
 *
 * <p>Both screens are handled by this single class (a {@code Screen} field selects which one is
 * currently rendered) rather than two separate GUI classes — simpler given how much state
 * (target, running amount) needs to carry over between them.
 */
public class ClaimBlockSendGUI implements OpenMenu {

    private enum Screen { TARGET, AMOUNT }
    private enum TargetMode { SPECIFIC, ALL }

    // ── Target screen slots ─────────────────────────────────────────────────
    private static final int SLOT_SPECIFIC = 20;
    private static final int SLOT_ALL = 24;
    private static final int SLOT_TARGET_CANCEL = 49;

    // ── Amount screen slots (row 18-26 = -1000,-100,-10,-1,info,+1,+10,+100,+1000) ──
    private static final int SLOT_MINUS_1000 = 18;
    private static final int SLOT_MINUS_100 = 19;
    private static final int SLOT_MINUS_10 = 20;
    private static final int SLOT_MINUS_1 = 21;
    private static final int SLOT_INFO = 22;
    private static final int SLOT_PLUS_1 = 23;
    private static final int SLOT_PLUS_10 = 24;
    private static final int SLOT_PLUS_100 = 25;
    private static final int SLOT_PLUS_1000 = 26;
    private static final int SLOT_TARGET_DISPLAY = 4;
    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_AMOUNT_CANCEL = 53;

    private final SwagClaimsPlugin plugin;
    private final Player sender;

    private Screen screen = Screen.TARGET;
    private TargetMode targetMode = TargetMode.SPECIFIC;
    private UUID targetUuid;
    private String targetName;
    private long amount = 0;

    public ClaimBlockSendGUI(SwagClaimsPlugin plugin, Player sender) {
        this.plugin = plugin;
        this.sender = sender;
    }

    public void openTargetScreen() {
        this.screen = Screen.TARGET;
        Inventory inv = Bukkit.createInventory(null, 54, color("&2&lSend Claim Blocks &7- Pick a Target"));

        fillBorder(inv);
        inv.setItem(SLOT_SPECIFIC, createNamedItem(Material.PLAYER_HEAD, "&a&lSpecific Player",
                List.of(color("&7Send claim blocks to one"), color("&7online player you pick."))));
        inv.setItem(SLOT_ALL, createNamedItem(Material.BEACON, "&b&lAll Online Players",
                List.of(color("&7Send the same amount to"), color("&7every player currently online."))));
        inv.setItem(SLOT_TARGET_CANCEL, createNamedItem(Material.BARRIER, "&cCancel", null));

        open(inv);
    }

    private void openAmountScreen() {
        this.screen = Screen.AMOUNT;
        long remaining = plugin.getClaimManager().getRemainingBlocks(sender.getUniqueId());
        long targetCount = targetCount();
        long maxPerTarget = targetCount > 0 ? Math.max(0, remaining / targetCount) : 0;
        this.amount = Math.min(this.amount, maxPerTarget);

        String title = targetMode == TargetMode.ALL ? "All Online Players" : targetName;
        Inventory inv = Bukkit.createInventory(null, 54, color("&2&lSend Claim Blocks &7- " + title));

        fillBorder(inv);
        inv.setItem(SLOT_TARGET_DISPLAY, createNamedItem(Material.NAME_TAG, "&eSending to: &f" + title, null));

        inv.setItem(SLOT_MINUS_1000, createDeltaItem(Material.REDSTONE_BLOCK, -1000));
        inv.setItem(SLOT_MINUS_100, createDeltaItem(Material.RED_CONCRETE, -100));
        inv.setItem(SLOT_MINUS_10, createDeltaItem(Material.RED_DYE, -10));
        inv.setItem(SLOT_MINUS_1, createDeltaItem(Material.GRAY_DYE, -1));
        inv.setItem(SLOT_PLUS_1, createDeltaItem(Material.LIME_DYE, 1));
        inv.setItem(SLOT_PLUS_10, createDeltaItem(Material.LIME_DYE, 10));
        inv.setItem(SLOT_PLUS_100, createDeltaItem(Material.LIME_CONCRETE, 100));
        inv.setItem(SLOT_PLUS_1000, createDeltaItem(Material.EMERALD_BLOCK, 1000));

        long totalNeeded = amount * targetCount;
        boolean affordable = totalNeeded <= remaining && amount > 0;
        List<String> infoLore = new ArrayList<>();
        infoLore.add(color("&7Per target: &f" + amount));
        if (targetCount > 1) infoLore.add(color("&7Targets: &f" + targetCount + " &7(total: &f" + totalNeeded + "&7)"));
        infoLore.add(color("&7Your spare blocks: &f" + remaining));
        infoLore.add("");
        infoLore.add(color(affordable ? "&aAffordable: yes" : "&cAffordable: no"));
        inv.setItem(SLOT_INFO, createNamedItem(Material.PAPER, "&eAmount: &f" + amount, infoLore));

        inv.setItem(SLOT_CONFIRM, createNamedItem(affordable ? Material.LIME_WOOL : Material.GRAY_WOOL,
                affordable ? "&a&lConfirm" : "&7&lConfirm (invalid amount)",
                List.of(color("&7Click to send."))));
        inv.setItem(SLOT_BACK, createNamedItem(Material.ARROW, "&e← Back", null));
        inv.setItem(SLOT_AMOUNT_CANCEL, createNamedItem(Material.BARRIER, "&cCancel", null));

        open(inv);
    }

    private long targetCount() {
        if (targetMode == TargetMode.SPECIFIC) return 1;
        return Bukkit.getOnlinePlayers().stream().filter(p -> !p.getUniqueId().equals(sender.getUniqueId())).count();
    }

    private void open(Inventory inv) {
        sender.openInventory(inv);
        plugin.getGuiListener().register(sender.getUniqueId(), this);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (screen == Screen.TARGET) {
            handleTargetClick(slot);
        } else {
            handleAmountClick(slot);
        }
    }

    private void handleTargetClick(int slot) {
        if (slot == SLOT_SPECIFIC) {
            new OnlinePlayerPickerGUI(plugin, sender, selected -> {
                this.targetMode = TargetMode.SPECIFIC;
                this.targetUuid = selected.getUniqueId();
                this.targetName = selected.getName();
                this.amount = 0;
                openAmountScreen();
            }, this::openTargetScreen).open();
            return;
        }
        if (slot == SLOT_ALL) {
            if (targetCountForAll() <= 0) {
                plugin.getMessages().send(sender, "gui.sendblocks.no-targets");
                return;
            }
            this.targetMode = TargetMode.ALL;
            this.targetUuid = null;
            this.targetName = "All Online Players";
            this.amount = 0;
            openAmountScreen();
            return;
        }
        if (slot == SLOT_TARGET_CANCEL) {
            sender.closeInventory();
        }
    }

    private long targetCountForAll() {
        return Bukkit.getOnlinePlayers().stream().filter(p -> !p.getUniqueId().equals(sender.getUniqueId())).count();
    }

    private void handleAmountClick(int slot) {
        long remaining = plugin.getClaimManager().getRemainingBlocks(sender.getUniqueId());
        long targetCount = targetCount();
        long maxPerTarget = targetCount > 0 ? Math.max(0, remaining / targetCount) : 0;

        Long delta = switch (slot) {
            case SLOT_MINUS_1000 -> -1000L;
            case SLOT_MINUS_100 -> -100L;
            case SLOT_MINUS_10 -> -10L;
            case SLOT_MINUS_1 -> -1L;
            case SLOT_PLUS_1 -> 1L;
            case SLOT_PLUS_10 -> 10L;
            case SLOT_PLUS_100 -> 100L;
            case SLOT_PLUS_1000 -> 1000L;
            default -> null;
        };
        if (delta != null) {
            this.amount = Math.max(0, Math.min(maxPerTarget, this.amount + delta));
            openAmountScreen();
            return;
        }
        if (slot == SLOT_BACK) {
            openTargetScreen();
            return;
        }
        if (slot == SLOT_AMOUNT_CANCEL) {
            sender.closeInventory();
            return;
        }
        if (slot == SLOT_CONFIRM) {
            confirmSend();
        }
    }

    private void confirmSend() {
        if (amount <= 0) return;

        if (targetMode == TargetMode.SPECIFIC) {
            Player target = targetUuid != null ? Bukkit.getPlayer(targetUuid) : null;
            if (target == null) {
                plugin.getMessages().send(sender, "gui.sendblocks.invalid-target");
                openTargetScreen();
                return;
            }
            boolean success = plugin.getClaimManager().sendClaimBlocks(sender.getUniqueId(), targetUuid, amount);
            if (!success) {
                plugin.getMessages().send(sender, "gui.sendblocks.insufficient");
                openAmountScreen();
                return;
            }
            Map<String, String> ph = ClaimCommandUtil.ph("amount", String.valueOf(amount));
            ph.put("target", target.getName());
            plugin.getMessages().send(sender, "gui.sendblocks.success-specific", ph);
            plugin.getMessages().send(target, "gui.sendblocks.received", ClaimCommandUtil.ph("amount", String.valueOf(amount)) );
            sender.closeInventory();
            return;
        }

        // ALL — recompute the online snapshot fresh at confirm time rather than trusting the
        // count the amount was clamped against on the previous render (the online player set
        // can change between screens).
        List<Player> onlineTargets = Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.getUniqueId().equals(sender.getUniqueId()))
                .<Player>map(p -> p)
                .toList();
        if (onlineTargets.isEmpty()) {
            plugin.getMessages().send(sender, "gui.sendblocks.no-targets");
            openTargetScreen();
            return;
        }
        long totalNeeded = amount * onlineTargets.size();
        long remaining = plugin.getClaimManager().getRemainingBlocks(sender.getUniqueId());
        if (totalNeeded > remaining) {
            plugin.getMessages().send(sender, "gui.sendblocks.insufficient");
            openAmountScreen();
            return;
        }

        for (Player target : onlineTargets) {
            plugin.getClaimManager().sendClaimBlocks(sender.getUniqueId(), target.getUniqueId(), amount);
        }

        Map<String, String> ph = ClaimCommandUtil.ph("amount", String.valueOf(amount));
        ph.put("count", String.valueOf(onlineTargets.size()));
        plugin.getMessages().send(sender, "gui.sendblocks.success-all", ph);

        Map<String, String> broadcastPh = ClaimCommandUtil.ph("amount", String.valueOf(amount));
        broadcastPh.put("sender", sender.getName());
        String broadcast = plugin.getMessages().getPrefix() + plugin.getMessages().get("gui.sendblocks.broadcast-all", broadcastPh);
        for (Player target : onlineTargets) {
            target.sendMessage(broadcast);
        }
        sender.closeInventory();
    }

    private ItemStack createDeltaItem(Material material, int delta) {
        String sign = delta > 0 ? "+" : "";
        return createNamedItem(material, (delta > 0 ? "&a" : "&c") + sign + delta, null);
    }

    private void fillBorder(Inventory inv) {
        ItemStack filler = createNamedItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, filler);
            }
        }
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
