package com.swag.swagclaims.listener;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.SiegeManager;
import com.swag.swagclaims.model.Claim;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Lets a besieger break "soft" whitelisted blocks (see {@link SiegeManager#isSiegeBreakableMaterial})
 * inside the claim they're currently sieging, even though they lack BUILD trust there.
 *
 * <p>Runs at {@link EventPriority#HIGH} <b>without</b> {@code ignoreCancelled} so it fires after
 * {@link ClaimProtectionListener}'s normal-priority BUILD-trust check has already cancelled the
 * break — this listener only ever un-cancels an event, never cancels one itself.
 */
public class SiegeListener implements Listener {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;

    public SiegeListener(SwagClaimsPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) return;

        Player player = event.getPlayer();
        Claim claim = claimManager.getClaimAt(event.getBlock().getLocation());
        if (claim == null) return;

        long topLevelId = claim.isTopLevel() ? claim.getId()
                : (claim.getParentId() != null ? claim.getParentId() : claim.getId());

        SiegeManager.ActiveSiege siege = plugin.getSiegeManager().getActiveSiege(topLevelId);
        if (siege == null || !siege.attacker.equals(player.getUniqueId())) return;

        if (plugin.getSiegeManager().isSiegeBreakableMaterial(event.getBlock().getType())) {
            event.setCancelled(false);
        }
    }
}
