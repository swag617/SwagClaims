package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /adminclaims — toggles administrator claim creation mode for the claim tool. */
public class AdminClaimsCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public AdminClaimsCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;
        if (!player.hasPermission("swagclaims.admin.claims")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }

        boolean nowOn = plugin.getClaimToolListener().toggleAdminClaimMode(player.getUniqueId());
        plugin.getMessages().send(player, nowOn ? "admin.mode-on" : "admin.mode-off");
        return true;
    }
}
