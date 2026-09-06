package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /claiminfo — shows owner, size, type and trust list for the claim the sender is standing in. */
public class ClaimInfoCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public ClaimInfoCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;

        Claim claim = plugin.getClaimManager().getClaimAt(player.getLocation());
        if (claim == null) {
            plugin.getMessages().send(player, "tool.investigate-wilderness");
            return true;
        }

        ClaimCommandUtil.sendClaimInfo(plugin, player, claim);

        if (claim.getOwnerUuid() != null && claim.getOwnerUuid().equals(player.getUniqueId())) {
            long remaining = plugin.getClaimManager().getRemainingBlocks(player.getUniqueId());
            plugin.getMessages().send(player, "info.blocks-remaining", ClaimCommandUtil.ph("remaining", String.valueOf(remaining)));
        }
        return true;
    }
}
