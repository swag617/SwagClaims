package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.gui.ClaimBlockSendGUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /sendclaimblocks — opens the "gift claim blocks" GUI (target picker screen). {@code
 * /claimblocks send} is the same feature under an alias, see {@link ClaimBlocksCommand}.
 */
public class SendClaimBlocksCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public SendClaimBlocksCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;
        if (!player.hasPermission("swagclaims.sendclaimblocks")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }
        new ClaimBlockSendGUI(plugin, player).openTargetScreen();
        return true;
    }
}
