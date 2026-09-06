package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /abandonallclaims — deletes every claim the sender owns, after a confirmation step. */
public class AbandonAllClaimsCommand implements CommandExecutor {

    private static final long CONFIRM_WINDOW_MILLIS = 10_000L;

    private final SwagClaimsPlugin plugin;
    private final Map<UUID, Long> pendingConfirmation = new HashMap<>();

    public AbandonAllClaimsCommand(SwagClaimsPlugin plugin) {
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

        List<Claim> owned = plugin.getClaimManager().getClaimsByOwner(player.getUniqueId());
        long topLevelCount = owned.stream().filter(Claim::isTopLevel).count();
        if (topLevelCount == 0) {
            plugin.getMessages().send(player, "abandon.no-claims");
            return true;
        }

        Long requestedAt = pendingConfirmation.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (requestedAt == null || (now - requestedAt) > CONFIRM_WINDOW_MILLIS) {
            pendingConfirmation.put(player.getUniqueId(), now);
            plugin.getMessages().send(player, "abandon.confirm-all", ClaimCommandUtil.ph("count", String.valueOf(topLevelCount)));
            return true;
        }

        pendingConfirmation.remove(player.getUniqueId());
        long returned = plugin.getClaimManager().abandonAllClaims(player.getUniqueId());

        Map<String, String> placeholders = ClaimCommandUtil.ph("count", String.valueOf(topLevelCount));
        placeholders.put("returned", String.valueOf(returned));
        plugin.getMessages().send(player, "abandon.all-success", placeholders);
        return true;
    }
}
