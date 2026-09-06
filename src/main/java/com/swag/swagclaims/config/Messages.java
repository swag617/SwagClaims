package com.swag.swagclaims.config;

import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;

/**
 * Loads messages.yml and resolves {token}-style placeholders. Legacy '&' color codes are
 * translated on every lookup (cheap for a handful of short strings, and keeps this class
 * reload-safe without extra bookkeeping).
 */
public class Messages {

    private final SwagClaimsPlugin plugin;
    private FileConfiguration messages;
    private File file;

    public Messages(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public String getPrefix() {
        return color(messages.getString("prefix", "&8[&2SwagClaims&8]&r "));
    }

    /** Raw (colorized, un-prefixed, placeholder-substituted) message lookup by dotted path. */
    public String get(String path, Map<String, String> placeholders) {
        String raw = messages.getString(path);
        if (raw == null) {
            plugin.getLogger().warning("Missing messages.yml key: " + path);
            return color("&c[missing message: " + path + "]");
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return color(raw);
    }

    public String get(String path) {
        return get(path, null);
    }

    /** Sends the prefixed, colorized, placeholder-substituted message to a player. */
    public void send(Player player, String path, Map<String, String> placeholders) {
        if (player == null) return;
        player.sendMessage(getPrefix() + get(path, placeholders));
    }

    public void send(Player player, String path) {
        send(player, path, null);
    }

    /** Sends a message without the plugin prefix (used for multi-line chat blocks). */
    public void sendRaw(Player player, String path, Map<String, String> placeholders) {
        if (player == null) return;
        player.sendMessage(get(path, placeholders));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
