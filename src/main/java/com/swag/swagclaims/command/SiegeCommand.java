package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.SiegeManager;
import com.swag.swagclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** /siege [player] — starts a siege against a claim. See {@link SiegeManager} for the (simplified) rule set. */
public class SiegeCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public SiegeCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player attacker = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (attacker == null) return true;
        if (!attacker.hasPermission("swagclaims.siege")) {
            plugin.getMessages().send(attacker, "general.no-permission");
            return true;
        }

        Player defender;
        if (args.length >= 1) {
            defender = Bukkit.getPlayerExact(args[0]);
            if (defender == null) {
                plugin.getMessages().send(attacker, "general.player-not-found", ClaimCommandUtil.ph("player", args[0]));
                return true;
            }
        } else {
            defender = resolveDefenderFromTarget(attacker);
            if (defender == null) {
                plugin.getMessages().send(attacker, "siege.specify-player");
                return true;
            }
        }

        if (defender.equals(attacker)) {
            plugin.getMessages().send(attacker, "siege.cannot-siege-self");
            return true;
        }

        SiegeManager.SiegeStartResult result = plugin.getSiegeManager().startSiege(attacker, defender);
        switch (result) {
            case SUCCESS -> {
                plugin.getMessages().send(attacker, "siege.started-attacker", ClaimCommandUtil.ph("defender", defender.getName()));
                plugin.getMessages().send(defender, "siege.started-defender", ClaimCommandUtil.ph("attacker", attacker.getName()));
            }
            case WORLD_DISABLED -> plugin.getMessages().send(attacker, "siege.world-disabled");
            case ATTACKER_IN_CLAIM -> plugin.getMessages().send(attacker, "siege.attacker-in-claim");
            case DEFENDER_NOT_IN_CLAIM -> plugin.getMessages().send(attacker, "siege.defender-not-in-claim");
            case CLAIM_NOT_SIEGABLE -> plugin.getMessages().send(attacker, "siege.claim-not-siegable");
            case ALREADY_UNDER_SIEGE -> plugin.getMessages().send(attacker, "siege.already-under-siege");
            case ON_COOLDOWN -> plugin.getMessages().send(attacker, "siege.on-cooldown");
        }
        return true;
    }

    /** Falls back to the owner of the claim the attacker is looking at, when no player name is given. */
    private Player resolveDefenderFromTarget(Player attacker) {
        Block target;
        try {
            target = attacker.getTargetBlockExact(100);
        } catch (Exception e) {
            return null;
        }
        if (target == null) return null;

        ClaimManager claimManager = plugin.getClaimManager();
        Claim claim = claimManager.getClaimAt(target.getLocation());
        if (claim == null) return null;

        UUID ownerUuid = claim.getOwnerUuid();
        return ownerUuid != null ? Bukkit.getPlayer(ownerUuid) : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return new ArrayList<>();
        String partial = args[0].toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(partial))
                .collect(Collectors.toList());
    }
}
