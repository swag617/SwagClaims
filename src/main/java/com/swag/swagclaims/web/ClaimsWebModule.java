package com.swag.swagclaims.web;

import com.SwagDev.SwagAPI.api.IWebService;
import com.swag.swagclaims.SwagClaimsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Registers the SwagClaims read-only admin claims browser with SwagAPI's shared {@link IWebService}.
 *
 * <p>SwagClaims does not own an HttpServer — SwagAPI's shared server does. Login is handled
 * entirely by SwagAPI's own session-cookie system: the mount point at
 * {@code /swagapi/swagclaims/} is already gated by SwagAPI's login before
 * {@link ClaimsWebHttpHandler} ever runs, so this panel has no password/auth of its own.</p>
 *
 * <p><b>Why this is an admin-wide browser, not a per-player "My Claims" page:</b>
 * {@link IWebService#getSessionUsername} resolves only the signed-in SwagAPI panel account's
 * username — a name chosen freely at account-setup time. Nothing on the public
 * {@code IWebService} interface maps that username back to a specific in-game player UUID; that
 * mapping ({@code linkedUuid}) exists only internally on SwagAPI's concrete {@code WebService}
 * class, and no dependent plugin in this ecosystem downcasts to it (verified — every plugin that
 * calls {@code registerModule} compiles only against the {@code IWebService} interface, same as
 * this one). Without a documented, interface-level way to resolve "this browser session belongs
 * to this specific player," a per-viewer "My Claims" page can't be built reliably — guessing at
 * an undocumented cast to a concrete SwagAPI class isn't a fit for a shared ecosystem interface.
 * This phase ships the documented fallback the phase plan explicitly allows instead: a single
 * read-only browser listing every claim on the server (owner, world, coordinates, size, creation
 * date, trust list), gated the same way every other admin panel in this ecosystem already is
 * (behind SwagAPI's login) — see {@link ClaimsWebHttpHandler} for what it actually renders.</p>
 *
 * <p>Config key: {@code web.enabled} (default {@code true}) gates whether this module registers
 * at all.</p>
 */
public class ClaimsWebModule {

    private final SwagClaimsPlugin plugin;
    private IWebService webService;
    private boolean registered = false;

    public ClaimsWebModule(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (!plugin.getClaimsConfig().isWebPanelEnabled()) {
            plugin.getLogger().info("Web panel disabled in config, skipping.");
            return;
        }

        RegisteredServiceProvider<IWebService> rsp =
                Bukkit.getServicesManager().getRegistration(IWebService.class);
        if (rsp == null) {
            plugin.getLogger().warning(
                    "SwagAPI IWebService not present — web panel unavailable. Is SwagAPI installed and enabled?");
            return;
        }

        webService = rsp.getProvider();
        webService.registerModule(plugin, new ClaimsWebHttpHandler(plugin));
        registered = true;
        plugin.getLogger().info("Web panel registered at " + getUrl());
    }

    public void disable() {
        if (!registered || webService == null) {
            return;
        }
        webService.unregisterModule(plugin);
        registered = false;
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getUrl() {
        if (webService == null || !registered) {
            return null;
        }
        return webService.getPluginUrl(plugin.getName().toLowerCase());
    }
}
