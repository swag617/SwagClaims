package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.UUID;

/** /deleteallclaims <player> — admin force-delete of every claim a player owns. */
public class DeleteAllClaimsCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public DeleteAllClaimsCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("swagclaims.admin.deleteallclaims")) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.deleteall-usage"));
            return true;
        }

        UUID targetUuid = ClaimCommandUtil.resolveTargetUuid(args[0]);
        if (targetUuid == null) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("general.player-not-found", ClaimCommandUtil.ph("player", args[0])));
            return true;
        }

        long count = plugin.getClaimManager().getClaimsByOwner(targetUuid).stream()
                .filter(c -> c.isTopLevel()).count();
        long returned = plugin.getClaimManager().abandonAllClaims(targetUuid);

        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetUuid);
        String name = offline.getName() != null ? offline.getName() : args[0];

        var placeholders = ClaimCommandUtil.ph("player", name);
        placeholders.put("count", String.valueOf(count));
        placeholders.put("returned", String.valueOf(returned));
        sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.deleteall-success", placeholders));
        return true;
    }
}
