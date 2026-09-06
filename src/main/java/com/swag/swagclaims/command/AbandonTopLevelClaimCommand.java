package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /abandontoplevelclaim — deletes the top-level claim the sender is standing in, and every subdivision inside it. */
public class AbandonTopLevelClaimCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public AbandonTopLevelClaimCommand(SwagClaimsPlugin plugin) {
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

        Claim topLevel = claim;
        if (claim.isSubdivision()) {
            Claim parent = plugin.getClaimManager().getClaim(claim.getParentId());
            if (parent != null) topLevel = parent;
        }

        if (!ClaimCommandUtil.canAbandon(plugin, player, topLevel)) {
            plugin.getMessages().send(player, "abandon.no-permission");
            return true;
        }

        long returned = plugin.getClaimManager().abandonTopLevelClaim(topLevel);
        plugin.getMessages().send(player, "abandon.success-toplevel", ClaimCommandUtil.ph("returned", String.valueOf(returned)));
        return true;
    }
}
