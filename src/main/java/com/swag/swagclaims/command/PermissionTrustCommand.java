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

/**
 * /permissiontrust <player> (alias /pt) — GriefPrevention's real permission-trust command: grants
 * a player the ability to manage OTHER players' trust levels on the claim (run /trust,
 * /containertrust, /accesstrust and /untrust on it themselves), distinct from plain build/container
 * trust. In real GriefPrevention this sits above Build trust as its own privilege; in this
 * codebase's trust ladder that's exactly what {@link TrustLevel#MANAGE} already means — see its
 * javadoc ("BUILD, plus can manage the claim itself: grant/revoke trust, resize, subdivide") and
 * {@link ClaimCommandUtil#canManage}, which already gates every trust grant/revoke on holding at
 * least MANAGE. So /permissiontrust is implemented as a thin alias that grants MANAGE trust,
 * reusing the exact same shared helper /trust, /containertrust and /accesstrust already use — no
 * separate trust tier or new permission node needed (matches those three, which also share the
 * single "swagclaims.trust" permission node and rely on canManage for the per-claim gate).
 */
public class PermissionTrustCommand implements CommandExecutor, TabCompleter {

    private final SwagClaimsPlugin plugin;

    public PermissionTrustCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ClaimCommandUtil.handleTrustGrant(plugin, sender, label, args, TrustLevel.MANAGE);
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
