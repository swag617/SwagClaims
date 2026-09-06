package com.swag.swagclaims.config;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.ClaimMode;
import com.swag.swagclaims.model.RankExpirationRule;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Typed accessors over config.yml. Re-read live from {@code plugin.getConfig()} on every call
 * (cheap for a few scalar keys) so {@code /claim reload} takes effect without needing this class
 * to track its own reload lifecycle.
 */
public class ClaimsConfig {

    private final SwagClaimsPlugin plugin;

    public ClaimsConfig(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    public Material getModificationTool() {
        return parseMaterial(plugin.getConfig().getString("modification-tool", "GOLDEN_SHOVEL"), Material.GOLDEN_SHOVEL);
    }

    public Material getInvestigationTool() {
        return parseMaterial(plugin.getConfig().getString("investigation-tool", "STICK"), Material.STICK);
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null) return fallback;
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid material '" + name + "' in config.yml, falling back to " + fallback);
            return fallback;
        }
    }

    public int getMinimumWidth() {
        return Math.max(1, plugin.getConfig().getInt("minimum-width", 5));
    }

    public int getMinimumArea() {
        return Math.max(1, plugin.getConfig().getInt("minimum-area", 100));
    }

    /** 0 = unlimited (not enforced this phase). */
    public int getMaxSubdivisionDepth() {
        return plugin.getConfig().getInt("max-subdivision-depth", 0);
    }

    public long getInitialBlocks() {
        return plugin.getConfig().getLong("initial-blocks", 1000);
    }

    public double getAbandonReturnRatio() {
        double ratio = plugin.getConfig().getDouble("abandon-return-ratio", 1.0);
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    public int getVisualizationDurationSeconds() {
        return Math.max(1, plugin.getConfig().getInt("visualization-duration-seconds", 10));
    }

    // ── GUI ─────────────────────────────────────────────────────────────────

    /** {@link java.text.SimpleDateFormat} pattern used for created/last-active timestamps in {@code ClaimListGUI}. */
    public String getGuiDateFormat() {
        return plugin.getConfig().getString("gui.date-format", "dd.MM.yyyy HH:mm");
    }

    // ── Per-world claim mode ────────────────────────────────────────────────

    /** Reads {@code worlds.<world>.claim-mode}, falling back to {@code worlds.Default.claim-mode}, then SURVIVAL. */
    public ClaimMode getClaimMode(String world) {
        String specific = plugin.getConfig().getString("worlds." + world + ".claim-mode");
        String value = specific != null ? specific : plugin.getConfig().getString("worlds.Default.claim-mode", "SURVIVAL");
        ClaimMode mode = ClaimMode.fromString(value);
        return mode != null ? mode : ClaimMode.SURVIVAL;
    }

    // ── Claim blocks economy ────────────────────────────────────────────────

    public double getAccruedPerHour(String world) {
        return getWorldOverridableDouble("claim-blocks.accrued-per-hour", world, 250);
    }

    public long getMaxAccrued(String world) {
        return (long) getWorldOverridableDouble("claim-blocks.max-accrued", world, 100_000_000);
    }

    private double getWorldOverridableDouble(String basePath, String world, double fallback) {
        String specificPath = basePath + "." + world;
        if (plugin.getConfig().isSet(specificPath)) {
            return plugin.getConfig().getDouble(specificPath, fallback);
        }
        return plugin.getConfig().getDouble(basePath + ".Default", fallback);
    }

    public double getClaimBlocksPurchaseCost() {
        return plugin.getConfig().getDouble("claim-blocks.purchase-cost", 1.0);
    }

    public double getClaimBlocksSellValue() {
        return plugin.getConfig().getDouble("claim-blocks.sell-value", 1.0);
    }

    /** 0 = disabled — no automatic starter claim is granted on first join. */
    public int getAutomaticClaimsRadius() {
        return Math.max(0, plugin.getConfig().getInt("automatic-claims-radius", 0));
    }

    // ── Siege ───────────────────────────────────────────────────────────────

    public List<String> getSiegeEnabledWorlds() {
        return plugin.getConfig().getStringList("siege.enabled-worlds");
    }

    public int getSiegeDoorOpenDelaySeconds() {
        return Math.max(1, plugin.getConfig().getInt("siege.door-open-delay-seconds", 300));
    }

    public int getSiegeCooldownMinutes() {
        return Math.max(0, plugin.getConfig().getInt("siege.cooldown-minutes", 60));
    }

    // ── PvP & combat ────────────────────────────────────────────────────────

    /** PvP is enabled exactly where claims are DISABLED — see config.yml's "pvp" section comment. */
    public boolean isPvpEnabled(String world) {
        return getClaimMode(world) == ClaimMode.DISABLED;
    }

    public boolean isProtectFreshSpawns() {
        return plugin.getConfig().getBoolean("pvp.protect-fresh-spawns", true);
    }

    public int getFreshSpawnProtectionSeconds() {
        return Math.max(0, plugin.getConfig().getInt("pvp.fresh-spawn-protection-seconds", 10));
    }

    public boolean isPunishLogout() {
        return plugin.getConfig().getBoolean("pvp.punish-logout", true);
    }

    public int getCombatTimeoutSeconds() {
        return Math.max(1, plugin.getConfig().getInt("pvp.combat-timeout-seconds", 15));
    }

    public List<String> getBlockedCombatCommands() {
        return plugin.getConfig().getStringList("pvp.blocked-commands");
    }

    public boolean isPreventLavaDumpingNearPlayers() {
        return plugin.getConfig().getBoolean("pvp.prevent-lava-dumping-near-players", true);
    }

    public boolean isPreventFlintAndSteelNearPlayers() {
        return plugin.getConfig().getBoolean("pvp.prevent-flint-and-steel-near-players", true);
    }

    public int getPvpDangerRadius() {
        return Math.max(1, plugin.getConfig().getInt("pvp.danger-radius", 15));
    }

    // ── Misc claim protections ──────────────────────────────────────────────

    public boolean isBlockExplosionsInClaims() {
        return plugin.getConfig().getBoolean("protection.block-explosions-in-claims", true);
    }

    public boolean isPistonClaimsOnly() {
        return plugin.getConfig().getBoolean("protection.piston-claims-only", true);
    }

    public boolean isFireSpreads() {
        return plugin.getConfig().getBoolean("protection.fire-spreads", false);
    }

    public boolean isFireDestroys() {
        return plugin.getConfig().getBoolean("protection.fire-destroys", false);
    }

    public boolean isPreventEndermanGriefing() {
        return plugin.getConfig().getBoolean("protection.prevent-enderman-griefing", true);
    }

    public boolean isPreventSilverfishGriefing() {
        return plugin.getConfig().getBoolean("protection.prevent-silverfish-griefing", true);
    }

    public boolean isPreventCropTrampling() {
        return plugin.getConfig().getBoolean("protection.prevent-crop-trampling", true);
    }

    // ── Claim enter/exit titles ──────────────────────────────────────────────
    // MiniMessage-formatted (not this plugin's usual {token}/'&' convention) with a %player%/
    // %claim% placeholder style, deliberately kept as-is to match the GriefPreventionEnterTitles
    // config format server staff are already used to. See ClaimTransitionListener.

    public String getTitleEnterTitle() {
        return plugin.getConfig().getString("titles.enter.title", "<white>%player%'s Claim");
    }

    public String getTitleEnterSubtitle() {
        return plugin.getConfig().getString("titles.enter.subtitle", "");
    }

    public String getTitleEnterActionbar() {
        return plugin.getConfig().getString("titles.enter.actionbar", "");
    }

    public String getTitleExitTitle() {
        return plugin.getConfig().getString("titles.exit.title", "");
    }

    public String getTitleExitSubtitle() {
        return plugin.getConfig().getString("titles.exit.subtitle", "<white>Wilderness");
    }

    public String getTitleExitActionbar() {
        return plugin.getConfig().getString("titles.exit.actionbar", "");
    }

    /** false (default) = watch every world; true = only the worlds listed below. */
    public boolean isWatchedWorldsEnabled() {
        return plugin.getConfig().getBoolean("titles.watched-worlds.enabled", false);
    }

    public List<String> getWatchedWorlds() {
        return plugin.getConfig().getStringList("titles.watched-worlds.worlds");
    }

    public boolean isShowTitlesOnDimensionSwitch() {
        return plugin.getConfig().getBoolean("titles.show-on-dimension-switch", true);
    }

    public boolean isShowTitlesOnAdminClaim() {
        return plugin.getConfig().getBoolean("titles.show-on-admin-claim", true);
    }

    // ── Web panel ───────────────────────────────────────────────────────────

    /** Whether the admin claims browser (and its settings section) registers with SwagAPI's IWebService. */
    public boolean isWebPanelEnabled() {
        return plugin.getConfig().getBoolean("web.enabled", true);
    }

    // ── Claim expiration (rank-tiered inactivity abandon) ───────────────────
    // Default false — see config.yml's comment on this section for why. Every value here is also
    // editable live from the web settings page (ClaimsWebHttpHandler's /settings route); a save
    // there does a targeted YamlConfiguration read-modify-write of just the affected key(s) followed
    // by reloadConfig(), so these accessors (re-reading live from plugin.getConfig() like every
    // other accessor in this class) immediately reflect it with no restart.

    public boolean isExpirationEnabled() {
        return plugin.getConfig().getBoolean("expiration.enabled", false);
    }

    public int getExpirationCheckIntervalHours() {
        return Math.max(1, plugin.getConfig().getInt("expiration.check-interval-hours", 24));
    }

    /** Days-of-inactivity threshold for an owner whose primary group has no entry under {@code expiration.ranks}. */
    public int getExpirationFallbackDays() {
        return Math.max(0, plugin.getConfig().getInt("expiration.fallback-days", 30));
    }

    /** Owners with at least this many TOTAL (accrued + bonus) claim blocks never expire. {@code <0} disables this check. */
    public long getExpirationBypassClaimBlocks() {
        return plugin.getConfig().getLong("expiration.bypass.claim-blocks", 10000);
    }

    /** Owners with at least this many BONUS claim blocks never expire. {@code <0} (default) disables this check. */
    public long getExpirationBypassBonusClaimBlocks() {
        return plugin.getConfig().getLong("expiration.bypass.bonus-claim-blocks", -1);
    }

    /** Permission node that exempts a player from expiration entirely, checked via Vault's offline lookup when available. */
    public String getExpirationBypassPermission() {
        return plugin.getConfig().getString("expiration.bypass.permission", "swagclaims.expiration.bypass");
    }

    /**
     * Every group name with its own entry under {@code expiration.ranks} in config.yml right now —
     * NOT the same as "every rank the web settings page can edit" (that list comes live from
     * {@code VaultIntegration#getGroups}); this is just what's currently persisted.
     */
    public Set<String> getConfiguredExpirationRankNames() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("expiration.ranks");
        return section != null ? section.getKeys(false) : Collections.emptySet();
    }

    /**
     * Resolves the expiration rule for {@code group}. Never returns null — a group with no entry
     * under {@code expiration.ranks} resolves to {@link #getExpirationFallbackDays()} with no
     * large-claim tier (threshold {@code 0}), matching {@code ExpirationManager}'s documented
     * "ungrouped/unconfigured" behavior. This same fallback shape is what the web settings page
     * shows for a rank it hasn't been configured for yet.
     */
    public RankExpirationRule getRankExpirationRule(String group) {
        int fallbackDays = getExpirationFallbackDays();
        if (group == null || group.isBlank()) {
            return new RankExpirationRule(fallbackDays, 0, fallbackDays);
        }
        String base = "expiration.ranks." + group + ".";
        int defaultDays = Math.max(0, plugin.getConfig().getInt(base + "default-days", fallbackDays));
        long threshold = Math.max(0, plugin.getConfig().getLong(base + "large-claim-threshold-blocks", 0));
        int largeDays = Math.max(0, plugin.getConfig().getInt(base + "large-claim-days", defaultDays));
        return new RankExpirationRule(defaultDays, threshold, largeDays);
    }
}
