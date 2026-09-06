package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.gui.ClaimFlagGUI;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import com.swag.swagclaims.model.FlagValue;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /claimflag [flag] [params] — toggles or configures a GPFlags-style flag on the claim the
 * sender is standing in. No args opens the {@link ClaimFlagGUI} panel (a QoL alternative to
 * typing exact flag keys/params — the text form below is unchanged for power users);
 * {@code off}/{@code false} as the params argument explicitly clears a flag rather than toggling
 * it. Requires MANAGE trust (or ownership/admin bypass), matching every other claim-management
 * command in this plugin.
 */
public class ClaimFlagCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public ClaimFlagCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;

        if (!player.hasPermission("swagclaims.flag.use")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }

        Claim claim = ClaimCommandUtil.requireClaimAt(plugin, player, "flag.not-in-claim");
        if (claim == null) return true;

        if (!ClaimCommandUtil.canManage(plugin, player, claim)) {
            plugin.getMessages().send(player, "flag.no-permission-manage");
            return true;
        }

        FlagManager flagManager = plugin.getFlagManager();

        if (args.length == 0) {
            new ClaimFlagGUI(plugin, player, claim).open();
            return true;
        }

        // Text-list escape hatch for anyone who preferred the old chat output over the GUI.
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sendFlagList(player, claim);
            return true;
        }

        String key = args[0].toLowerCase();
        if (!ClaimFlags.isKnown(key)) {
            plugin.getMessages().send(player, "flag.unknown", ClaimCommandUtil.ph("flag", args[0]));
            return true;
        }

        if (args.length == 1) {
            toggleFlag(player, claim, flagManager, key);
            return true;
        }

        String paramsArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (paramsArg.equalsIgnoreCase("off") || paramsArg.equalsIgnoreCase("false")) {
            flagManager.removeClaimFlag(claim, key);
            plugin.getMessages().send(player, "flag.toggled-off", ClaimCommandUtil.ph("flag", key));
            return true;
        }

        flagManager.setClaimFlag(claim, key, paramsArg, true);
        var placeholders = ClaimCommandUtil.ph("flag", key);
        placeholders.put("params", paramsArg);
        plugin.getMessages().send(player, "flag.set-with-params", placeholders);
        return true;
    }

    private void toggleFlag(Player player, Claim claim, FlagManager flagManager, String key) {
        FlagValue current = claim.getFlag(key);
        if (current == null || !current.isValue()) {
            flagManager.setClaimFlag(claim, key, current != null ? current.getParams() : null, true);
            plugin.getMessages().send(player, "flag.toggled-on", ClaimCommandUtil.ph("flag", key));
        } else {
            flagManager.removeClaimFlag(claim, key);
            plugin.getMessages().send(player, "flag.toggled-off", ClaimCommandUtil.ph("flag", key));
        }
    }

    private void sendFlagList(Player player, Claim claim) {
        plugin.getMessages().sendRaw(player, "flag.list-header", null);
        for (String key : ClaimFlags.ALL) {
            FlagValue value = claim.getFlag(key);
            String status;
            if (value == null || !value.isValue()) {
                status = "&7off";
            } else if (value.getParams() != null && !value.getParams().isEmpty()) {
                status = "&aon &7(" + value.getParams() + ")";
            } else {
                status = "&aon";
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&f" + key + "&7: " + status));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>(ClaimFlags.ALL);
            suggestions.add("list");
            return suggestions.stream().filter(f -> f.startsWith(partial)).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String key = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>(List.of("off"));
            switch (key) {
                case ClaimFlags.PLAYER_TIME -> suggestions.addAll(List.of("day", "night", "noon", "midnight"));
                case ClaimFlags.PLAYER_WEATHER -> suggestions.addAll(List.of("sun", "rain"));
                case ClaimFlags.KEEP_INVENTORY -> suggestions.addAll(List.of("true", "false"));
                default -> {
                }
            }
            String partial = args[1].toLowerCase();
            return suggestions.stream().filter(s -> s.startsWith(partial)).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
