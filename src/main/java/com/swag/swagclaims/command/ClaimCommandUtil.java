package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared helpers for every SwagClaims command class — permission checks, target resolution, messaging. */
public final class ClaimCommandUtil {

    private ClaimCommandUtil() {
    }

    /** Returns the sender as a Player, or sends the "players-only" message and returns null. */
    public static Player requirePlayer(SwagClaimsPlugin plugin, CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.players-only"));
        return null;
    }

    /** Returns the claim the player is standing in, or sends {@code notInClaimKey} and returns null. */
    public static Claim requireClaimAt(SwagClaimsPlugin plugin, Player player, String notInClaimKey) {
        Claim claim = plugin.getClaimManager().getClaimAt(player.getLocation());
        if (claim == null) {
            plugin.getMessages().send(player, notInClaimKey);
            return null;
        }
        return claim;
    }

    /** True if the player may manage (grant/revoke trust, resize, abandon) this claim. */
    public static boolean canManage(SwagClaimsPlugin plugin, Player player, Claim claim) {
        if (player.hasPermission("swagclaims.admin.claims")) return true;
        if (claim.isAdminClaim()) return false; // only admin.claims bypass manages admin claims
        if (claim.getOwnerUuid() != null && claim.getOwnerUuid().equals(player.getUniqueId())) return true;
        ClaimManager claimManager = plugin.getClaimManager();
        TrustLevel effective = claimManager.resolveEffectiveTrust(claim, player);
        return effective != null && effective.implies(TrustLevel.MANAGE);
    }

    /**
     * True if the player may abandon (permanently delete) this claim. Deliberately stricter than
     * {@link #canManage} — a MANAGE-trusted player can grant/revoke trust on someone else's claim,
     * but only the actual owner (or an admin.claims bypass) can delete it outright.
     */
    public static boolean canAbandon(SwagClaimsPlugin plugin, Player player, Claim claim) {
        if (player.hasPermission("swagclaims.admin.claims")) return true;
        return claim.getOwnerUuid() != null && claim.getOwnerUuid().equals(player.getUniqueId());
    }

    /** Resolves a player name to a UUID (online exact match first, then any known offline player). */
    public static UUID resolveTargetUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();

        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return offline.getUniqueId();
        }
        return null;
    }

    public static Map<String, String> ph(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * Shared implementation for /trust, /containertrust and /accesstrust — the only difference
     * between the three commands is which {@link TrustLevel} they grant.
     */
    public static void handleTrustGrant(SwagClaimsPlugin plugin, CommandSender sender, String label,
                                         String[] args, TrustLevel level) {
        Player player = requirePlayer(plugin, sender);
        if (player == null) return;
        if (!player.hasPermission("swagclaims.trust")) {
            plugin.getMessages().send(player, "general.no-permission");
            return;
        }
        if (args.length < 1) {
            plugin.getMessages().send(player, "trust.usage", ph("command", label));
            return;
        }

        Claim claim = requireClaimAt(plugin, player, "trust.not-in-claim");
        if (claim == null) return;

        if (!canManage(plugin, player, claim)) {
            plugin.getMessages().send(player, "trust.no-permission-manage");
            return;
        }

        String targetName = args[0];
        UUID targetUuid;
        if (targetName.equalsIgnoreCase("public")) {
            targetUuid = null; // handled specially below
        } else {
            targetUuid = resolveTargetUuid(targetName);
            if (targetUuid == null) {
                plugin.getMessages().send(player, "general.player-not-found", ph("player", targetName));
                return;
            }
            if (targetUuid.equals(player.getUniqueId())) {
                plugin.getMessages().send(player, "trust.cannot-trust-self");
                return;
            }
        }

        String target = targetUuid != null ? targetUuid.toString() : Claim.PUBLIC_TARGET;
        String displayName = targetUuid != null ? Bukkit.getOfflinePlayer(targetUuid).getName() : "everyone";
        if (displayName == null) displayName = targetName;

        plugin.getClaimManager().grantTrust(claim, target, level);

        Map<String, String> placeholders = ph("target", displayName);
        placeholders.put("level", level.name());
        plugin.getMessages().send(player, "trust.granted", placeholders);
    }

    /** Shared implementation for /untrust. */
    public static void handleUntrust(SwagClaimsPlugin plugin, CommandSender sender, String label, String[] args) {
        Player player = requirePlayer(plugin, sender);
        if (player == null) return;
        if (!player.hasPermission("swagclaims.trust")) {
            plugin.getMessages().send(player, "general.no-permission");
            return;
        }
        if (args.length < 1) {
            plugin.getMessages().send(player, "trust.usage", ph("command", label));
            return;
        }

        Claim claim = requireClaimAt(plugin, player, "trust.not-in-claim");
        if (claim == null) return;

        if (!canManage(plugin, player, claim)) {
            plugin.getMessages().send(player, "trust.no-permission-manage");
            return;
        }

        String targetName = args[0];
        String target;
        String displayName;
        if (targetName.equalsIgnoreCase("public")) {
            target = Claim.PUBLIC_TARGET;
            displayName = "everyone";
        } else {
            UUID targetUuid = resolveTargetUuid(targetName);
            if (targetUuid == null) {
                plugin.getMessages().send(player, "general.player-not-found", ph("player", targetName));
                return;
            }
            target = targetUuid.toString();
            displayName = Bukkit.getOfflinePlayer(targetUuid).getName();
            if (displayName == null) displayName = targetName;
        }

        if (claim.getTrust(target) == null) {
            plugin.getMessages().send(player, "trust.not-trusted", ph("target", displayName));
            return;
        }

        plugin.getClaimManager().revokeTrust(claim, target);
        plugin.getMessages().send(player, "trust.revoked", ph("target", displayName));
    }

    /**
     * Sends the full claim-info chat block (owner/admin line, size, type, trust list) to a
     * player, plus a 10-second boundary visualization. Shared by /claiminfo and the
     * investigation-tool right-click handler so the two stay in sync.
     */
    public static void sendClaimInfo(SwagClaimsPlugin plugin, Player player, Claim claim) {
        plugin.getMessages().sendRaw(player, "tool.investigate-header", null);

        if (claim.isAdminClaim()) {
            plugin.getMessages().sendRaw(player, "tool.investigate-admin-claim", null);
        } else {
            var offline = Bukkit.getOfflinePlayer(claim.getOwnerUuid());
            String ownerName = offline.getName() != null ? offline.getName() : claim.getOwnerUuid().toString();
            plugin.getMessages().sendRaw(player, "tool.investigate-owner", ph("owner", ownerName));
        }

        Map<String, String> sizePh = new HashMap<>();
        sizePh.put("width", String.valueOf(claim.getWidthX()));
        sizePh.put("length", String.valueOf(claim.getWidthZ()));
        sizePh.put("area", String.valueOf(claim.getArea()));
        plugin.getMessages().sendRaw(player, "tool.investigate-size", sizePh);

        plugin.getMessages().sendRaw(player, "tool.investigate-type", ph("type", claim.getClaimType().name()));

        if (claim.getTrustMap().isEmpty()) {
            plugin.getMessages().sendRaw(player, "tool.investigate-trust-none", null);
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, TrustLevel> entry : claim.getTrustMap().entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(describeTarget(plugin, entry.getKey())).append(" (").append(entry.getValue().name()).append(")");
            }
            plugin.getMessages().sendRaw(player, "tool.investigate-trust", ph("trusted", sb.toString()));
        }

        plugin.getClaimVisualizer().show(player, claim);
    }

    /** Renders a trust "target" string ({@code public}, {@code g:<group>}, or a UUID) as a display name. */
    public static String describeTarget(SwagClaimsPlugin plugin, String target) {
        if (target.equals(Claim.PUBLIC_TARGET)) return "everyone";
        if (target.startsWith(Claim.GROUP_TARGET_PREFIX)) return "group " + target.substring(Claim.GROUP_TARGET_PREFIX.length());
        try {
            UUID uuid = UUID.fromString(target);
            var offline = Bukkit.getOfflinePlayer(uuid);
            return offline.getName() != null ? offline.getName() : target;
        } catch (IllegalArgumentException e) {
            return target;
        }
    }
}
