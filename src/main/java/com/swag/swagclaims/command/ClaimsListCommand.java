package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.gui.ClaimListGUI;
import com.swag.swagclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /claimslist [player|admin] [--gui] — lists a player's top-level claims in chat, or opens
 * {@link ClaimListGUI} instead when {@code --gui} is passed (any position). {@code admin} in the
 * player-name slot opens/lists the admin-claims-only view instead of a specific player's claims,
 * gated by {@code swagclaims.admin.claims} same as every other admin-claims feature.
 */
public class ClaimsListCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public ClaimsListCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] rawArgs) {
        boolean gui = false;
        List<String> args = new ArrayList<>();
        for (String arg : rawArgs) {
            if (arg.equalsIgnoreCase("--gui")) {
                gui = true;
            } else {
                args.add(arg);
            }
        }

        if (!args.isEmpty() && args.get(0).equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("swagclaims.admin.claims")) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
                return true;
            }
            if (gui) {
                Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
                if (player == null) return true;
                ClaimListGUI.forAdmin(plugin, player).open();
                return true;
            }
            sendAdminClaimsList(sender);
            return true;
        }

        UUID targetUuid;
        String targetName;

        if (!args.isEmpty()) {
            if (!sender.hasPermission("swagclaims.claimslist.others")
                    && !(sender instanceof Player p && p.getName().equalsIgnoreCase(args.get(0)))) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
                return true;
            }
            targetUuid = ClaimCommandUtil.resolveTargetUuid(args.get(0));
            if (targetUuid == null) {
                sender.sendMessage(plugin.getMessages().getPrefix()
                        + plugin.getMessages().get("general.player-not-found", ClaimCommandUtil.ph("player", args.get(0))));
                return true;
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetUuid);
            targetName = offline.getName() != null ? offline.getName() : args.get(0);
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.players-only"));
                return true;
            }
            if (!sender.hasPermission("swagclaims.claimslist")) {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
                return true;
            }
            targetUuid = player.getUniqueId();
            targetName = player.getName();
        }

        if (gui) {
            Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
            if (player == null) return true;
            if (targetUuid.equals(player.getUniqueId())) {
                ClaimListGUI.forOwn(plugin, player).open();
            } else {
                ClaimListGUI.forOther(plugin, player, targetUuid, targetName).open();
            }
            return true;
        }

        List<Claim> claims = plugin.getClaimManager().getClaimsByOwner(targetUuid).stream()
                .filter(Claim::isTopLevel)
                .collect(Collectors.toList());

        long area = claims.stream().mapToLong(Claim::getArea).sum();

        sender.sendMessage(plugin.getMessages().get("list.header"));
        Map<String, String> titlePh = new HashMap<>();
        titlePh.put("player", targetName);
        titlePh.put("count", String.valueOf(claims.size()));
        titlePh.put("area", String.valueOf(area));
        sender.sendMessage(plugin.getMessages().get("list.title", titlePh));

        if (claims.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("list.none"));
        } else {
            for (Claim claim : claims) {
                Map<String, String> ph = new HashMap<>();
                ph.put("world", claim.getWorld());
                ph.put("minx", String.valueOf(claim.getMinX()));
                ph.put("minz", String.valueOf(claim.getMinZ()));
                ph.put("maxx", String.valueOf(claim.getMaxX()));
                ph.put("maxz", String.valueOf(claim.getMaxZ()));
                ph.put("type", claim.getClaimType().name());
                sender.sendMessage(plugin.getMessages().get("list.entry", ph));
            }
        }
        sender.sendMessage(plugin.getMessages().get("list.header"));
        return true;
    }

    /** Chat listing for {@code /claimslist admin} — same layout as the per-player listing above. */
    private void sendAdminClaimsList(CommandSender sender) {
        List<Claim> claims = plugin.getClaimManager().getAllClaims().stream()
                .filter(Claim::isTopLevel)
                .filter(Claim::isAdminClaim)
                .collect(Collectors.toList());
        long area = claims.stream().mapToLong(Claim::getArea).sum();

        sender.sendMessage(plugin.getMessages().get("list.header"));
        Map<String, String> titlePh = new HashMap<>();
        titlePh.put("player", "Administrator");
        titlePh.put("count", String.valueOf(claims.size()));
        titlePh.put("area", String.valueOf(area));
        sender.sendMessage(plugin.getMessages().get("list.title", titlePh));

        if (claims.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("list.none"));
        } else {
            for (Claim claim : claims) {
                Map<String, String> ph = new HashMap<>();
                ph.put("world", claim.getWorld());
                ph.put("minx", String.valueOf(claim.getMinX()));
                ph.put("minz", String.valueOf(claim.getMinZ()));
                ph.put("maxx", String.valueOf(claim.getMaxX()));
                ph.put("maxz", String.valueOf(claim.getMaxZ()));
                ph.put("type", claim.getClaimType().name());
                sender.sendMessage(plugin.getMessages().get("list.entry", ph));
            }
        }
        sender.sendMessage(plugin.getMessages().get("list.header"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return new ArrayList<>();
        String partial = args[0].toLowerCase();
        List<String> suggestions = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toCollection(ArrayList::new));
        suggestions.add("admin");
        suggestions.add("--gui");
        return suggestions.stream()
                .filter(n -> n.toLowerCase().startsWith(partial))
                .collect(Collectors.toList());
    }
}
