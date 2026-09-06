package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** /accesstrust <player> — grants ACCESS trust in the claim the sender is standing in. */
public class AccessTrustCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public AccessTrustCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ClaimCommandUtil.handleTrustGrant(plugin, sender, label, args, TrustLevel.ACCESS);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return new ArrayList<>();
        String partial = args[0].toLowerCase();
        List<String> names = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList());
        names.add("public");
        return names.stream().filter(n -> n.toLowerCase().startsWith(partial)).collect(Collectors.toList());
    }
}
