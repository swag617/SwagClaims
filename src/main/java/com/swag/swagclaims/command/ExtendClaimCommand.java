package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * /extendclaim <amount> <direction> — programmatic alternative to the interactive golden-shovel
 * resize (see {@code ClaimToolListener#handleLeftClick}/{@code handleRightClick}): extends ONE
 * edge of the claim the sender is standing in outward by {@code amount} blocks in the given
 * compass direction (Minecraft convention: north = -Z, south = +Z, east = +X, west = -X), then
 * delegates to the exact same {@link ClaimManager#resizeClaim} the interactive tool uses — so it
 * gets the same overlap check, minimum size floor, and claim-block balance check for free, with no
 * duplicated validation logic. Only works on top-level claims (a subdivision can't be resized
 * through either path this phase — see {@code resizeClaim}'s javadoc) and respects
 * {@link ClaimFlags#NO_RESIZING}, same as the interactive tool.
 */
public class ExtendClaimCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public ExtendClaimCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;

        if (!player.hasPermission("swagclaims.extendclaim")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }
        if (args.length < 2) {
            plugin.getMessages().send(player, "extend.usage");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            plugin.getMessages().send(player, "extend.usage");
            return true;
        }
        if (amount <= 0) {
            plugin.getMessages().send(player, "extend.invalid-amount");
            return true;
        }

        String direction = args[1].toLowerCase(Locale.ROOT);
        if (!direction.equals("north") && !direction.equals("south") && !direction.equals("east") && !direction.equals("west")) {
            plugin.getMessages().send(player, "extend.invalid-direction");
            return true;
        }

        Claim claim = ClaimCommandUtil.requireClaimAt(plugin, player, "extend.not-in-claim");
        if (claim == null) return true;

        if (claim.isSubdivision()) {
            plugin.getMessages().send(player, "extend.not-toplevel");
            return true;
        }
        if (!ClaimCommandUtil.canManage(plugin, player, claim)) {
            plugin.getMessages().send(player, "extend.no-permission-manage");
            return true;
        }
        if (plugin.getFlagManager().isSet(claim, ClaimFlags.NO_RESIZING) && !player.hasPermission("swagclaims.admin.claims")) {
            plugin.getMessages().send(player, "flag.no-resizing");
            return true;
        }

        int minX = claim.getMinX(), maxX = claim.getMaxX(), minZ = claim.getMinZ(), maxZ = claim.getMaxZ();
        switch (direction) {
            case "north" -> minZ -= amount;
            case "south" -> maxZ += amount;
            case "east" -> maxX += amount;
            case "west" -> minX -= amount;
            default -> {
            }
        }

        ClaimManager.ClaimResult result = plugin.getClaimManager().resizeClaim(
                claim, minX, claim.getMinY(), minZ, maxX, claim.getMaxY(), maxZ);

        switch (result.status) {
            case SUCCESS -> plugin.getMessages().send(player, "extend.success");
            case TOO_SMALL_WIDTH -> plugin.getMessages().send(player, "tool.claim-too-small-width",
                    ClaimCommandUtil.ph("min", String.valueOf(plugin.getClaimsConfig().getMinimumWidth())));
            case TOO_SMALL_AREA -> plugin.getMessages().send(player, "tool.claim-too-small-area",
                    ClaimCommandUtil.ph("min", String.valueOf(plugin.getClaimsConfig().getMinimumArea())));
            case OVERLAP -> plugin.getMessages().send(player, "extend.overlap");
            case NOT_ENOUGH_BLOCKS -> plugin.getMessages().send(player, "tool.not-enough-blocks",
                    ClaimCommandUtil.ph("needed", String.valueOf(result.blocksNeeded)));
            default -> plugin.getMessages().send(player, "extend.failed");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2) {
            return ClaimCommandUtil.filter(args[1], List.of("north", "south", "east", "west"));
        }
        return List.of();
    }
}
