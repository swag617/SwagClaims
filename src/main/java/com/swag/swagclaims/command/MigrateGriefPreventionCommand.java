package com.swag.swagclaims.command;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.migration.GriefPreventionImporter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * {@code /swagclaims migrategp [--dry-run] [--source <path>]} — one-time admin import of a live
 * GriefPrevention(+GPFlags) installation's flat-file data into SwagClaims. Dispatched through
 * {@link ClaimCommand}'s subcommand switch (a fresh instance per invocation, matching this
 * plugin's existing house style for admin subcommands), so the "already running" guard below is
 * a static flag shared across every instance rather than a per-instance field.
 *
 * <p>Defaults the source folder to {@code GriefPreventionData} directly inside the server's
 * plugins directory (a sibling of SwagClaims' own plugin folder, resolved via
 * {@code getDataFolder().getParentFile()} — the same "sibling plugin folder" pattern this
 * ecosystem already uses for other hidden migrations). GPFlags' {@code flags.yml} is always
 * resolved the same way (plugins/GPFlags/flags.yml), independent of a custom {@code --source}
 * override, since GPFlags is a separate plugin whose own folder doesn't move with GriefPrevention's.
 */
public class MigrateGriefPreventionCommand implements CommandExecutor {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final SwagClaimsPlugin plugin;

    public MigrateGriefPreventionCommand(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("swagclaims.admin.migrate")) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("general.no-permission"));
            return true;
        }

        boolean dryRun = false;
        String sourceArg = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--dry-run")) {
                dryRun = true;
            } else if (a.equalsIgnoreCase("--source")) {
                if (i + 1 >= args.length) {
                    sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.migrate-usage"));
                    return true;
                }
                sourceArg = args[++i];
            } else {
                sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.migrate-usage"));
                return true;
            }
        }

        if (!RUNNING.compareAndSet(false, true)) {
            sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.migrate-already-running"));
            return true;
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        File sourceFolder = sourceArg != null ? new File(sourceArg) : new File(pluginsDir, "GriefPreventionData");
        File gpFlagsFile = new File(new File(pluginsDir, "GPFlags"), "flags.yml");
        boolean finalDryRun = dryRun;

        sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.migrate-started",
                ClaimCommandUtil.ph("source", sourceFolder.getAbsolutePath())));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            GriefPreventionImporter.Result result;
            try {
                result = new GriefPreventionImporter(plugin).run(sourceFolder, gpFlagsFile, finalDryRun);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "GriefPrevention migration failed unexpectedly", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    RUNNING.set(false);
                    sender.sendMessage(plugin.getMessages().getPrefix() + ChatColor.RED + "Migration failed: " + e.getMessage());
                });
                return;
            }

            GriefPreventionImporter.Result finalResult = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                RUNNING.set(false);
                reportBack(sender, finalResult, finalDryRun);
            });
        });
        return true;
    }

    private void reportBack(CommandSender sender, GriefPreventionImporter.Result result, boolean dryRun) {
        Map<String, String> ph = new HashMap<>();
        ph.put("mode", dryRun ? "DRY RUN" : "LIVE");
        ph.put("claims", String.valueOf(result.claimsImported));
        ph.put("claimsSkipped", String.valueOf(result.claimsSkipped));
        ph.put("trust", String.valueOf(result.trustImported));
        ph.put("trustSkipped", String.valueOf(result.trustSkipped));
        ph.put("playerdata", String.valueOf(result.playerDataImported));
        ph.put("playerdataSkipped", String.valueOf(result.playerDataSkipped));
        ph.put("flags", String.valueOf(result.flagsImported));
        ph.put("flagsSkipped", String.valueOf(result.flagsSkipped));
        ph.put("report", result.reportFile != null ? result.reportFile.getName() : "n/a");

        sender.sendMessage(plugin.getMessages().getPrefix() + plugin.getMessages().get("admin.migrate-complete", ph));

        int shown = Math.min(10, result.warnings.size());
        for (int i = 0; i < shown; i++) {
            sender.sendMessage(ChatColor.YELLOW + " - " + result.warnings.get(i));
        }
        if (result.warnings.size() > shown) {
            sender.sendMessage(ChatColor.GRAY + "... and " + (result.warnings.size() - shown)
                    + " more — see the full report file: " + (result.reportFile != null ? result.reportFile.getName() : "n/a"));
        }
    }
}
