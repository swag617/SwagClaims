package com.swag.swagclaims.web;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.config.ClaimsConfig;
import com.swag.swagclaims.integration.VaultIntegration;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.RankExpirationRule;
import com.swag.swagclaims.model.TrustLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * HTTP handler for the SwagClaims read-only admin claims browser, mounted under SwagAPI's shared
 * web server at {@code /swagapi/swagclaims/} via {@link ClaimsWebModule}.
 *
 * <p>Authentication is handled entirely by SwagAPI's session-cookie system before this handler
 * ever runs — this handler has no password/login logic of its own. See {@link ClaimsWebModule}'s
 * class javadoc for why this is an admin-wide browser rather than a per-player "My Claims" page.</p>
 *
 * <h3>Routes</h3>
 * <ul>
 *   <li>{@code GET /} — every claim on the server: id, type, owner, world, coordinates, size,
 *       creation date, parent (for subdivisions), and trust list. Read-only.</li>
 *   <li>{@code GET /settings} — the rank-tiered claim-expiration settings page: a live-populated
 *       (from {@link VaultIntegration#getGroups()}) rank dropdown with its own per-rank rule
 *       editor, plus the general {@code expiration.*} fields. The only write-capable page this
 *       handler serves — everything else in this plugin's GUIs/commands remains the primary way
 *       to edit claims/trust/flags; this section exists solely because expiration policy needed a
 *       reviewable, no-restart way to tune numbers before turning the feature on.</li>
 *   <li>{@code POST /settings/general} — saves {@code expiration.enabled},
 *       {@code expiration.fallback-days}, and the two {@code expiration.bypass.*} numeric fields.
 *       Form-urlencoded body: {@code enabled, fallbackDays, bypassClaimBlocks, bypassBonusClaimBlocks}.</li>
 *   <li>{@code POST /settings/rank} — saves one {@code expiration.ranks.<group>} entry. Form-urlencoded
 *       body: {@code group, defaultDays, thresholdBlocks, largeClaimDays}.</li>
 * </ul>
 * <p>Both POST routes do a targeted {@link YamlConfiguration} read-modify-write of just the
 * affected key(s) (not a full config rewrite) and hot-reload the live config afterward via
 * {@link SwagClaimsPlugin#reloadClaimsConfig()} — see {@link #applyTargetedConfigChange} for why a
 * plain load/set/save round-trip (which doesn't preserve comments) was an acceptable simplification
 * here, unlike SwagAPI's own settings page.</p>
 * <p>There's no session-to-player-UUID mapping available on this panel (see this class's sibling
 * {@link ClaimsWebModule}'s javadoc), so a save is a plain confirmation with no per-admin
 * attribution — anyone who can reach this page already passed SwagAPI's own admin-only login.</p>
 *
 * <h3>Thread safety</h3>
 * <p>SwagAPI's web server dispatches every handler on a background thread pool — never the main
 * Bukkit thread. The claim cache itself ({@code ClaimManager}) is backed by a
 * {@code ConcurrentHashMap} and safe to read from any thread, but resolving owner/trust-target
 * names touches Bukkit's offline-player cache via {@link Bukkit#getOfflinePlayer(UUID)}, so the
 * whole page build is hopped onto the main thread via
 * {@link Bukkit#getScheduler()}{@code .runTask(...)} and joined on the calling (pool) thread —
 * mirroring the main-thread hop {@code WeatherWebHttpHandler} uses, just without a separate
 * {@code whenComplete} callback since there's only one response to send either way.</p>
 */
public class ClaimsWebHttpHandler implements HttpHandler {

    private static final String PAGE_CSS =
            "body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;margin:2rem;background:#0f1115;color:#e6e6e6}"
            + "h1{margin-bottom:.25rem}"
            + ".sub{color:#9aa0a6;margin-top:0}"
            + "table{border-collapse:collapse;width:100%;margin-top:1rem}"
            + "th,td{border:1px solid #2a2d34;padding:.5rem .75rem;text-align:left;font-size:.9rem;vertical-align:top}"
            + "th{background:#1a1d24;position:sticky;top:0}"
            + "tr:nth-child(even){background:#161920}"
            + ".muted{color:#666}"
            + ".nav{margin:.5rem 0 1rem}"
            + ".nav a{color:#8ab4f8;margin-right:1.25rem;text-decoration:none}"
            + ".nav a:hover{text-decoration:underline}"
            + ".warn{color:#e0a030;background:#2a2412;border:1px solid #4a3d1a;padding:.5rem .75rem;border-radius:4px}"
            + "section.panel{margin-top:1.5rem;padding:1rem 1.25rem;background:#161920;border:1px solid #2a2d34;border-radius:6px;max-width:40rem}"
            + "section.panel h2{margin-top:0}"
            + "label{display:block;margin:.75rem 0 .25rem;font-size:.9rem}"
            + "input[type=number],select{width:14rem;padding:.35rem .5rem;background:#0f1115;border:1px solid #2a2d34;color:#e6e6e6;border-radius:4px}"
            + "input[type=checkbox]{transform:scale(1.2);margin-right:.4rem}"
            + "button{margin-top:1rem;padding:.5rem 1.1rem;background:#3a6ea5;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:.9rem}"
            + "button:hover{background:#4a7eb5}"
            + ".save-msg{margin-top:.6rem;font-size:.85rem;min-height:1.1rem}"
            + ".save-msg.ok{color:#7fd17f}"
            + ".save-msg.err{color:#e07f7f}"
            + ".hint{color:#9aa0a6;font-size:.8rem;margin:.25rem 0 0}";

    /**
     * Absolute mount point this handler is registered under (see class javadoc). Used to build
     * absolute hrefs/fetch URLs in the rendered pages instead of relative ones — relative links
     * would only resolve correctly if {@link ClaimsWebModule#getUrl()} always ends in a trailing
     * slash, which isn't guaranteed by the {@code IWebService} interface.
     */
    private static final String MOUNT = "/swagapi/swagclaims/";

    private final SwagClaimsPlugin plugin;

    public ClaimsWebHttpHandler(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            if (path.equals("/") || path.isEmpty()) {
                if (!"GET".equals(method)) {
                    sendPlain(exchange, 405, "Method Not Allowed");
                    return;
                }
                serveClaimsPage(exchange);
                return;
            }

            if (path.equals("/settings")) {
                if (!"GET".equals(method)) {
                    sendPlain(exchange, 405, "Method Not Allowed");
                    return;
                }
                serveSettingsPage(exchange);
                return;
            }

            if (path.equals("/settings/general")) {
                if (!"POST".equals(method)) {
                    sendPlain(exchange, 405, "Method Not Allowed");
                    return;
                }
                handleSaveGeneral(exchange);
                return;
            }

            if (path.equals("/settings/rank")) {
                if (!"POST".equals(method)) {
                    sendPlain(exchange, 405, "Method Not Allowed");
                    return;
                }
                handleSaveRank(exchange);
                return;
            }

            sendPlain(exchange, 404, "Unknown route: " + path);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Web panel request error on " + method + " " + path, e);
            try {
                sendPlain(exchange, 500, "Internal error — check the server console.");
            } catch (Exception ignored) {
                // Response likely already partially sent — nothing more we can do.
            }
        }
    }

    // -------------------------------------------------------------------------
    // GET / — the claims table
    // -------------------------------------------------------------------------

    private void serveClaimsPage(HttpExchange exchange) throws IOException {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(buildPageHtml());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        String html;
        try {
            html = future.join();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to build web panel claims page", e);
            sendPlain(exchange, 500, "Failed to build claims page — check the server console.");
            return;
        }

        sendHtml(exchange, html);
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** Must run on the main thread — resolves offline player names via Bukkit's cache. */
    private String buildPageHtml() {
        List<Claim> claims = new ArrayList<>(plugin.getClaimManager().getAllClaims());
        claims.sort(Comparator.comparingLong(Claim::getId));

        SimpleDateFormat dateFormat = new SimpleDateFormat(plugin.getClaimsConfig().getGuiDateFormat());

        StringBuilder rows = new StringBuilder();
        for (Claim claim : claims) {
            rows.append("<tr>")
                    .append("<td>").append(claim.getId()).append("</td>")
                    .append("<td>").append(escapeHtml(claim.getClaimType().name())).append("</td>")
                    .append("<td>").append(escapeHtml(resolveOwnerName(claim))).append("</td>")
                    .append("<td>").append(escapeHtml(claim.getWorld())).append("</td>")
                    .append("<td>").append(claim.getMinX()).append(", ").append(claim.getMinZ())
                    .append(" &rarr; ").append(claim.getMaxX()).append(", ").append(claim.getMaxZ())
                    .append("</td>")
                    .append("<td>").append(claim.getWidthX()).append("&times;").append(claim.getWidthZ())
                    .append(" (").append(claim.getArea()).append(")</td>")
                    .append("<td>").append(dateFormat.format(new Date(claim.getCreatedAt()))).append("</td>")
                    .append("<td>").append(claim.getParentId() != null ? String.valueOf(claim.getParentId()) : "-")
                    .append("</td>")
                    .append("<td>").append(buildTrustCell(claim)).append("</td>")
                    .append("</tr>");
        }

        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>SwagClaims - Claims Browser</title>"
                + "<style>" + PAGE_CSS + "</style></head><body>"
                + "<h1>SwagClaims &mdash; Admin Claims Browser</h1>"
                + "<p class='sub'>Read-only. " + claims.size() + " claim(s) total. "
                + "In-game GUIs (/trustmenu, /claimslist) handle editing.</p>"
                + "<div class='nav'><a href='" + MOUNT + "'>Claims Browser</a>"
                + "<a href='" + MOUNT + "settings'>Expiration Settings</a></div>"
                + "<table><thead><tr>"
                + "<th>ID</th><th>Type</th><th>Owner</th><th>World</th><th>Coordinates</th>"
                + "<th>Size (area)</th><th>Created</th><th>Parent</th><th>Trust</th>"
                + "</tr></thead><tbody>" + rows + "</tbody></table>"
                + "</body></html>";
    }

    private String resolveOwnerName(Claim claim) {
        if (claim.isAdminClaim() || claim.getOwnerUuid() == null) {
            return "Administrator";
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(claim.getOwnerUuid());
        String name = owner.getName();
        return name != null ? name : claim.getOwnerUuid().toString();
    }

    private String buildTrustCell(Claim claim) {
        Map<String, TrustLevel> trustMap = claim.getTrustMap();
        if (trustMap.isEmpty()) {
            return "<span class='muted'>none</span>";
        }
        List<String> targets = new ArrayList<>(trustMap.keySet());
        Collections.sort(targets);

        StringBuilder sb = new StringBuilder();
        for (String target : targets) {
            sb.append(escapeHtml(resolveTrustTargetDisplay(target)))
                    .append(": ").append(trustMap.get(target).name()).append("<br>");
        }
        return sb.toString();
    }

    private String resolveTrustTargetDisplay(String target) {
        if (Claim.PUBLIC_TARGET.equals(target)) {
            return "public";
        }
        if (target.startsWith(Claim.GROUP_TARGET_PREFIX)) {
            return target;
        }
        try {
            UUID uuid = UUID.fromString(target);
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String name = op.getName();
            return name != null ? name : target;
        } catch (IllegalArgumentException e) {
            return target;
        }
    }

    // -------------------------------------------------------------------------
    // GET /settings — rank-tiered expiration settings page
    // -------------------------------------------------------------------------

    private void serveSettingsPage(HttpExchange exchange) throws IOException {
        // Hopped to the main thread for consistency with serveClaimsPage/buildPageHtml above —
        // Vault's Permission#getGroups() isn't documented as thread-safe, so this follows the same
        // convention as every other Vault/LuckPerms call in this codebase (see ClaimManager#isInGroup).
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(buildSettingsPageHtml(null));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        String html;
        try {
            html = future.join();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to build web panel settings page", e);
            sendPlain(exchange, 500, "Failed to build settings page — check the server console.");
            return;
        }

        sendHtml(exchange, html);
    }

    /** Must run on the main thread — calls into Vault's Permission provider. {@code statusMessage} is shown at the top if non-null. */
    private String buildSettingsPageHtml(String statusMessage) {
        ClaimsConfig cfg = plugin.getClaimsConfig();
        VaultIntegration vault = plugin.getVaultIntegration();

        String[] vaultGroups = vault.getGroups();
        List<String> groupList = new ArrayList<>();
        boolean usingFallbackGroupList = vaultGroups.length == 0;
        if (!usingFallbackGroupList) {
            groupList.addAll(Arrays.asList(vaultGroups));
            Collections.sort(groupList);
        } else {
            groupList.addAll(Arrays.asList("Member", "Axolotl", "Lizard", "Flea"));
        }

        StringBuilder options = new StringBuilder();
        StringBuilder ruleJson = new StringBuilder("{");
        for (int i = 0; i < groupList.size(); i++) {
            String group = groupList.get(i);
            RankExpirationRule rule = cfg.getRankExpirationRule(group);
            options.append("<option value=\"").append(escapeHtml(group)).append("\">")
                    .append(escapeHtml(group)).append("</option>");
            if (i > 0) ruleJson.append(",");
            ruleJson.append("\"").append(escapeJs(group)).append("\":{")
                    .append("\"defaultDays\":").append(rule.getDefaultDays()).append(",")
                    .append("\"thresholdBlocks\":").append(rule.getLargeClaimThresholdBlocks()).append(",")
                    .append("\"largeClaimDays\":").append(rule.getLargeClaimDays())
                    .append("}");
        }
        ruleJson.append("}");

        String warning = usingFallbackGroupList
                ? "<p class='warn'>Vault's Permission provider isn't hooked (or reports no groups) — "
                  + "showing the four default rank names below instead of live-detected groups. Once Vault "
                  + "and a permission plugin (e.g. LuckPerms) are hooked, every real group will appear here "
                  + "automatically.</p>"
                : "";

        String statusHtml = statusMessage != null
                ? "<p class='save-msg ok'>" + escapeHtml(statusMessage) + "</p>" : "";

        boolean enabled = cfg.isExpirationEnabled();
        int fallbackDays = cfg.getExpirationFallbackDays();
        long bypassClaimBlocks = cfg.getExpirationBypassClaimBlocks();
        long bypassBonusClaimBlocks = cfg.getExpirationBypassBonusClaimBlocks();

        String script = "<script>"
                + "const rankRules = " + ruleJson + ";"
                + "const MOUNT = '" + MOUNT + "';"
                + "function applyRule(group){"
                + "  const r = rankRules[group] || {defaultDays:30, thresholdBlocks:0, largeClaimDays:30};"
                + "  document.getElementById('defaultDays').value = r.defaultDays;"
                + "  document.getElementById('thresholdBlocks').value = r.thresholdBlocks;"
                + "  document.getElementById('largeClaimDays').value = r.largeClaimDays;"
                + "}"
                + "function onGroupChange(){ applyRule(document.getElementById('group').value); }"
                + "async function post(path, params){"
                + "  const body = new URLSearchParams(params);"
                + "  const res = await fetch(MOUNT + path, {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body: body.toString()});"
                + "  const text = await res.text();"
                + "  return {ok: res.ok, text: text};"
                + "}"
                + "function showMsg(el, ok, text){ el.textContent = text; el.className = 'save-msg ' + (ok ? 'ok' : 'err'); }"
                + "async function saveRank(){"
                + "  const el = document.getElementById('rankMsg');"
                + "  const group = document.getElementById('group').value;"
                + "  const result = await post('settings/rank', {"
                + "    group: group,"
                + "    defaultDays: document.getElementById('defaultDays').value,"
                + "    thresholdBlocks: document.getElementById('thresholdBlocks').value,"
                + "    largeClaimDays: document.getElementById('largeClaimDays').value"
                + "  });"
                + "  showMsg(el, result.ok, result.text);"
                + "  if (result.ok) { rankRules[group] = {"
                + "    defaultDays: parseInt(document.getElementById('defaultDays').value)||0,"
                + "    thresholdBlocks: parseInt(document.getElementById('thresholdBlocks').value)||0,"
                + "    largeClaimDays: parseInt(document.getElementById('largeClaimDays').value)||0}; }"
                + "}"
                + "async function saveGeneral(){"
                + "  const el = document.getElementById('generalMsg');"
                + "  const result = await post('settings/general', {"
                + "    enabled: document.getElementById('enabled').checked ? 'true' : 'false',"
                + "    fallbackDays: document.getElementById('fallbackDays').value,"
                + "    bypassClaimBlocks: document.getElementById('bypassClaimBlocks').value,"
                + "    bypassBonusClaimBlocks: document.getElementById('bypassBonusClaimBlocks').value"
                + "  });"
                + "  showMsg(el, result.ok, result.text);"
                + "}"
                + "window.addEventListener('DOMContentLoaded', function(){ applyRule(document.getElementById('group').value); });"
                + "</script>";

        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>SwagClaims - Expiration Settings</title>"
                + "<style>" + PAGE_CSS + "</style></head><body>"
                + "<h1>SwagClaims &mdash; Expiration Settings</h1>"
                + "<p class='sub'>Rank-tiered inactivity claim expiration. Changes here take effect "
                + "immediately (no restart) but the next sweep still waits for the configured check interval.</p>"
                + "<div class='nav'><a href='" + MOUNT + "'>Claims Browser</a>"
                + "<a href='" + MOUNT + "settings'>Expiration Settings</a></div>"
                + statusHtml
                + "<section class='panel'>"
                + "<h2>General</h2>"
                + "<label><input type='checkbox' id='enabled'" + (enabled ? " checked" : "") + "> Enable automatic claim expiration</label>"
                + "<p class='hint'>Off by default on purpose — review the rank rules below before enabling.</p>"
                + "<label for='fallbackDays'>Fallback days (no rank rule / Vault not hooked)</label>"
                + "<input type='number' min='0' id='fallbackDays' value='" + fallbackDays + "'>"
                + "<label for='bypassClaimBlocks'>Bypass: total claim blocks (owners at/above this never expire; -1 disables)</label>"
                + "<input type='number' id='bypassClaimBlocks' value='" + bypassClaimBlocks + "'>"
                + "<label for='bypassBonusClaimBlocks'>Bypass: bonus claim blocks (owners at/above this never expire; -1 disables)</label>"
                + "<input type='number' id='bypassBonusClaimBlocks' value='" + bypassBonusClaimBlocks + "'>"
                + "<div><button type='button' onclick='saveGeneral()'>Save General Settings</button></div>"
                + "<div id='generalMsg' class='save-msg'></div>"
                + "</section>"
                + "<section class='panel'>"
                + "<h2>Per-Rank Rule</h2>"
                + warning
                + "<label for='group'>Rank</label>"
                + "<select id='group' onchange='onGroupChange()'>" + options + "</select>"
                + "<label for='defaultDays'>Default expiry (days)</label>"
                + "<input type='number' min='0' id='defaultDays'>"
                + "<label for='thresholdBlocks'>Large-claim threshold (claim blocks / area; 0 disables the large-claim tier)</label>"
                + "<input type='number' min='0' id='thresholdBlocks'>"
                + "<label for='largeClaimDays'>Large-claim expiry (days)</label>"
                + "<input type='number' min='0' id='largeClaimDays'>"
                + "<div><button type='button' onclick='saveRank()'>Save Rank Rule</button></div>"
                + "<div id='rankMsg' class='save-msg'></div>"
                + "</section>"
                + script
                + "</body></html>";
    }

    // -------------------------------------------------------------------------
    // POST /settings/general, POST /settings/rank
    // -------------------------------------------------------------------------

    private void handleSaveGeneral(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseFormBody(exchange);
        ClaimsConfig cfg = plugin.getClaimsConfig();

        boolean enabled = "true".equalsIgnoreCase(form.get("enabled")) || "on".equalsIgnoreCase(form.get("enabled"));
        int fallbackDays = parseIntOrDefault(form.get("fallbackDays"), cfg.getExpirationFallbackDays());
        long bypassClaimBlocks = parseLongOrDefault(form.get("bypassClaimBlocks"), cfg.getExpirationBypassClaimBlocks());
        long bypassBonusClaimBlocks = parseLongOrDefault(form.get("bypassBonusClaimBlocks"), cfg.getExpirationBypassBonusClaimBlocks());

        try {
            applyTargetedConfigChange(yml -> {
                yml.set("expiration.enabled", enabled);
                yml.set("expiration.fallback-days", fallbackDays);
                yml.set("expiration.bypass.claim-blocks", bypassClaimBlocks);
                yml.set("expiration.bypass.bonus-claim-blocks", bypassBonusClaimBlocks);
            });
            sendPlain(exchange, 200, "General settings saved.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save expiration general settings from web panel", e);
            sendPlain(exchange, 500, "Failed to save settings — check the server console.");
        }
    }

    private void handleSaveRank(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseFormBody(exchange);
        String group = form.get("group");

        if (group == null || group.isBlank() || group.contains(".") || group.chars().anyMatch(Character::isWhitespace)) {
            sendPlain(exchange, 400, "Invalid rank name.");
            return;
        }

        int defaultDays = parseIntOrDefault(form.get("defaultDays"), 30);
        long thresholdBlocks = parseLongOrDefault(form.get("thresholdBlocks"), 0);
        int largeClaimDays = parseIntOrDefault(form.get("largeClaimDays"), defaultDays);
        String base = "expiration.ranks." + group + ".";

        try {
            applyTargetedConfigChange(yml -> {
                yml.set(base + "default-days", defaultDays);
                yml.set(base + "large-claim-threshold-blocks", thresholdBlocks);
                yml.set(base + "large-claim-days", largeClaimDays);
            });
            sendPlain(exchange, 200, "Rank rule saved for " + group + ".");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save expiration rank rule for '" + group + "' from web panel", e);
            sendPlain(exchange, 500, "Failed to save rank rule — check the server console.");
        }
    }

    /**
     * Targeted config.yml edit: loads the on-disk file into a scratch {@link YamlConfiguration},
     * applies {@code mutator} (which sets only the specific keys it cares about), saves it back,
     * then hot-reloads the plugin's live config via {@link SwagClaimsPlugin#reloadClaimsConfig()}
     * so every {@link ClaimsConfig} accessor (which all re-read live from {@code plugin.getConfig()})
     * picks up the change immediately, no restart needed.
     *
     * <p><b>Documented simplification:</b> a {@code YamlConfiguration} load/set/save round-trip
     * does not preserve comments elsewhere in the file (SnakeYAML-backed re-serialization rewrites
     * the whole document). SwagAPI's own settings page instead does raw text-line edits specifically
     * to avoid that. This plugin's config.yml comments aren't load-bearing documentation anyone
     * edits live server-side the way SwagAPI's are, so the simpler round-trip is an accepted
     * trade-off for this phase rather than an oversight — every OTHER key in the file is still
     * preserved (just without its comments), since {@code mutator} only ever calls {@code set} on
     * the specific keys it's saving.</p>
     *
     * <p>The file read/write itself runs on the calling (background HTTP pool) thread — pure file
     * I/O, no Bukkit API involved. Only the final {@code reloadClaimsConfig()} call is hopped onto
     * the main thread and joined, matching {@link #serveClaimsPage}'s existing join pattern.</p>
     */
    private void applyTargetedConfigChange(Consumer<YamlConfiguration> mutator) throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(configFile);
        mutator.accept(yml);
        yml.save(configFile);

        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                plugin.reloadClaimsConfig();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        future.join();
    }

    private Map<String, String> parseFormBody(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        if (raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private int parseIntOrDefault(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLongOrDefault(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
    }

    // -------------------------------------------------------------------------
    // HTTP response helpers
    // -------------------------------------------------------------------------

    private void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
