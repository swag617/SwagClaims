package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.gui.ClaimBlockSendGUI;
import com.swag.swagclaims.model.PlayerClaimData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /claimblocks — shows the sender's accrued/bonus/used/remaining claim block totals.
 * {@code /claimblocks send} is an alias into the same "gift claim blocks" GUI
 * {@link SendClaimBlocksCommand} opens — kept here too since the phase plan calls it out as
 * "alias claimblocks send" alongside the dedicated command.
 */
public class ClaimBlocksCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public ClaimBlocksCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
}
