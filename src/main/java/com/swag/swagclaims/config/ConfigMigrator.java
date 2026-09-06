package com.swag.swagclaims.config;

import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Backfills config.yml keys that a later phase introduced into an already-existing install.
 * {@code JavaPlugin#saveDefaultConfig()} only ever writes the file when it's missing entirely, so
 * without this, upgrading the jar on a server with a pre-existing config.yml would silently leave
 * new keys (like this phase's {@code titles.*} section) completely absent — every one of this
 * class's callers would then see {@code getString(...)}'s hardcoded fallback forever, since that
 * fallback is only used when the key is unset, and it'd stay unset with no on-disk trace of it.
 *
 * <p>Deliberately simple per the phase spec: a recursive "copy any default key missing on disk"
 * merge, no version numbering. Existing on-disk values (and their comments, since this rewrites
 * the whole file when anything changed) are preserved as-is.
 */
public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    /** Call once in onEnable, after {@code saveDefaultConfig()} and before anything reads config values. */
    public static void migrate(SwagClaimsPlugin plugin) {
        try {
            File onDiskFile = new File(plugin.getDataFolder(), "config.yml");
            if (!onDiskFile.exists()) return; // saveDefaultConfig() just wrote a brand-new, already-complete file

            YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(onDiskFile);

            InputStream defaultStream = plugin.getResource("config.yml");
            if (defaultStream == null) return;
            YamlConfiguration defaults;
            try (InputStreamReader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
                defaults = YamlConfiguration.loadConfiguration(reader);
            }

            if (mergeMissing(defaults, onDisk)) {
                onDisk.save(onDiskFile);
                plugin.getLogger().info("SwagClaims config.yml was missing newer default keys — merged them in.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to migrate config.yml defaults (continuing with what's on disk)", e);
        }
    }

    /** Recursively copies any key present in {@code defaults} but absent from {@code target}. Returns true if anything changed. */
    private static boolean mergeMissing(ConfigurationSection defaults, ConfigurationSection target) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            Object defaultValue = defaults.get(key);
            if (defaultValue instanceof ConfigurationSection defaultSection) {
                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection == null) {
                    targetSection = target.createSection(key);
                    changed = true;
                }
                changed |= mergeMissing(defaultSection, targetSection);
            } else if (!target.isSet(key)) {
                target.set(key, defaultValue);
                changed = true;
            }
        }
        return changed;
    }
}
