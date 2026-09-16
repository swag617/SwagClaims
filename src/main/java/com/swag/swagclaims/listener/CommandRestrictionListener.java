package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

/**
 * Enforces the five claim flags that gate a specific external command by name rather than a real
 * Bukkit event: {@link ClaimFlags#NO_HOMES_SET} ({@code /sethome}),
 * {@link ClaimFlags#NO_WARPS_SET} ({@code /setwarp}), {@link ClaimFlags#NO_PLAYER_WARPS}
 * ({@code /playerwarps}/{@code /pwarp}), {@link ClaimFlags#NO_BACK} ({@code /back}), and
 * {@link ClaimFlags#NO_TOP} ({@code /top}).
 *
 * <p><b>Documented limitation</b> (see also {@code ClaimFlags}' class javadoc): SwagCore, which
 * owns {@code /sethome}, {@code /setwarp}, {@code /back} and {@code /top} in this ecosystem, fires
 * no dedicated Bukkit event for any of these actions — confirmed by inspecting SwagCore's source,
 * which registers each as a plain command with no custom event class anywhere. No
 * "PlayerWarps"-style plugin exists anywhere in this ecosystem either. Command-name interception
 * (this class) is therefore the only available hook: it only catches the literal command typed
 * (not, say, an equivalent GUI button elsewhere in SwagCore), it can't distinguish "setting home
 * named X" from "setting home named Y" — it blocks the whole command outright — and it silently
 * stops working if SwagCore ever renames these commands. This is a deliberate, documented
 * trade-off given no cleaner hook exists, not an oversight.
 */
public class CommandRestrictionListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    public CommandRestrictionListener(SwagClaimsPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("swagclaims.admin.ignoreclaims")) return;

        String label = firstToken(event.getMessage());
        String flagKey = switch (label) {
            case "sethome" -> ClaimFlags.NO_HOMES_SET;
            case "setwarp" -> ClaimFlags.NO_WARPS_SET;
            case "playerwarps", "pwarp", "pw" -> ClaimFlags.NO_PLAYER_WARPS;
            case "back" -> ClaimFlags.NO_BACK;
            case "top" -> ClaimFlags.NO_TOP;
            default -> null;
        };
        if (flagKey == null) return;

        Location loc = player.getLocation();
        Claim claim = claimManager.getClaimAt(loc);
        if (claim == null) return;

        FlagManager flagManager = plugin.getFlagManager();
        if (flagManager.isSetAt(loc, flagKey)) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "flag.command-blocked");
        }
    }

    /** Command label without the leading slash, plugin-name prefix, arguments, or case — e.g. "/SwagCore:SetHome bob" -> "sethome". */
    private String firstToken(String rawMessage) {
        String withoutSlash = rawMessage.startsWith("/") ? rawMessage.substring(1) : rawMessage;
        int spaceIdx = withoutSlash.indexOf(' ');
        String label = spaceIdx >= 0 ? withoutSlash.substring(0, spaceIdx) : withoutSlash;
        int colonIdx = label.indexOf(':');
        if (colonIdx >= 0) label = label.substring(colonIdx + 1);
        return label.toLowerCase(Locale.ROOT);
    }
}
