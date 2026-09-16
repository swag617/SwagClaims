package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * /claimban <player> — bans a player from the claim the sender is standing in. A banned player is
 * denied entry outright (see {@code ClaimTransitionListener#gateTransition}) regardless of any
 * trust level they separately hold there — bans and trust are independent lists that don't clear
 * each other, so an owner who bans a previously-trusted player should also {@code /untrust} them
 * if the intent is to fully revoke their standing, not just block entry.
 */
public class ClaimBanCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public ClaimBanCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;

        if (!player.hasPermission("swagclaims.claimban")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }
        if (args.length < 1) {
            plugin.getMessages().send(player, "ban.usage", ClaimCommandUtil.ph("command", label));
            return true;
        }

        Claim claim = ClaimCommandUtil.requireClaimAt(plugin, player, "ban.not-in-claim");
        if (claim == null) return true;

        if (!ClaimCommandUtil.canManage(plugin, player, claim)) {
            plugin.getMessages().send(player, "ban.no-permission-manage");
            return true;
        }

        String targetName = args[0];
        UUID targetUuid = ClaimCommandUtil.resolveTargetUuid(targetName);
        if (targetUuid == null) {
            plugin.getMessages().send(player, "general.player-not-found", ClaimCommandUtil.ph("player", targetName));
            return true;
        }
        if (targetUuid.equals(player.getUniqueId())) {
            plugin.getMessages().send(player, "ban.cannot-ban-self");
            return true;
        }
        if (claim.getOwnerUuid() != null && claim.getOwnerUuid().equals(targetUuid)) {
            plugin.getMessages().send(player, "ban.cannot-ban-owner");
            return true;
        }

        plugin.getClaimManager().banPlayer(claim, targetUuid);

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        String displayName = target.getName() != null ? target.getName() : targetName;
        plugin.getMessages().send(player, "ban.banned", ClaimCommandUtil.ph("target", displayName));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        return ClaimCommandUtil.players(args[0]);
    }
}
