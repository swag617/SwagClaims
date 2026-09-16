package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /claimrename &lt;name&gt; — sets the nickname of the claim the sender is standing in; "clear" or
 * "--clear" as the (sole) argument removes it back to unset. Requires MANAGE trust (or
 * ownership/admin bypass), matching every other claim-identity-changing command in this plugin
 * (see {@link ClaimFlagCommand}). Color codes are allowed and sanitized the same way every other
 * user-composed display string in this plugin is (see {@code Messages#color}).
 */
public class ClaimRenameCommand implements CommandExecutor, TabCompleter {

    /** Comfortably under the swagclaims_claims.name VARCHAR(64) column. */
    private static final int MAX_NAME_LENGTH = 32;

    private final SwagClaimsPlugin plugin;

    public ClaimRenameCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;

        if (!player.hasPermission("swagclaims.rename")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }

        Claim claim = ClaimCommandUtil.requireClaimAt(plugin, player, "rename.not-in-claim");
        if (claim == null) return true;

        if (!ClaimCommandUtil.canManage(plugin, player, claim)) {
            plugin.getMessages().send(player, "rename.no-permission-manage");
            return true;
        }

        if (args.length == 0) {
            plugin.getMessages().send(player, "rename.usage");
            return true;
        }

        if (args.length == 1 && (args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("--clear"))) {
            plugin.getClaimManager().renameClaim(claim, null);
            plugin.getMessages().send(player, "rename.cleared");
            return true;
        }

        String sanitized = ChatColor.translateAlternateColorCodes('&', String.join(" ", args));
        if (sanitized.length() > MAX_NAME_LENGTH) {
            plugin.getMessages().send(player, "rename.too-long", ClaimCommandUtil.ph("max", String.valueOf(MAX_NAME_LENGTH)));
            return true;
        }

        plugin.getClaimManager().renameClaim(claim, sanitized);
        plugin.getMessages().send(player, "rename.success", ClaimCommandUtil.ph("name", sanitized));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return ClaimCommandUtil.filter(args[0], List.of("clear"));
        return List.of();
    }
}
