package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /subdivideclaims — toggles subdivision creation mode for the claim tool. */
public class SubdivideClaimsCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public SubdivideClaimsCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;
        if (!player.hasPermission("swagclaims.subdivide")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }

        boolean nowOn = plugin.getClaimToolListener().toggleSubdivisionMode(player.getUniqueId());
        plugin.getMessages().send(player, nowOn ? "subdivide.mode-on" : "subdivide.mode-off");
        return true;
    }
}
