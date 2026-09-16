package com.swag.swagclaims;

import com.SwagDev.SwagAPI.api.IDatabaseService;
import com.SwagDev.SwagAPI.api.IEventBusService;
import com.swag.swagclaims.command.AbandonAllClaimsCommand;
import com.swag.swagclaims.command.AbandonClaimCommand;
import com.swag.swagclaims.command.AbandonTopLevelClaimCommand;
import com.swag.swagclaims.command.AccessTrustCommand;
import com.swag.swagclaims.command.AdminClaimsCommand;
import com.swag.swagclaims.command.BuyClaimBlocksCommand;
import com.swag.swagclaims.command.ClaimBanCommand;
import com.swag.swagclaims.command.ClaimBlocksCommand;
import com.swag.swagclaims.command.ClaimCommand;
import com.swag.swagclaims.command.ClaimInfoCommand;
import com.swag.swagclaims.command.ClaimRenameCommand;
import com.swag.swagclaims.command.ClaimsListCommand;
import com.swag.swagclaims.command.ContainerTrustCommand;
import com.swag.swagclaims.command.ClaimFlagCommand;
import com.swag.swagclaims.command.DeleteAllClaimsCommand;
import com.swag.swagclaims.command.DeleteClaimCommand;
import com.swag.swagclaims.command.ExtendClaimCommand;
import com.swag.swagclaims.command.PermissionTrustCommand;
import com.swag.swagclaims.command.SellClaimBlocksCommand;
import com.swag.swagclaims.command.SendClaimBlocksCommand;
import com.swag.swagclaims.command.SiegeCommand;
import com.swag.swagclaims.command.SubdivideClaimsCommand;
import com.swag.swagclaims.command.TransferClaimCommand;
import com.swag.swagclaims.command.TrustCommand;
import com.swag.swagclaims.command.TrustMenuCommand;
import com.swag.swagclaims.command.UnclaimBanCommand;
import com.swag.swagclaims.command.UntrustCommand;
import com.swag.swagclaims.config.ClaimsConfig;
import com.swag.swagclaims.config.ConfigMigrator;
import com.swag.swagclaims.config.Messages;
import com.swag.swagclaims.database.ClaimDatabaseManager;
import com.swag.swagclaims.integration.VaultIntegration;
import com.swag.swagclaims.listener.ClaimFlagListener;
import com.swag.swagclaims.listener.ClaimProtectionListener;
import com.swag.swagclaims.listener.ClaimToolListener;
import com.swag.swagclaims.listener.ClaimTransitionListener;
import com.swag.swagclaims.listener.CommandRestrictionListener;
import com.swag.swagclaims.listener.GUIListener;
import com.swag.swagclaims.listener.PlayerDataListener;
import com.swag.swagclaims.listener.PvPProtectionListener;
import com.swag.swagclaims.listener.SiegeListener;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.ExpirationManager;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.manager.SiegeManager;
import com.swag.swagclaims.placeholder.SwagClaimsExpansion;
import com.swag.swagclaims.util.ClaimVisualizer;
import com.swag.swagclaims.web.ClaimsWebModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class SwagClaimsPlugin extends JavaPlugin {

    private static SwagClaimsPlugin instance;

    // ── SwagAPI service references ─────────────────────────────────────────
    private IDatabaseService dbService;
    private IEventBusService eventBusService; // soft — claim lifecycle events are best-effort

    private ClaimsConfig claimsConfig;
    private Messages messages;
    private ClaimDatabaseManager databaseManager;
    private ClaimManager claimManager;
    private FlagManager flagManager;
    private ClaimVisualizer claimVisualizer;
    private VaultIntegration vaultIntegration;
    private SiegeManager siegeManager;
    private ExpirationManager expirationManager;
    private ClaimToolListener claimToolListener;
    private ClaimFlagListener claimFlagListener;
    private GUIListener guiListener;
    private ClaimsWebModule webModule;

    @Override
    public void onEnable() {
        try {
            instance = this;
            saveDefaultConfig();
            ConfigMigrator.migrate(this);
            reloadConfig();

            if (!hookSwagAPI()) {
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            this.claimsConfig = new ClaimsConfig(this);
            this.messages = new Messages(this);
            this.vaultIntegration = new VaultIntegration(this);
            this.databaseManager = new ClaimDatabaseManager(this, dbService);

            // Schema must exist before anything else touches the database — block once here.
            try {
                databaseManager.createSchema().join();
                getLogger().info("SwagClaims schema ready.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to create SwagClaims schema! Disabling.", e);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            this.claimManager = new ClaimManager(this, databaseManager, claimsConfig);
            try {
                claimManager.loadAll().join();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to load claims at startup!", e);
            }

            this.flagManager = new FlagManager(this, databaseManager, claimManager);
            try {
                flagManager.loadAll().join();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to load world flags at startup!", e);
            }

            this.claimVisualizer = new ClaimVisualizer(this);
            this.siegeManager = new SiegeManager(this, claimManager);

            registerListeners();
            registerCommands();
            startAutosaveTask();
            startAccrualTask();

            this.expirationManager = new ExpirationManager(this, claimManager, databaseManager, claimsConfig, vaultIntegration);
            expirationManager.start();

            registerPlaceholderExpansion();

            this.webModule = new ClaimsWebModule(this);
            webModule.enable();

            SwagClaimsAPI.init(this);

            getLogger().info("SwagClaims has been enabled!");
        } catch (Exception e) {
            getLogger().severe("CRITICAL ERROR DURING ENABLE:");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (expirationManager != null) {
            expirationManager.stop();
        }
        if (webModule != null) {
            webModule.disable();
        }
        SwagClaimsAPI.shutdown();

        if (databaseManager != null && claimManager != null) {
            try {
                databaseManager.savePlayerDataBulk(new java.util.ArrayList<>(claimManager.getAllLoadedPlayerData()));
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error saving player claim data during shutdown: " + e.getMessage(), e);
            }
        }
        getLogger().info("SwagClaims has been disabled!");
    }

    private void registerListeners() {
        this.claimToolListener = new ClaimToolListener(this, claimManager);
        getServer().getPluginManager().registerEvents(claimToolListener, this);
        getServer().getPluginManager().registerEvents(new ClaimProtectionListener(this, claimManager), this);
        getServer().getPluginManager().registerEvents(new PlayerDataListener(this, claimManager), this);
        getServer().getPluginManager().registerEvents(new PvPProtectionListener(this, claimManager), this);
        getServer().getPluginManager().registerEvents(new SiegeListener(this, claimManager), this);
        getServer().getPluginManager().registerEvents(new CommandRestrictionListener(this, claimManager), this);

        this.claimFlagListener = new ClaimFlagListener(this, claimManager, flagManager);
        getServer().getPluginManager().registerEvents(claimFlagListener, this);
        // Depends on claimFlagListener (drives playertime/playerweather/enteractionbar on transition)
        // so it must be constructed after it.
        getServer().getPluginManager().registerEvents(new ClaimTransitionListener(this, claimManager, claimFlagListener), this);

        this.guiListener = new GUIListener();
        getServer().getPluginManager().registerEvents(guiListener, this);
    }

    private void registerCommands() {
        setExecutor("claim", new ClaimCommand(this), true);
        setExecutor("trust", new TrustCommand(this), true);
        setExecutor("untrust", new UntrustCommand(this), true);
        setExecutor("containertrust", new ContainerTrustCommand(this), true);
        setExecutor("accesstrust", new AccessTrustCommand(this), true);
        setExecutor("abandonclaim", new AbandonClaimCommand(this), false);
        setExecutor("abandontoplevelclaim", new AbandonTopLevelClaimCommand(this), false);
        setExecutor("abandonallclaims", new AbandonAllClaimsCommand(this), false);
        setExecutor("claiminfo", new ClaimInfoCommand(this), false);
        setExecutor("claimslist", new ClaimsListCommand(this), true);
        setExecutor("buyclaimblocks", new BuyClaimBlocksCommand(this), false);
        setExecutor("sellclaimblocks", new SellClaimBlocksCommand(this), false);
        setExecutor("claimblocks", new ClaimBlocksCommand(this), true);
        setExecutor("subdivideclaims", new SubdivideClaimsCommand(this), false);
        setExecutor("adminclaims", new AdminClaimsCommand(this), false);
        setExecutor("deleteclaim", new DeleteClaimCommand(this), false);
        setExecutor("deleteallclaims", new DeleteAllClaimsCommand(this), false);
        setExecutor("transferclaim", new TransferClaimCommand(this), true);
        setExecutor("claimrename", new ClaimRenameCommand(this), true);
        setExecutor("siege", new SiegeCommand(this), true);
        setExecutor("claimflag", new ClaimFlagCommand(this), true);
        setExecutor("trustmenu", new TrustMenuCommand(this), false);
        setExecutor("sendclaimblocks", new SendClaimBlocksCommand(this), false);
        setExecutor("claimban", new ClaimBanCommand(this), true);
        setExecutor("unclaimban", new UnclaimBanCommand(this), true);
        setExecutor("permissiontrust", new PermissionTrustCommand(this), true);
        setExecutor("extendclaim", new ExtendClaimCommand(this), true);
    }

    private void setExecutor(String name, Object executor, boolean withTabCompleter) {
        org.bukkit.command.PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' is not defined in plugin.yml — skipping registration.");
            return;
        }
        if (executor instanceof org.bukkit.command.CommandExecutor ce) {
            cmd.setExecutor(ce);
        }
        if (withTabCompleter && executor instanceof org.bukkit.command.TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }

    /** Periodically flushes every currently-loaded player's claim-block data to the database. */
    private void startAutosaveTask() {
        long intervalTicks = 20L * 60 * 5; // 5 minutes
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (databaseManager == null || claimManager == null) return;
            databaseManager.savePlayerDataBulk(new java.util.ArrayList<>(claimManager.getAllLoadedPlayerData()));
        }, intervalTicks, intervalTicks);
    }

    /**
     * Credits accrued claim blocks to online players once a minute, prorated from the configured
     * per-hour rate. Runs synchronously (not async) since it reads {@code Bukkit.getOnlinePlayers()}
     * and each player's current world — Bukkit API access must stay on the main thread.
     */
    private void startAccrualTask() {
        long intervalTicks = 20L * 60; // 1 minute
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (claimManager == null) return;
            claimManager.accrueBlocksForOnlinePlayers();
        }, intervalTicks, intervalTicks);
    }

    /** Reloads config.yml and messages.yml (used by /claim reload). */
    public void reloadClaimsConfig() {
        reloadConfig();
        messages.load();
    }

    /**
     * Hooks all SwagAPI services this plugin needs. IDatabaseService is a hard
     * requirement — if it's missing, SwagAPI isn't loaded and the plugin disables itself.
     */
    private boolean hookSwagAPI() {
        ServicesManager sm = getServer().getServicesManager();

        RegisteredServiceProvider<IDatabaseService> dbProv = sm.getRegistration(IDatabaseService.class);
        if (dbProv == null) {
            getLogger().severe("SwagAPI IDatabaseService not found! Is SwagAPI loaded? Disabling.");
            return false;
        }
        dbService = dbProv.getProvider();
        getLogger().info("Hooked SwagAPI IDatabaseService.");

        // Soft — claim lifecycle events (create/delete/trust) are best-effort cross-plugin
        // notifications, not required for SwagClaims' own correctness.
        RegisteredServiceProvider<IEventBusService> busProv = sm.getRegistration(IEventBusService.class);
        if (busProv != null) {
            eventBusService = busProv.getProvider();
            getLogger().info("Hooked SwagAPI IEventBusService.");
        } else {
            getLogger().info("SwagAPI IEventBusService not registered — claim lifecycle events won't be published.");
        }

        getLogger().info("SwagAPI hook complete.");
        return true;
    }

    /** Registers the {@code %swagclaims_*%} PlaceholderAPI expansion if PlaceholderAPI is installed. */
    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SwagClaimsExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
    }

    public static SwagClaimsPlugin getInstance() {
        return instance;
    }

    public IDatabaseService getDbService() {
        return dbService;
    }

    /** Nullable — SwagAPI's event bus is a soft dependency (see {@link #hookSwagAPI()}). */
    public IEventBusService getEventBusService() {
        return eventBusService;
    }

    public ClaimsConfig getClaimsConfig() {
        return claimsConfig;
    }

    public Messages getMessages() {
        return messages;
    }

    public ClaimDatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ClaimManager getClaimManager() {
        return claimManager;
    }

    public FlagManager getFlagManager() {
        return flagManager;
    }

    public ClaimVisualizer getClaimVisualizer() {
        return claimVisualizer;
    }

    public VaultIntegration getVaultIntegration() {
        return vaultIntegration;
    }

    public SiegeManager getSiegeManager() {
        return siegeManager;
    }

    public ExpirationManager getExpirationManager() {
        return expirationManager;
    }

    public ClaimToolListener getClaimToolListener() {
        return claimToolListener;
    }

    public GUIListener getGuiListener() {
        return guiListener;
    }
}
