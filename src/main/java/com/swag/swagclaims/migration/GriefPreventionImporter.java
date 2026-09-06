package com.swag.swagclaims.migration;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.database.ClaimDatabaseManager;
import com.swag.swagclaims.manager.ClaimManager;
import com.swag.swagclaims.manager.FlagManager;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimFlags;
import com.swag.swagclaims.model.ClaimType;
import com.swag.swagclaims.model.PlayerClaimData;
import com.swag.swagclaims.model.TrustLevel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * One-time importer that reads a live GriefPrevention(+GPFlags) installation's flat-file data
 * and loads it into SwagClaims' database/cache. Never mutates any source file.
 *
 * <p><b>Threading:</b> {@link #run} does blocking file I/O and (in live mode) many sequential
 * DB writes — it must always be invoked off the main thread (see
 * {@code MigrateGriefPreventionCommand}, which wraps it in
 * {@code Bukkit.getScheduler().runTaskAsynchronously}). Because of that, this class deliberately
 * does <b>not</b> route trust grants through {@link ClaimManager#grantTrust}: that method
 * publishes a {@code SwagCrossPluginMessageEvent} (a synchronous Bukkit {@code Event}), which
 * throws {@code IllegalStateException} if fired off the main thread. Migrating potentially
 * thousands of legacy trust/claim rows also isn't something other plugins should be notified
 * about one row at a time, so trust rows are written directly via the same
 * {@link ClaimDatabaseManager#saveTrust} call {@code grantTrust} itself uses, skipping only the
 * event publish. {@link FlagManager#setClaimFlag}/{@link FlagManager#setWorldFlag} are safe to
 * call as-is — neither publishes any event.
 *
 * <p><b>Testability:</b> every pure-parsing step ({@link #parseClaimFile}, {@link #parseCorner},
 * {@link #normalizeTrustList}, {@link #parsePlayerDataFile}, {@link #parseFlagsFile}) is a static
 * method with no dependency on a live plugin/manager instance, so it can be exercised directly
 * against fixture files without a running server.
 */
public class GriefPreventionImporter {

    private final SwagClaimsPlugin plugin;
    private final ClaimManager claimManager;
    private final FlagManager flagManager;
    private final ClaimDatabaseManager db;

    public GriefPreventionImporter(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
        this.claimManager = plugin.getClaimManager();
        this.flagManager = plugin.getFlagManager();
        this.db = plugin.getDatabaseManager();
    }

    // ── Result / intermediate data holders ─────────────────────────────────

    public static class Result {
        public int claimsImported, claimsSkipped;
        public int trustImported, trustSkipped;
        public int playerDataImported, playerDataSkipped;
        public int flagsImported, flagsSkipped;
        public boolean dryRun;
        public File reportFile;
        public final List<String> warnings = new ArrayList<>();
    }

    static class ParsedClaim {
        long legacyId;
        String world;
        int minX, minY, minZ, maxX, maxY, maxZ;
        UUID ownerUuid; // null = admin claim
        long parentLegacyId; // -1 = top-level
        boolean inheritNothing;
        ClaimType claimType;
        final Map<TrustLevel, List<String>> trustByLevel = new LinkedHashMap<>();
    }

    static class ParsedPlayerData {
        UUID uuid;
        long accrued;
        long bonus;
    }

    static class FlagAction {
        boolean claimScoped;
        Long claimId; // set when claimScoped
        String world; // set when !claimScoped
        String flagKey;
        String params; // nullable
        boolean value;
    }

    // ── Orchestration ───────────────────────────────────────────────────────

    /**
     * Runs the full import. {@code sourceFolder} is the GriefPrevention data folder (containing
     * {@code ClaimData/} and {@code PlayerData/}); {@code gpFlagsFile} is GPFlags'
     * {@code flags.yml} (may not exist — GPFlags is optional). In dry-run mode, every file is
     * parsed and validated exactly as in a live run, but no database write and no cache mutation
     * ever happens.
     */
    public Result run(File sourceFolder, File gpFlagsFile, boolean dryRun) {
        Result result = new Result();
        result.dryRun = dryRun;

        File claimDataDir = new File(sourceFolder, "ClaimData");
        File playerDataDir = new File(sourceFolder, "PlayerData");

        // ── Parse every ClaimData/*.yml file (no DB/cache touched yet) ──────
        List<ParsedClaim> parsedClaims = new ArrayList<>();
        if (claimDataDir.isDirectory()) {
            File[] files = claimDataDir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    try {
                        parsedClaims.add(parseClaimFile(f, result));
                    } catch (Exception e) {
                        result.claimsSkipped++;
                        result.warnings.add("[claim] Skipped '" + f.getName() + "': " + e.getMessage());
                    }
                }
            }
        } else {
            result.warnings.add("[claim] ClaimData folder not found at " + claimDataDir.getAbsolutePath()
                    + " — no claims imported.");
        }

        // ── Pass 1: insert every claim with parentId=null, recording legacy -> new id/object ──
        Map<Long, Long> legacyToNewId = new LinkedHashMap<>();
        Map<Long, Claim> legacyToClaim = new LinkedHashMap<>();
        long dryRunIdCounter = -1_000_000L; // well clear of ClaimManager's own live temp-id range

        for (ParsedClaim pc : parsedClaims) {
            try {
                Claim claim = new Claim();
                claim.setLegacyGpId(pc.legacyId);
                claim.setWorld(pc.world);
                claim.setBounds(pc.minX, pc.minY, pc.minZ, pc.maxX, pc.maxY, pc.maxZ);
                claim.setOwnerUuid(pc.ownerUuid);
                claim.setParentId(null);
                claim.setClaimType(pc.claimType);
                claim.setInheritNothing(pc.inheritNothing);
                long now = System.currentTimeMillis();
                claim.setCreatedAt(now);
                claim.setLastActiveAt(now);

                long newId;
                if (!dryRun) {
                    newId = db.insertClaim(claim).join();
                    claim.setId(newId);
                    // Bypasses ClaimManager#createClaim's validation (overlap/min-size/block-cost) —
                    // a migrated claim's historical bounds are facts to preserve, not a new request.
                    claimManager.registerMigratedClaim(claim);
                } else {
                    newId = dryRunIdCounter--;
                    claim.setId(newId);
                }
                legacyToNewId.put(pc.legacyId, newId);
                legacyToClaim.put(pc.legacyId, claim);
                result.claimsImported++;
            } catch (Exception e) {
                result.claimsSkipped++;
                result.warnings.add("[claim] Failed to insert legacy claim id " + pc.legacyId + ": " + e.getMessage());
            }
        }

        // ── Pass 2: patch real parent ids now that every claim has a new id ──
        for (ParsedClaim pc : parsedClaims) {
            if (pc.parentLegacyId == -1L) continue;
            Long newId = legacyToNewId.get(pc.legacyId);
            if (newId == null) continue; // claim itself failed to import; already logged above
            Long newParentId = legacyToNewId.get(pc.parentLegacyId);
            if (newParentId == null) {
                result.warnings.add("[claim] Legacy claim " + pc.legacyId + " referenced parent claim id "
                        + pc.parentLegacyId + ", which was not imported — left as a top-level claim.");
                continue;
            }
            Claim claim = legacyToClaim.get(pc.legacyId);
            if (claim == null) continue;
            claim.setParentId(newParentId);
            if (!dryRun) {
                db.updateClaimParent(newId, newParentId);
            }
        }

        // ── Trust ────────────────────────────────────────────────────────────
        for (ParsedClaim pc : parsedClaims) {
            Claim claim = legacyToClaim.get(pc.legacyId);
            if (claim == null) continue; // claim failed to import; its trust rows have nothing to attach to
            for (Map.Entry<TrustLevel, List<String>> entry : pc.trustByLevel.entrySet()) {
                for (String target : entry.getValue()) {
                    if (!dryRun) {
                        // Deliberately NOT ClaimManager#grantTrust — see class javadoc (async-thread
                        // event-publish safety + avoiding an event-bus flood for a bulk import).
                        claim.setTrust(target, entry.getKey());
                        db.saveTrust(claim.getId(), target, entry.getKey());
                    }
                    result.trustImported++;
                }
            }
        }

        // ── Player data ──────────────────────────────────────────────────────
        if (playerDataDir.isDirectory()) {
            File[] files = playerDataDir.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    if (f.getName().toLowerCase().endsWith(".ignore")) continue; // explicitly excluded, not an error
                    try {
                        ParsedPlayerData ppd = parsePlayerDataFile(f);
                        if (!dryRun) {
                            PlayerClaimData data = new PlayerClaimData(ppd.uuid);
                            data.setAccruedBlocks(ppd.accrued);
                            data.setBonusBlocks(ppd.bonus);
                            db.savePlayerData(data);
                            claimManager.cachePlayerData(data);
                        }
                        result.playerDataImported++;
                    } catch (Exception e) {
                        result.playerDataSkipped++;
                        result.warnings.add("[playerdata] Skipped '" + f.getName() + "': " + e.getMessage());
                    }
                }
            }
        } else {
            result.warnings.add("[playerdata] PlayerData folder not found at " + playerDataDir.getAbsolutePath()
                    + " — no player data imported.");
        }

        // ── GPFlags ──────────────────────────────────────────────────────────
        if (gpFlagsFile != null && gpFlagsFile.isFile()) {
            try {
                List<FlagAction> actions = parseFlagsFile(gpFlagsFile, legacyToNewId, result);
                for (FlagAction action : actions) {
                    if (!dryRun) {
                        if (action.claimScoped) {
                            Claim claim = claimManager.getClaim(action.claimId);
                            if (claim == null) {
                                result.flagsSkipped++;
                                result.warnings.add("[flags] Claim #" + action.claimId + " flag '" + action.flagKey
                                        + "' — claim not found in cache, skipped.");
                                continue;
                            }
                            flagManager.setClaimFlag(claim, action.flagKey, action.params, action.value);
                        } else {
                            flagManager.setWorldFlag(action.world, action.flagKey, action.params, action.value);
                        }
                    }
                    result.flagsImported++;
                }
            } catch (Exception e) {
                result.warnings.add("[flags] Failed to parse '" + gpFlagsFile.getAbsolutePath() + "': " + e.getMessage());
            }
        } else {
            result.warnings.add("[flags] GPFlags flags.yml not found at "
                    + (gpFlagsFile != null ? gpFlagsFile.getAbsolutePath() : "(unresolved path)")
                    + " — skipping (GPFlags may not be installed on this server).");
        }

        writeReport(result, sourceFolder, gpFlagsFile);
        return result;
    }

    // ── ClaimData/*.yml parsing (pure — no plugin/manager dependency) ──────────

    static ParsedClaim parseClaimFile(File file, Result result) throws Exception {
        String fileName = file.getName();
        String baseName = fileName.toLowerCase().endsWith(".yml")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        long legacyId;
        try {
            legacyId = Long.parseLong(baseName.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("filename is not a valid numeric legacy claim id: '" + baseName + "'");
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        String lesser = yml.getString("Lesser Boundary Corner");
        String greater = yml.getString("Greater Boundary Corner");
        if (lesser == null || greater == null) {
            throw new IllegalArgumentException("missing Lesser/Greater Boundary Corner");
        }

        String world = lesser.split(";")[0].trim();
        if (world.isEmpty()) {
            throw new IllegalArgumentException("empty world name in corner '" + lesser + "'");
        }

        int[] lo = parseCorner(lesser);
        int[] hi = parseCorner(greater);

        String ownerRaw = yml.getString("Owner", "");
        UUID ownerUuid = null;
        if (ownerRaw != null && !ownerRaw.isBlank()) {
            try {
                ownerUuid = UUID.fromString(ownerRaw.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid Owner UUID '" + ownerRaw + "'");
            }
        }

        long parentLegacyId = yml.getLong("Parent Claim ID", -1L);
        boolean inheritNothing = yml.getBoolean("inheritNothing", false);

        ClaimType claimType;
        if (ownerUuid == null) {
            claimType = ClaimType.ADMIN;
        } else if (parentLegacyId == -1L) {
            claimType = ClaimType.BASIC;
        } else {
            claimType = ClaimType.SUBDIVISION;
        }

        ParsedClaim pc = new ParsedClaim();
        pc.legacyId = legacyId;
        pc.world = world;
        pc.minX = lo[0];
        pc.minY = lo[1];
        pc.minZ = lo[2];
        pc.maxX = hi[0];
        pc.maxY = hi[1];
        pc.maxZ = hi[2];
        pc.ownerUuid = ownerUuid;
        pc.parentLegacyId = parentLegacyId;
        pc.inheritNothing = inheritNothing;
        pc.claimType = claimType;

        pc.trustByLevel.put(TrustLevel.BUILD, normalizeTrustList(yml.getStringList("Builders"), legacyId, "Builders", result));
        pc.trustByLevel.put(TrustLevel.CONTAINER, normalizeTrustList(yml.getStringList("Containers"), legacyId, "Containers", result));
        pc.trustByLevel.put(TrustLevel.ACCESS, normalizeTrustList(yml.getStringList("Accessors"), legacyId, "Accessors", result));
        pc.trustByLevel.put(TrustLevel.MANAGE, normalizeTrustList(yml.getStringList("Managers"), legacyId, "Managers", result));

        return pc;
    }

    /** Parses a {@code world;x;y;z} corner string into {x, y, z} (world is read separately by the caller). */
    static int[] parseCorner(String raw) {
        String[] parts = raw.split(";");
        if (parts.length != 4) {
            throw new IllegalArgumentException("corner '" + raw + "' does not have 4 semicolon-separated parts");
        }
        try {
            int x = (int) Math.round(Double.parseDouble(parts[1].trim()));
            int y = (int) Math.round(Double.parseDouble(parts[2].trim()));
            int z = (int) Math.round(Double.parseDouble(parts[3].trim()));
            return new int[]{x, y, z};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("corner '" + raw + "' has a non-numeric coordinate");
        }
    }

    /**
     * Normalizes a raw Builders/Containers/Accessors/Managers list entry into the same "target"
     * string shape {@code Claim}'s trust map uses: {@code public}, {@code g:<group>}, or a UUID
     * string. Invalid entries are logged against {@code result} and dropped individually — they
     * never fail the whole claim.
     */
    static List<String> normalizeTrustList(List<String> raw, long legacyId, String listName, Result result) {
        List<String> normalized = new ArrayList<>();
        if (raw == null) return normalized;
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) continue;
            String trimmed = entry.trim();
            if (trimmed.equalsIgnoreCase("public")) {
                normalized.add(Claim.PUBLIC_TARGET);
            } else if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
                normalized.add(Claim.GROUP_TARGET_PREFIX + trimmed.substring(1, trimmed.length() - 1));
            } else {
                try {
                    normalized.add(UUID.fromString(trimmed).toString());
                } catch (IllegalArgumentException e) {
                    result.trustSkipped++;
                    result.warnings.add("[trust] Claim " + legacyId + " " + listName + " entry '" + trimmed
                            + "' is not a valid UUID/public/[group] — skipped.");
                }
            }
        }
        return normalized;
    }

    // ── PlayerData/<uuid> parsing (pure) ────────────────────────────────────

    static ParsedPlayerData parsePlayerDataFile(File file) throws Exception {
        String name = file.getName();
        int dot = name.indexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        UUID uuid;
        try {
            uuid = UUID.fromString(base.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("filename '" + name + "' is not a valid player UUID");
        }

        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.size() < 3) {
            throw new IllegalArgumentException("expected at least 3 lines, found " + lines.size());
        }

        long accrued;
        long bonus;
        try {
            accrued = Long.parseLong(lines.get(1).trim());
            bonus = Long.parseLong(lines.get(2).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line 2 (accrued) or line 3 (bonus) is not a valid whole number");
        }

        ParsedPlayerData ppd = new ParsedPlayerData();
        ppd.uuid = uuid;
        ppd.accrued = accrued;
        ppd.bonus = bonus;
        return ppd;
    }

    // ── GPFlags' flags.yml parsing (pure) ────────────────────────────────────

    /**
     * Parses every flag entry in GPFlags' {@code flags.yml} into a flat list of actions,
     * resolving claim-ID-keyed sections through {@code legacyToNewId}. Doesn't touch any
     * manager/database itself — {@link #run} applies the returned actions.
     */
    static List<FlagAction> parseFlagsFile(File flagsFile, Map<Long, Long> legacyToNewId, Result result) {
        List<FlagAction> actions = new ArrayList<>();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(flagsFile);

        for (String key : yml.getKeys(false)) {
            ConfigurationSection section = yml.getConfigurationSection(key);
            if (section == null) continue;

            Long legacyClaimId = tryParseLong(key);
            if (legacyClaimId != null) {
                Long newId = legacyToNewId.get(legacyClaimId);
                if (newId == null) {
                    result.warnings.add("[flags] Claim-scoped flags for legacy claim id " + legacyClaimId
                            + " skipped — that claim was not imported.");
                    result.flagsSkipped += section.getKeys(false).size();
                    continue;
                }
                for (String flagName : section.getKeys(false)) {
                    FlagAction action = buildFlagAction(true, newId, null, flagName,
                            section.getConfigurationSection(flagName), result);
                    if (action != null) actions.add(action);
                }
            } else {
                String world = key;
                for (String flagName : section.getKeys(false)) {
                    FlagAction action = buildFlagAction(false, null, world, flagName,
                            section.getConfigurationSection(flagName), result);
                    if (action != null) actions.add(action);
                }
            }
        }
        return actions;
    }

    private static FlagAction buildFlagAction(boolean claimScoped, Long claimId, String world, String flagName,
                                               ConfigurationSection entry, Result result) {
        String subject = claimScoped ? ("claim #" + claimId) : ("world '" + world + "'");
        if (entry == null) {
            result.flagsSkipped++;
            result.warnings.add("[flags] " + subject + " flag '" + flagName + "' has no value section — skipped.");
            return null;
        }
        String key = flagName.toLowerCase();
        if (!ClaimFlags.isKnown(key)) {
            result.flagsSkipped++;
            result.warnings.add("[flags] " + subject + " unknown flag key '" + flagName + "' — skipped.");
            return null;
        }

        String params = entry.getString("params", "");
        boolean value = entry.getBoolean("value", false);

        FlagAction action = new FlagAction();
        action.claimScoped = claimScoped;
        action.claimId = claimId;
        action.world = world;
        action.flagKey = key;
        action.params = (params == null || params.isEmpty()) ? null : params;
        action.value = value;
        return action;
    }

    static Long tryParseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Report ───────────────────────────────────────────────────────────────

    private void writeReport(Result result, File sourceFolder, File gpFlagsFile) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File reportFile = new File(plugin.getDataFolder(), "migration-report-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("SwagClaims GriefPrevention Migration Report\n");
        sb.append("Generated: ").append(new Date()).append("\n");
        sb.append("Mode: ").append(result.dryRun ? "DRY RUN (no database writes performed)" : "LIVE").append("\n");
        sb.append("Source folder: ").append(sourceFolder.getAbsolutePath()).append("\n");
        sb.append("GPFlags file: ").append(gpFlagsFile != null ? gpFlagsFile.getAbsolutePath() : "(not checked)").append("\n\n");

        sb.append("Claims imported: ").append(result.claimsImported).append("\n");
        sb.append("Claims skipped: ").append(result.claimsSkipped).append("\n");
        sb.append("Trust entries imported: ").append(result.trustImported).append("\n");
        sb.append("Trust entries skipped: ").append(result.trustSkipped).append("\n");
        sb.append("Player data rows imported: ").append(result.playerDataImported).append("\n");
        sb.append("Player data rows skipped: ").append(result.playerDataSkipped).append("\n");
        sb.append("Flag entries imported: ").append(result.flagsImported).append("\n");
        sb.append("Flag entries skipped: ").append(result.flagsSkipped).append("\n\n");

        if (result.warnings.isEmpty()) {
            sb.append("No warnings — every parsed item imported cleanly.\n");
        } else {
            sb.append("Warnings (").append(result.warnings.size()).append("):\n");
            for (String w : result.warnings) {
                sb.append(" - ").append(w).append("\n");
            }
        }

        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            Files.writeString(reportFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
            result.reportFile = reportFile;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write GriefPrevention migration report file", e);
        }
    }
}
