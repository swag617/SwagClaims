package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /abandonclaim — deletes the single claim (subdivision or top-level) the sender is standing in. */
public class AbandonClaimCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public AbandonClaimCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;
        if (!player.hasPermission("swagclaims.abandon")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }

        Claim claim = ClaimCommandUtil.requireClaimAt(plugin, player, "abandon.not-in-claim");
        if (claim == null) return true;

        if (!ClaimCommandUtil.canAbandon(plugin, player, claim)) {
            plugin.getMessages().send(player, "abandon.no-permission");
            return true;
        }

        long returned = plugin.getClaimManager().abandonClaim(claim);
        plugin.getMessages().send(player, "abandon.success", ClaimCommandUtil.ph("returned", String.valueOf(returned)));
        return true;
    }
}
