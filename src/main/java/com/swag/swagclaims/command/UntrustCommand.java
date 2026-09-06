package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** /untrust <player> — removes a player's (or "public") trust from the claim the sender is standing in. */
public class UntrustCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public UntrustCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ClaimCommandUtil.handleUntrust(plugin, sender, label, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return new ArrayList<>();
        if (!(sender instanceof Player player)) return new ArrayList<>();

        var claim = plugin.getClaimManager().getClaimAt(player.getLocation());
        List<String> names = new ArrayList<>();
        if (claim != null) {
            for (String target : claim.getTrustMap().keySet()) {
                if (target.equals("public")) {
                    names.add("public");
                } else if (!target.startsWith("g:")) {
                    try {
                        String name = Bukkit.getOfflinePlayer(java.util.UUID.fromString(target)).getName();
                        if (name != null) names.add(name);
                    } catch (IllegalArgumentException ignored) {
                        // not a UUID (shouldn't happen for player targets)
                    }
                }
            }
        }
        String partial = args[0].toLowerCase();
        return names.stream().filter(n -> n.toLowerCase().startsWith(partial)).collect(Collectors.toList());
    }
}
