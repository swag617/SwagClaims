package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.PlayerClaimData;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /sellclaimblocks <amount> — sells spare (not currently used by any of the player's claims)
 * claim blocks for Vault economy currency. Deducted from bonus blocks first, then accrued blocks,
 * mirroring the abandon-penalty bookkeeping order in {@code ClaimManager#abandonClaim}.
 */
public class SellClaimBlocksCommand implements CommandExecutor {

    private final SwagClaimsPlugin plugin;

    public SellClaimBlocksCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = ClaimCommandUtil.requirePlayer(plugin, sender);
        if (player == null) return true;
        if (!player.hasPermission("swagclaims.sellclaimblocks")) {
            plugin.getMessages().send(player, "general.no-permission");
            return true;
        }
        if (!plugin.getVaultIntegration().hasEconomy()) {
            plugin.getMessages().send(player, "economy.no-economy");
            return true;
        }
        if (args.length < 1) {
            plugin.getMessages().send(player, "economy.sell-usage");
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            plugin.getMessages().send(player, "economy.sell-invalid-amount");
            return true;
        }
        if (amount <= 0) {
            plugin.getMessages().send(player, "economy.sell-invalid-amount");
            return true;
        }

        long remaining = plugin.getClaimManager().getRemainingBlocks(player.getUniqueId());
        if (amount > remaining) {
            plugin.getMessages().send(player, "economy.sell-insufficient-blocks",
                    ClaimCommandUtil.ph("remaining", String.valueOf(remaining)));
            return true;
        }

        double value = amount * plugin.getClaimsConfig().getClaimBlocksSellValue();
        Economy economy = plugin.getVaultIntegration().getEconomy();
        var response = economy.depositPlayer(player, value);
        if (!response.transactionSuccess()) {
            plugin.getMessages().send(player, "economy.no-economy");
            return true;
        }

        PlayerClaimData data = plugin.getClaimManager().getPlayerData(player.getUniqueId());
        long fromBonus = Math.min(amount, data.getBonusBlocks());
        data.setBonusBlocks(data.getBonusBlocks() - fromBonus);
        long fromAccrued = amount - fromBonus;
        if (fromAccrued > 0) {
            data.setAccruedBlocks(data.getAccruedBlocks() - fromAccrued);
        }
        plugin.getDatabaseManager().savePlayerData(data);

        var placeholders = ClaimCommandUtil.ph("amount", String.valueOf(amount));
        placeholders.put("value", economy.format(value));
        plugin.getMessages().send(player, "economy.sell-success", placeholders);
        return true;
    }
}
