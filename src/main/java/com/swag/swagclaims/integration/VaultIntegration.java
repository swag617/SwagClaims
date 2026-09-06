package com.swag.swagclaims.integration;

import com.swag.swagclaims.SwagClaimsPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/**
 * Soft-hooks Vault's {@code Economy} (claim block purchases/sales) and {@code Permission} (real
 * group-membership lookups for group trust) services. Both are optional — Vault itself is a
 * {@code softdepend}, so every caller must be null/false-safe when either service isn't present.
 */
public class VaultIntegration {

    private final SwagClaimsPlugin plugin;
    private Economy economy;
    private Permission permission;

    public VaultIntegration(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
        hook();
    }

    private void hook() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("Vault not found — claim block purchases/sales are disabled and group trust " +
                    "falls back to the group.<name> permission node.");
            return;
        }

        ServicesManager sm = plugin.getServer().getServicesManager();

        RegisteredServiceProvider<Economy> econProv = sm.getRegistration(Economy.class);
        if (econProv != null) {
            economy = econProv.getProvider();
            plugin.getLogger().info("Hooked Vault economy: " + economy.getName());
        } else {
            plugin.getLogger().warning("Vault is present but no Economy provider is registered — " +
                    "/buyclaimblocks and /sellclaimblocks are disabled.");
        }

        RegisteredServiceProvider<Permission> permProv = sm.getRegistration(Permission.class);
        if (permProv != null) {
            permission = permProv.getProvider();
            plugin.getLogger().info("Hooked Vault permission: " + permission.getName());
        } else {
            plugin.getLogger().warning("Vault is present but no Permission provider is registered — " +
                    "group trust falls back to the group.<name> permission node.");
        }
    }

    public boolean hasEconomy() {
        return economy != null;
    }

    public Economy getEconomy() {
        return economy;
    }

    public boolean hasPermission() {
        return permission != null;
    }

    /**
     * True if the player is a member of {@code groupName}, via Vault's Permission service when
     * available; otherwise falls back to the "group.&lt;name&gt;" permission-node convention.
     */
    public boolean isInGroup(Player player, String groupName) {
        if (permission != null) {
            try {
                return permission.playerInGroup(player, groupName);
            } catch (Exception e) {
                plugin.getLogger().warning("Vault permission provider threw while checking group '"
                        + groupName + "': " + e.getMessage());
            }
        }
        return player.hasPermission("group." + groupName.toLowerCase());
    }

    /**
     * Every group name Vault's Permission provider currently knows about (e.g. every LuckPerms
     * group on the live server) — used by the rank-expiration web settings page to populate its
     * rank dropdown without hardcoding a fixed rank list. Verified against the real VaultAPI 1.7.1
     * jar via {@code javap}: {@code Permission#getGroups()} is a non-deprecated, no-arg abstract
     * method. Returns an empty array (never null) if Permission isn't hooked, or if the provider
     * throws.
     */
    public String[] getGroups() {
        if (permission != null) {
            try {
                String[] groups = permission.getGroups();
                return groups != null ? groups : new String[0];
            } catch (Exception e) {
                plugin.getLogger().warning("Vault permission provider threw while listing groups: " + e.getMessage());
            }
        }
        return new String[0];
    }

    /**
     * Resolves a possibly-offline player's primary group, or null if it can't be resolved
     * (Permission not hooked, provider threw, or the provider itself returned null/blank).
     *
     * <p>Verified against the real VaultAPI 1.7.1 jar via {@code javap}: of the four
     * {@code getPrimaryGroup} overloads, only {@code getPrimaryGroup(String world, OfflinePlayer)}
     * and {@code getPrimaryGroup(Player)} are non-deprecated; the {@code (String world,
     * String playerName)} overload this class's {@link #isInGroup} sibling method could have used
     * instead is marked {@code @Deprecated}. The world argument is passed as {@code null} —
     * there's no "current world" for a possibly-offline player, and every Vault/LuckPerms bridge
     * in this ecosystem treats a null world as a global (non-per-world) lookup.
     */
    public String getPrimaryGroup(OfflinePlayer player) {
        if (permission != null && player != null) {
            try {
                String group = permission.getPrimaryGroup(null, player);
                return (group != null && !group.isBlank()) ? group : null;
            } catch (Exception e) {
                plugin.getLogger().warning("Vault permission provider threw while resolving primary group for "
                        + player.getUniqueId() + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Offline-safe permission check via Vault's Permission service, used by
     * {@code ExpirationManager}'s bypass-permission check. Returns false (never throws) if
     * Permission isn't hooked — callers must treat that as "this specific bypass check can't be
     * evaluated, skip it gracefully," not "the player was checked and denied."
     *
     * <p>Verified against the real VaultAPI 1.7.1 jar via {@code javap}:
     * {@code playerHas(String world, OfflinePlayer, String)} is non-deprecated (unlike the
     * {@code (String world, String playerName, String)} overload). Same null-world convention as
     * {@link #getPrimaryGroup(OfflinePlayer)} above.
     */
    public boolean hasPermissionOffline(OfflinePlayer player, String permissionNode) {
        if (permission != null && player != null && permissionNode != null && !permissionNode.isBlank()) {
            try {
                return permission.playerHas(null, player, permissionNode);
            } catch (Exception e) {
                plugin.getLogger().warning("Vault permission provider threw while checking permission '"
                        + permissionNode + "' for " + player.getUniqueId() + ": " + e.getMessage());
            }
        }
        return false;
    }
}
