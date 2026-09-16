package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.gui.ClaimBlockSendGUI;
import com.swag.swagclaims.model.PlayerClaimData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /claimblocks — shows the sender's accrued/bonus/used/remaining claim block totals.
 * {@code /claimblocks send} is an alias into the same "gift claim blocks" GUI
 * {@link SendClaimBlocksCommand} opens — kept here too since the phase plan calls it out as
 * "alias claimblocks send" alongside the dedicated command. {@code /claimblocks give <player>
 * <amount>} is the admin counterpart — grants bonus blocks directly, no permission/console
 * restriction beyond {@code swagclaims.admin.claimblocks.give}, offline-target-safe.
 * {@code /claimblocks take <player> <amount>} is give's inverse — removes bonus blocks (never a
 * player's naturally accrued ones), gated on {@code swagclaims.admin.claimblocks.take}.
 */
public class ClaimBlocksCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public ClaimBlocksCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            handleGive(sender, args);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("take")) {
            handleTake(sender, args);
            return true;
        }

        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;

        if (args.length >= 1 && args[0].equalsIgnoreCase("send")) {
            if (!player.hasPermission("swagclaims.sendclaimblocks")) {
                plugin.getMessages().send(player, "general.no-permission");
                return true;
            }
            new ClaimBlockSendGUI(plugin, player).openTargetScreen();
            return true;
        }

        if (!player.hasPermission("swagclaims.claimblocks")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }

        PlayerClaimData data = plugin.getClaimManager().getPlayerData(player.getUniqueId());
        long used = data.getUsedBlocks(plugin.getClaimManager().getClaimsByOwner(player.getUniqueId()));
        long remaining = data.getTotalBlocks() - used;

        plugin.getMessages().sendRaw(player, "blocks.summary-header", null);
        plugin.getMessages().sendRaw(player, "blocks.summary-accrued", ClaimCommandUtil.ph("accrued", String.valueOf(data.getAccruedBlocks())));
        plugin.getMessages().sendRaw(player, "blocks.summary-bonus", ClaimCommandUtil.ph("bonus", String.valueOf(data.getBonusBlocks())));
        plugin.getMessages().sendRaw(player, "blocks.summary-used", ClaimCommandUtil.ph("used", String.valueOf(used)));
        plugin.getMessages().sendRaw(player, "blocks.summary-remaining", ClaimCommandUtil.ph("remaining", String.valueOf(remaining)));
        return true;
    }

    /** {@code /claimblocks give <player> <amount>} — console-capable, offline-target-safe. */
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("swagclaims.admin.claimblocks.give")) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
            return;
        }
        if (args.length != 3) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.give-usage"));
            return;
        }

        String targetName = args[1];
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.give-invalid-amount"));
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.give-invalid-amount"));
            return;
        }

        UUID targetUuid = ClaimCommandUtil.resolveTargetUuid(targetName);
        if (targetUuid == null) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("general.player-not-found", ClaimCommandUtil.ph("player", targetName)));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        String displayName = target.getName() != null ? target.getName() : targetName;

        plugin.getClaimManager().giveClaimBlocks(targetUuid, amount).whenComplete((v, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to give " + amount + " claim block(s) to " + displayName, err);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("admin.give-failed", ClaimCommandUtil.ph("player", displayName))));
                return;
            }
            var placeholders = ClaimCommandUtil.ph("player", displayName);
            placeholders.put("amount", String.valueOf(amount));
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.give-success", placeholders));
                Player targetOnline = Bukkit.getPlayer(targetUuid);
                if (targetOnline != null) {
                    plugin.getMessages().send(targetOnline, "admin.give-notify", ClaimCommandUtil.ph("amount", String.valueOf(amount)));
                }
            });
        });
    }

    /** {@code /claimblocks take <player> <amount>} — console-capable, offline-target-safe.
     *  Mirrors {@link #handleGive}, but only ever removes bonus blocks (see
     *  {@link com.swag.swagclaims.manager.ClaimManager#takeClaimBlocks}) — never a player's
     *  naturally accrued (playtime) blocks — and reports the amount actually removed, which may
     *  be less than requested if they didn't have that many bonus blocks. */
    private void handleTake(CommandSender sender, String[] args) {
        if (!sender.hasPermission("swagclaims.admin.claimblocks.take")) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
            return;
        }
        if (args.length != 3) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.take-usage"));
            return;
        }

        String targetName = args[1];
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.take-invalid-amount"));
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.take-invalid-amount"));
            return;
        }

        UUID targetUuid = ClaimCommandUtil.resolveTargetUuid(targetName);
        if (targetUuid == null) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("general.player-not-found", ClaimCommandUtil.ph("player", targetName)));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        String displayName = target.getName() != null ? target.getName() : targetName;
        long requested = amount;

        plugin.getClaimManager().takeClaimBlocks(targetUuid, amount).whenComplete((actual, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to take " + requested + " claim block(s) from " + displayName, err);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("admin.take-failed", ClaimCommandUtil.ph("player", displayName))));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (actual <= 0) {
                    sender.sendMessage(plugin.getMessages().getPrefix()
                            + plugin.getMessages().get("admin.take-none", ClaimCommandUtil.ph("player", displayName)));
                    return;
                }
                var placeholders = ClaimCommandUtil.ph("player", displayName);
                placeholders.put("amount", String.valueOf(actual));
                String key = actual < requested ? "admin.take-partial" : "admin.take-success";
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get(key, placeholders));

                Player targetOnline = Bukkit.getPlayer(targetUuid);
                if (targetOnline != null) {
                    plugin.getMessages().send(targetOnline, "admin.take-notify", ClaimCommandUtil.ph("amount", String.valueOf(actual)));
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("swagclaims.sendclaimblocks")) options.add("send");
            if (sender.hasPermission("swagclaims.admin.claimblocks.give")) options.add("give");
            if (sender.hasPermission("swagclaims.admin.claimblocks.take")) options.add("take");
            return ClaimCommandUtil.filter(args[0], options);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take"))
                && (sender.hasPermission("swagclaims.admin.claimblocks.give") || sender.hasPermission("swagclaims.admin.claimblocks.take"))) {
            return ClaimCommandUtil.players(args[1]);
        }
        return List.of();
    }
}
