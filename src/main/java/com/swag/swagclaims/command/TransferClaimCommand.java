package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimType;
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
 * /transferclaim [id] &lt;player&gt; — admin transfer of a top-level BASIC claim's ownership to
 * another player. Mirrors {@link DeleteClaimCommand}'s claim-resolution UX (an explicit numeric
 * id, or "the claim the sender is standing in" if omitted) with the new-owner name always the
 * last argument, so the argument count alone disambiguates: one arg is just the player name
 * (claim resolved from where the sender stands), two args are {@code <id> <player>}.
 */
public class TransferClaimCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public TransferClaimCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("swagclaims.admin.transferclaim")) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.transfer-usage"));
            return true;
        }

        Claim claim;
        String targetName;
        if (args.length == 2) {
            long id;
            try {
                id = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.transfer-usage"));
                return true;
            }
            claim = plugin.getClaimManager().getClaim(id);
            if (claim == null) {
                sender.sendMessage(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("admin.claim-not-found", ClaimCommandUtil.ph("id", String.valueOf(id))));
                return true;
            }
            targetName = args[1];
        } else {
            Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
            if (player == null) return true;
            claim = plugin.getClaimManager().getClaimAt(player.getLocation());
            if (claim == null) {
                plugin.getMessages().send(player, "admin.transfer-not-standing");
                return true;
            }
            targetName = args[0];
        }

        if (claim.isSubdivision()) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.transfer-is-subdivision"));
            return true;
        }

        boolean isAdminClaim = claim.getClaimType() == ClaimType.ADMIN;
        if (claim.getClaimType() != ClaimType.BASIC && !isAdminClaim) {
            // Not reachable in practice (a top-level claim is always BASIC or ADMIN — subdivisions
            // are caught above), but guarded rather than assumed.
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.transfer-not-basic"));
            return true;
        }
        if (isAdminClaim && !sender.hasPermission("swagclaims.admin.transferclaim.admin")) {
            // Transferring an admin claim converts it to a player-owned BASIC claim, which is a
            // bigger deal than moving an already-player-owned claim between two players — gate it
            // behind its own permission on top of the base swagclaims.admin.transferclaim check
            // above, rather than silently folding it into the "not basic" rejection.
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.transfer-admin-permission"));
            return true;
        }

        UUID newOwnerUuid = ClaimCommandUtil.resolveTargetUuid(targetName);
        if (newOwnerUuid == null) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("general.player-not-found", ClaimCommandUtil.ph("player", targetName)));
            return true;
        }
        if (newOwnerUuid.equals(claim.getOwnerUuid())) {
            sender.sendMessage(plugin.getMessages().getPrefix()
                    + plugin.getMessages().get("admin.transfer-already-owner", ClaimCommandUtil.ph("player", targetName)));
            return true;
        }

        UUID previousOwnerUuid = claim.getOwnerUuid(); // null for an admin claim
        String previousOwnerName;
        if (previousOwnerUuid != null) {
            OfflinePlayer previousOwner = Bukkit.getOfflinePlayer(previousOwnerUuid);
            previousOwnerName = previousOwner.getName() != null ? previousOwner.getName() : previousOwnerUuid.toString();
        } else {
            previousOwnerName = plugin.getMessages().get("protection.admin-claim-owner");
        }
        OfflinePlayer newOwner = Bukkit.getOfflinePlayer(newOwnerUuid);
        String newOwnerName = newOwner.getName() != null ? newOwner.getName() : targetName;

        long claimId = claim.getId();
        int subdivisionsTransferred = plugin.getClaimManager().transferClaim(claim, newOwnerUuid);

        var placeholders = ClaimCommandUtil.ph("id", String.valueOf(claimId));
        placeholders.put("previous", previousOwnerName);
        placeholders.put("new", newOwnerName);
        placeholders.put("subdivisions", String.valueOf(subdivisionsTransferred));
        sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.transfer-success", placeholders));

        Player newOwnerOnline = Bukkit.getPlayer(newOwnerUuid);
        if (newOwnerOnline != null) {
            var notifyPh = ClaimCommandUtil.ph("id", String.valueOf(claimId));
            notifyPh.put("previous", previousOwnerName);
            plugin.getMessages().send(newOwnerOnline, "admin.transfer-notify-new-owner", notifyPh);
        }
        return true;
    }

    /** Both positions can be the trailing player-name argument (a single arg is player-only usage,
     *  a second arg is the player name after an explicit claim id), so both suggest player names. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 || args.length == 2) {
            return ClaimCommandUtil.players(args[args.length - 1]);
        }
        return List.of();
    }
}
