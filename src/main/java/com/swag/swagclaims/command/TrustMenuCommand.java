package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.gui.TrustManagerGUI;
import com.swag.swagclaims.model.Claim;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /trustmenu — opens the interactive trust manager GUI for the claim the sender is standing in.
 * Gated the same two-step way {@code /claimflag} is: a blanket "you may use this feature at all"
 * permission, then a per-claim {@link ClaimCommandUtil#canManage} check (ownership, MANAGE trust,
 * or the admin.claims bypass).
 */
public class TrustMenuCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public TrustMenuCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;

        if (!player.hasPermission("swagclaims.trustmenu")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }

        Claim claim = ClaimCommandUtil.requireClaimAt(plugin, player, "trust.not-in-claim");
        if (claim == null) return true;

        if (!ClaimCommandUtil.canManage(plugin, player, claim)) {
            plugin.getMessages().send(player, "trust.no-permission-manage");
            return true;
        }

        new TrustManagerGUI(plugin, player, claim).open();
        return true;
    }
}
