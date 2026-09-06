package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /deleteclaim [id] — admin force-delete of any claim, by id or by standing in it. */
public class DeleteClaimCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public DeleteClaimCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("swagclaims.admin.deleteclaim")) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
            return true;
        }

        Claim claim;
        if (args.length >= 1) {
            long id;
            try {
                id = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.delete-usage"));
                return true;
            }
            claim = plugin.getClaimManager().getClaim(id);
            if (claim == null) {
                sender.sendMessage(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("admin.claim-not-found", ClaimCommandUtil.ph("id", String.valueOf(id))));
                return true;
            }
        } else {
            Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
            if (player == null) return true;
            claim = plugin.getClaimManager().getClaimAt(player.getLocation());
            if (claim == null) {
                plugin.getMessages().send(player, "admin.delete-not-standing");
                return true;
            }
        }

        long claimId = claim.getId();
        long returned = claim.isTopLevel()
                ? plugin.getClaimManager().abandonTopLevelClaim(claim)
                : plugin.getClaimManager().abandonClaim(claim);

        var placeholders = ClaimCommandUtil.ph("id", String.valueOf(claimId));
        placeholders.put("returned", String.valueOf(returned));
        sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.delete-success", placeholders));
        return true;
    }
}
