package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main /claim (aliases /swagclaims, /sc) command. Every subcommand here mirrors one of the
 * standalone commands (/trust, /abandonclaim, etc.) registered in plugin.yml — both entry
 * points share the exact same {@link ClaimCommandUtil} implementation, so behavior never
 * drifts between "/claim trust Steve" and "/trust Steve".
 */
public class ClaimCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public ClaimCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "help" -> sendHelp(sender);

            case "info" -> new ClaimInfoCommand(plugin).onCommand(sender, command, "claiminfo", rest);

            case "list" -> new ClaimsListCommand(plugin).onCommand(sender, command, "claimslist", rest);

            case "trust" -> ClaimCommandUtil.handleTrustGrant(plugin, sender, "trust", rest, TrustLevel.BUILD);

            case "untrust" -> ClaimCommandUtil.handleUntrust(plugin, sender, "untrust", rest);

            case "containertrust" ->
                    ClaimCommandUtil.handleTrustGrant(plugin, sender, "containertrust", rest, TrustLevel.CONTAINER);

            case "accesstrust" ->
                    ClaimCommandUtil.handleTrustGrant(plugin, sender, "accesstrust", rest, TrustLevel.ACCESS);

            case "abandon", "abandonclaim" -> new AbandonClaimCommand(plugin).onCommand(sender, command, "abandonclaim", rest);

            case "abandontoplevel", "abandontoplevelclaim" ->
                    new AbandonTopLevelClaimCommand(plugin).onCommand(sender, command, "abandontoplevelclaim", rest);

            case "abandonall", "abandonallclaims" ->
                    new AbandonAllClaimsCommand(plugin).onCommand(sender, command, "abandonallclaims", rest);

            case "reload" -> handleReload(sender);

            case "migrategp" -> new MigrateGriefPreventionCommand(plugin).onCommand(sender, command, "migrategp", rest);

            default -> {
                Player player = sender instanceof Player p ? p : null;
                if (player != null) {
                    plugin.getMessages().send(player, "general.unknown-subcommand");
                } else {
                    sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /claim help.");
                }
            }
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("swagclaims.admin.reload")) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
            return;
        }
        plugin.reloadClaimsConfig();
        sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.reloaded"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "SwagClaims Commands");
        sender.sendMessage(ChatColor.YELLOW + "/claim info " + ChatColor.GRAY + "- Show info about the claim you're standing in");
        sender.sendMessage(ChatColor.YELLOW + "/claim list [player] " + ChatColor.GRAY + "- List claims");
        sender.sendMessage(ChatColor.YELLOW + "/trust <player> " + ChatColor.GRAY + "- Grant build trust");
        sender.sendMessage(ChatColor.YELLOW + "/containertrust <player> " + ChatColor.GRAY + "- Grant container trust");
        sender.sendMessage(ChatColor.YELLOW + "/accesstrust <player> " + ChatColor.GRAY + "- Grant access trust");
        sender.sendMessage(ChatColor.YELLOW + "/untrust <player> " + ChatColor.GRAY + "- Revoke trust");
        sender.sendMessage(ChatColor.YELLOW + "/abandonclaim " + ChatColor.GRAY + "- Delete the claim you're standing in");
        sender.sendMessage(ChatColor.YELLOW + "/abandontoplevelclaim " + ChatColor.GRAY + "- Delete a claim and its subdivisions");
        sender.sendMessage(ChatColor.YELLOW + "/abandonallclaims " + ChatColor.GRAY + "- Delete every claim you own");
        if (sender.hasPermission("swagclaims.admin.reload")) {
            sender.sendMessage(ChatColor.RED + "/claim reload " + ChatColor.GRAY + "- Reload configuration (admin)");
        }
        if (sender.hasPermission("swagclaims.admin.migrate")) {
            sender.sendMessage(ChatColor.RED + "/swagclaims migrategp [--dry-run] [--source <path>] "
                    + ChatColor.GRAY + "- Import GriefPrevention data (admin)");
        }
    }

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "help", "info", "list", "trust", "untrust", "containertrust", "accesstrust",
            "abandon", "abandontoplevel", "abandonall", "reload", "migrategp");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
