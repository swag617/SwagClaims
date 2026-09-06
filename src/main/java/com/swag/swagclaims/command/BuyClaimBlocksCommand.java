package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.PlayerClaimData;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /buyclaimblocks <amount> — purchases claim blocks with Vault economy currency, credited as bonus blocks. */
public class BuyClaimBlocksCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public BuyClaimBlocksCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;
        if (!player.hasPermission("swagclaims.buyclaimblocks")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }
        if (!plugin.getVaultIntegration().hasEconomy()) {
            plugin.getMessages().send(player, "economy.no-economy");
            return true;
        }
        if (args.length < 1) {
            plugin.getMessages().send(player, "economy.buy-usage");
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            plugin.getMessages().send(player, "economy.buy-invalid-amount");
            return true;
        }
        if (amount <= 0) {
            plugin.getMessages().send(player, "economy.buy-invalid-amount");
            return true;
        }

        double cost = amount * plugin.getClaimsConfig().getClaimBlocksPurchaseCost();
        Economy economy = plugin.getVaultIntegration().getEconomy();

        if (!economy.has(player, cost)) {
            plugin.getMessages().send(player, "economy.buy-insufficient-funds",
                    ClaimCommandUtil.ph("cost", economy.format(cost)));
            return true;
        }

        var response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            plugin.getMessages().send(player, "economy.buy-insufficient-funds",
                    ClaimCommandUtil.ph("cost", economy.format(cost)));
            return true;
        }

        PlayerClaimData data = plugin.getClaimManager().getPlayerData(player.getUniqueId());
        data.setBonusBlocks(data.getBonusBlocks() + amount);
        plugin.getDatabaseManager().savePlayerData(data);

        var placeholders = ClaimCommandUtil.ph("amount", String.valueOf(amount));
        placeholders.put("cost", economy.format(cost));
        plugin.getMessages().send(player, "economy.buy-success", placeholders);
        return true;
    }
}
