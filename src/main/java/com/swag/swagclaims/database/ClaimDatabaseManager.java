package com.swag.swagclaims.database;

import com.SwagDev.SwagAPI.api.IDatabaseService;
import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import com.swag.swagclaims.model.ClaimType;
import com.swag.swagclaims.model.FlagValue;
import com.swag.swagclaims.model.PlayerClaimData;
import com.swag.swagclaims.model.TrustLevel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Owns all {@code swagclaims_*} table schema and persistence. Every method that touches the
 * database runs through {@link IDatabaseService}'s async helpers ({@code queryAsync}/
 * {@code executeAsync}) rather than borrowing a raw connection synchronously on the caller's
 * thread — the one exception is schema creation, which callers explicitly {@code .join()} once
 * during {@code onEnable} (before any other manager touches the cache), matching the ordinary
 * "plugin startup blocks until its own storage is ready" expectation.
 */
public class ClaimDatabaseManager {

    private final SwagClaimsPlugin plugin;
    private final IDatabaseService db;

    public ClaimDatabaseManager(SwagClaimsPlugin plugin, IDatabaseService db) {
        this.plugin = plugin;
        this.db = db;
    }

    /**
     * Creates all six swagclaims_ tables (if missing). Runs synchronously on the calling thread
     * and returns an already-completed future so {@code onEnable} can {@code .join()} it safely.
     *
     * <p><b>Deliberately does NOT go through {@code IDatabaseService#queryAsync}</b>: that helper
     * schedules the work via {@code Bukkit.getScheduler().runTaskAsynchronously(...)}, which is
     * not guaranteed to have a worker pick up the task this early in server boot (confirmed by a
     * real hang here — a thread dump showed the main thread parked in
     * {@code CompletableFuture.join()} at this exact call site with no thread anywhere in the JVM
     * executing the scheduled callable). Every other plugin in this ecosystem avoids
     * {@code queryAsync}/{@code executeAsync} for their own startup schema/data loading for the
     * same reason — a raw synchronous connection during {@code onEnable} is the established,
     * safe pattern. Every other (post-startup, event-driven) use of {@code queryAsync} elsewhere
     * in this class is unaffected and stays truly async, since by then the server is fully
     * ticking and the scheduler is reliably running.</p>
     */
    public CompletableFuture<Void> createSchema() {
        try {
            String autoIncrement = db.isMySQL() ? "AUTO_INCREMENT" : "AUTOINCREMENT";
            try (Connection conn = db.getConnection();
                 Statement st = conn.createStatement()) {

                st.execute("CREATE TABLE IF NOT EXISTS swagclaims_claims (" +
                        "id INTEGER PRIMARY KEY " + autoIncrement + "," +
                        "legacy_gp_id BIGINT," +
                        "world VARCHAR(64) NOT NULL," +
                        "min_x INTEGER NOT NULL," +
                        "min_y INTEGER NOT NULL," +
                        "min_z INTEGER NOT NULL," +
                        "max_x INTEGER NOT NULL," +
                        "max_y INTEGER NOT NULL," +
                        "max_z INTEGER NOT NULL," +
                        "owner_uuid VARCHAR(36)," +
                        "parent_id BIGINT," +
                        "claim_type VARCHAR(16) NOT NULL," +
                        "name VARCHAR(64)," +
                        "inherit_nothing BOOLEAN DEFAULT 0," +
                        "created_at BIGINT NOT NULL," +
                        "last_active_at BIGINT NOT NULL)");

                st.execute("CREATE TABLE IF NOT EXISTS swagclaims_trust (" +
                        "claim_id BIGINT NOT NULL," +
                        "target VARCHAR(40) NOT NULL," +
                        "trust_level VARCHAR(16) NOT NULL," +
                        "PRIMARY KEY (claim_id, target))");

                st.execute("CREATE TABLE IF NOT EXISTS swagclaims_flags (" +
                        "claim_id BIGINT NOT NULL," +
                        "flag_key VARCHAR(32) NOT NULL," +
                        "params VARCHAR(255)," +
                        "value BOOLEAN NOT NULL," +
                        "PRIMARY KEY (claim_id, flag_key))");

                st.execute("CREATE TABLE IF NOT EXISTS swagclaims_world_flags (" +
                        "world VARCHAR(64) NOT NULL," +
                        "flag_key VARCHAR(32) NOT NULL," +
                        "params VARCHAR(255)," +
                        "value BOOLEAN NOT NULL," +
                        "PRIMARY KEY (world, flag_key))");

                st.execute("CREATE TABLE IF NOT EXISTS swagclaims_playerdata (" +
                        "uuid VARCHAR(36) PRIMARY KEY," +
                        "accrued_blocks BIGINT NOT NULL DEFAULT 0," +
                        "bonus_blocks BIGINT NOT NULL DEFAULT 0," +
                        "last_login BIGINT NOT NULL DEFAULT 0," +
                        "ignore_claims BOOLEAN NOT NULL DEFAULT 0)");

                st.execute("CREATE TABLE IF NOT EXISTS swagclaims_siege (" +
                        "id INTEGER PRIMARY KEY " + autoIncrement + "," +
                        "attacker_uuid VARCHAR(36) NOT NULL," +
                        "defender_uuid VARCHAR(36) NOT NULL," +
                        "claim_id BIGINT NOT NULL," +
                        "start_time BIGINT NOT NULL," +
                        "end_time BIGINT)");

                st.execute("CREATE TABLE IF NOT EXISTS swagclaims_bans (" +
                        "claim_id BIGINT NOT NULL," +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "PRIMARY KEY (claim_id, player_uuid))");

            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create SwagClaims schema!", e);
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Loads every claim and its trust rows into memory, keyed by claim id. */
    /**
     * Runs synchronously on the calling thread rather than through {@code queryAsync} — see
     * {@link #createSchema()}'s javadoc for why: this is also joined once during {@code onEnable}.
     */
    public CompletableFuture<Map<Long, Claim>> loadAllClaims() {
        Map<Long, Claim> result = new LinkedHashMap<>();
        try {
            try (Connection conn = db.getConnection()) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM swagclaims_claims")) {
                    while (rs.next()) {
                        Claim claim = new Claim();
                        claim.setId(rs.getLong("id"));
                        long legacyId = rs.getLong("legacy_gp_id");
                        claim.setLegacyGpId(rs.wasNull() ? null : legacyId);
                        claim.setWorld(rs.getString("world"));
                        claim.setBounds(
                                rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                                rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"));
                        String ownerStr = rs.getString("owner_uuid");
                        claim.setOwnerUuid(ownerStr != null ? UUID.fromString(ownerStr) : null);
                        long parentId = rs.getLong("parent_id");
                        claim.setParentId(rs.wasNull() ? null : parentId);
                        claim.setClaimType(ClaimType.fromString(rs.getString("claim_type")));
                        claim.setName(rs.getString("name"));
                        claim.setInheritNothing(rs.getBoolean("inherit_nothing"));
                        claim.setCreatedAt(rs.getLong("created_at"));
                        claim.setLastActiveAt(rs.getLong("last_active_at"));
                        result.put(claim.getId(), claim);
                    }
                }

                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM swagclaims_trust")) {
                    while (rs.next()) {
                        long claimId = rs.getLong("claim_id");
                        Claim claim = result.get(claimId);
                        if (claim == null) continue; // orphaned row (claim deleted without cascading) — skip
                        TrustLevel level = TrustLevel.fromString(rs.getString("trust_level"));
                        if (level == null) continue;
                        claim.setTrust(rs.getString("target"), level);
                    }
                }

                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM swagclaims_flags")) {
                    while (rs.next()) {
                        long claimId = rs.getLong("claim_id");
                        Claim claim = result.get(claimId);
                        if (claim == null) continue; // orphaned row (claim deleted without cascading) — skip
                        claim.setFlag(rs.getString("flag_key"), new FlagValue(rs.getString("params"), rs.getBoolean("value")));
                    }
                }

                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM swagclaims_bans")) {
                    while (rs.next()) {
                        long claimId = rs.getLong("claim_id");
                        Claim claim = result.get(claimId);
                        if (claim == null) continue; // orphaned row (claim deleted without cascading) — skip
                        try {
                            claim.banPlayer(UUID.fromString(rs.getString("player_uuid")));
                        } catch (IllegalArgumentException ignored) {
                            // malformed UUID in the row — skip rather than fail the whole load
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load claims from database!", e);
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(result);
    }

    /** Inserts a new claim row and returns the generated id. */
    public CompletableFuture<Long> insertClaim(Claim claim) {
        return db.queryAsync(() -> {
            String sql = "INSERT INTO swagclaims_claims " +
                    "(legacy_gp_id, world, min_x, min_y, min_z, max_x, max_y, max_z, owner_uuid, parent_id, " +
                    "claim_type, name, inherit_nothing, created_at, last_active_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                if (claim.getLegacyGpId() != null) {
                    ps.setLong(1, claim.getLegacyGpId());
                } else {
                    ps.setNull(1, java.sql.Types.BIGINT);
                }
                ps.setString(2, claim.getWorld());
                ps.setInt(3, claim.getMinX());
                ps.setInt(4, claim.getMinY());
                ps.setInt(5, claim.getMinZ());
                ps.setInt(6, claim.getMaxX());
                ps.setInt(7, claim.getMaxY());
                ps.setInt(8, claim.getMaxZ());
                ps.setString(9, claim.getOwnerUuid() != null ? claim.getOwnerUuid().toString() : null);
                if (claim.getParentId() != null) {
                    ps.setLong(10, claim.getParentId());
                } else {
                    ps.setNull(10, java.sql.Types.BIGINT);
                }
                ps.setString(11, claim.getClaimType().name());
                ps.setString(12, claim.getName());
                ps.setBoolean(13, claim.isInheritNothing());
                ps.setLong(14, claim.getCreatedAt());
                ps.setLong(15, claim.getLastActiveAt());
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert claim into database!", e);
                throw new RuntimeException(e);
            }
            throw new IllegalStateException("Insert did not return a generated id for claim");
        });
    }

    /** Updates a claim's bounds and last-active timestamp. */
    public void updateClaimBounds(Claim claim) {
        db.executeAsync(() -> {
            String sql = "UPDATE swagclaims_claims SET min_x = ?, min_y = ?, min_z = ?, " +
                    "max_x = ?, max_y = ?, max_z = ?, last_active_at = ? WHERE id = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, claim.getMinX());
                ps.setInt(2, claim.getMinY());
                ps.setInt(3, claim.getMinZ());
                ps.setInt(4, claim.getMaxX());
                ps.setInt(5, claim.getMaxY());
                ps.setInt(6, claim.getMaxZ());
                ps.setLong(7, claim.getLastActiveAt());
                ps.setLong(8, claim.getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update claim bounds for claim #" + claim.getId(), e);
            }
        });
    }

    /** Updates a claim's owner (used for admin ownership transfers — see ClaimManager#transferClaim). */
    public void updateClaimOwner(long claimId, UUID ownerUuid) {
        db.executeAsync(() -> {
            String sql = "UPDATE swagclaims_claims SET owner_uuid = ? WHERE id = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ownerUuid != null ? ownerUuid.toString() : null);
                ps.setLong(2, claimId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update owner for claim #" + claimId, e);
            }
        });
    }

    /** Updates a claim's type (used when transferring an admin claim converts it to BASIC — see ClaimManager#transferClaim). */
    public void updateClaimType(long claimId, ClaimType type) {
        db.executeAsync(() -> {
            String sql = "UPDATE swagclaims_claims SET claim_type = ? WHERE id = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type.name());
                ps.setLong(2, claimId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update claim type for claim #" + claimId, e);
            }
        });
    }

    /** Updates a claim's display nickname ({@code null} clears it back to unset). */
    public void updateClaimName(long claimId, String name) {
        db.executeAsync(() -> {
            String sql = "UPDATE swagclaims_claims SET name = ? WHERE id = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setLong(2, claimId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update name for claim #" + claimId, e);
            }
        });
    }

    /**
     * Updates a claim's parent id (null clears it back to top-level). Used by
     * {@code GriefPreventionImporter}'s second pass, which links every migrated claim to its
     * (also-freshly-migrated) parent only after every claim already has a real database id.
     */
    public void updateClaimParent(long claimId, Long parentId) {
        db.executeAsync(() -> {
            String sql = "UPDATE swagclaims_claims SET parent_id = ? WHERE id = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                if (parentId != null) {
                    ps.setLong(1, parentId);
                } else {
                    ps.setNull(1, java.sql.Types.BIGINT);
                }
                ps.setLong(2, claimId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update parent id for claim #" + claimId, e);
            }
        });
    }

    /** Deletes a claim and its trust/flag/ban rows. */
    public void deleteClaim(long claimId) {
        db.executeAsync(() -> {
            try (Connection conn = db.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM swagclaims_trust WHERE claim_id = ?")) {
                    ps.setLong(1, claimId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM swagclaims_flags WHERE claim_id = ?")) {
                    ps.setLong(1, claimId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM swagclaims_bans WHERE claim_id = ?")) {
                    ps.setLong(1, claimId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM swagclaims_claims WHERE id = ?")) {
                    ps.setLong(1, claimId);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete claim #" + claimId, e);
            }
        });
    }

    // ── Claim bans (/claimban, /unclaimban) ─────────────────────────────────

    /** Inserts a ban row; no-ops (via INSERT OR IGNORE / ON DUPLICATE KEY) if already banned. */
    public void saveBan(long claimId, UUID playerUuid) {
        db.executeAsync(() -> {
            String sql = "INSERT INTO swagclaims_bans (claim_id, player_uuid) VALUES (?, ?) " +
                    (db.isMySQL()
                            ? "ON DUPLICATE KEY UPDATE claim_id = claim_id"
                            : "ON CONFLICT(claim_id, player_uuid) DO NOTHING");
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, claimId);
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save ban for claim #" + claimId + " player " + playerUuid, e);
            }
        });
    }

    /** Deletes a single ban row. */
    public void deleteBan(long claimId, UUID playerUuid) {
        db.executeAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM swagclaims_bans WHERE claim_id = ? AND player_uuid = ?")) {
                ps.setLong(1, claimId);
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete ban for claim #" + claimId + " player " + playerUuid, e);
            }
        });
    }

    /** Upserts a single trust row. */
    public void saveTrust(long claimId, String target, TrustLevel level) {
        db.executeAsync(() -> {
            String sql = "INSERT INTO swagclaims_trust (claim_id, target, trust_level) VALUES (?, ?, ?) " +
                    (db.isMySQL()
                            ? "ON DUPLICATE KEY UPDATE trust_level = VALUES(trust_level)"
                            : "ON CONFLICT(claim_id, target) DO UPDATE SET trust_level = excluded.trust_level");
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, claimId);
                ps.setString(2, target);
                ps.setString(3, level.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save trust for claim #" + claimId + " target " + target, e);
            }
        });
    }

    /** Deletes a single trust row. */
    public void deleteTrust(long claimId, String target) {
        db.executeAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM swagclaims_trust WHERE claim_id = ? AND target = ?")) {
                ps.setLong(1, claimId);
                ps.setString(2, target);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete trust for claim #" + claimId + " target " + target, e);
            }
        });
    }

    // ── Flags ────────────────────────────────────────────────────────────────

    /** Upserts a single claim-level flag row. */
    public void saveClaimFlag(long claimId, String flagKey, String params, boolean value) {
        db.executeAsync(() -> {
            String sql = "INSERT INTO swagclaims_flags (claim_id, flag_key, params, value) VALUES (?, ?, ?, ?) " +
                    (db.isMySQL()
                            ? "ON DUPLICATE KEY UPDATE params = VALUES(params), value = VALUES(value)"
                            : "ON CONFLICT(claim_id, flag_key) DO UPDATE SET params = excluded.params, value = excluded.value");
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, claimId);
                ps.setString(2, flagKey);
                ps.setString(3, params);
                ps.setBoolean(4, value);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save flag '" + flagKey + "' for claim #" + claimId, e);
            }
        });
    }

    /** Deletes a single claim-level flag row. */
    public void deleteClaimFlag(long claimId, String flagKey) {
        db.executeAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM swagclaims_flags WHERE claim_id = ? AND flag_key = ?")) {
                ps.setLong(1, claimId);
                ps.setString(2, flagKey);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete flag '" + flagKey + "' for claim #" + claimId, e);
            }
        });
    }

    /** Loads every world-level flag into memory, keyed by lowercased world name then flag key. */
    /**
     * Runs synchronously on the calling thread rather than through {@code queryAsync} — see
     * {@link #createSchema()}'s javadoc for why: this is also joined once during {@code onEnable}.
     */
    public CompletableFuture<Map<String, Map<String, FlagValue>>> loadAllWorldFlags() {
        Map<String, Map<String, FlagValue>> result = new LinkedHashMap<>();
        try {
            try (Connection conn = db.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM swagclaims_world_flags")) {
                while (rs.next()) {
                    String world = rs.getString("world").toLowerCase();
                    FlagValue value = new FlagValue(rs.getString("params"), rs.getBoolean("value"));
                    result.computeIfAbsent(world, w -> new java.util.HashMap<>()).put(rs.getString("flag_key"), value);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load world flags from database!", e);
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(result);
    }

    /** Upserts a single world-level flag row. */
    public void saveWorldFlag(String world, String flagKey, String params, boolean value) {
        db.executeAsync(() -> {
            String sql = "INSERT INTO swagclaims_world_flags (world, flag_key, params, value) VALUES (?, ?, ?, ?) " +
                    (db.isMySQL()
                            ? "ON DUPLICATE KEY UPDATE params = VALUES(params), value = VALUES(value)"
                            : "ON CONFLICT(world, flag_key) DO UPDATE SET params = excluded.params, value = excluded.value");
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, world);
                ps.setString(2, flagKey);
                ps.setString(3, params);
                ps.setBoolean(4, value);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save world flag '" + flagKey + "' for world " + world, e);
            }
        });
    }

    /** Deletes a single world-level flag row. */
    public void deleteWorldFlag(String world, String flagKey) {
        db.executeAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM swagclaims_world_flags WHERE world = ? AND flag_key = ?")) {
                ps.setString(1, world);
                ps.setString(2, flagKey);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete world flag '" + flagKey + "' for world " + world, e);
            }
        });
    }

    /** Loads a single player's claim-block data, or null if they have no row yet. */
    public CompletableFuture<PlayerClaimData> loadPlayerData(UUID uuid) {
        return db.queryAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT * FROM swagclaims_playerdata WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    PlayerClaimData data = new PlayerClaimData(uuid);
                    data.setAccruedBlocks(rs.getLong("accrued_blocks"));
                    data.setBonusBlocks(rs.getLong("bonus_blocks"));
                    data.setLastLogin(rs.getLong("last_login"));
                    data.setIgnoreClaims(rs.getBoolean("ignore_claims"));
                    return data;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load player claim data for " + uuid, e);
                throw new RuntimeException(e);
            }
        });
    }

    /** Upserts a player's claim-block data. */
    public void savePlayerData(PlayerClaimData data) {
        db.executeAsync(() -> {
            String sql = "INSERT INTO swagclaims_playerdata (uuid, accrued_blocks, bonus_blocks, last_login, ignore_claims) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    (db.isMySQL()
                            ? "ON DUPLICATE KEY UPDATE accrued_blocks = VALUES(accrued_blocks), bonus_blocks = VALUES(bonus_blocks), " +
                              "last_login = VALUES(last_login), ignore_claims = VALUES(ignore_claims)"
                            : "ON CONFLICT(uuid) DO UPDATE SET accrued_blocks = excluded.accrued_blocks, " +
                              "bonus_blocks = excluded.bonus_blocks, last_login = excluded.last_login, " +
                              "ignore_claims = excluded.ignore_claims");
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, data.getUuid().toString());
                ps.setLong(2, data.getAccruedBlocks());
                ps.setLong(3, data.getBonusBlocks());
                ps.setLong(4, data.getLastLogin());
                ps.setBoolean(5, data.isIgnoreClaims());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player claim data for " + data.getUuid(), e);
            }
        });
    }

    /**
     * Inserts a new siege history row and returns its generated id (used later to patch in the
     * end time). Returns -1 on failure instead of throwing — a siege history row is a
     * nice-to-have record, not something worth failing the siege over.
     */
    public CompletableFuture<Long> insertSiege(UUID attacker, UUID defender, long claimId, long startTime) {
        return db.queryAsync(() -> {
            String sql = "INSERT INTO swagclaims_siege (attacker_uuid, defender_uuid, claim_id, start_time) VALUES (?, ?, ?, ?)";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, attacker.toString());
                ps.setString(2, defender != null ? defender.toString() : null);
                ps.setLong(3, claimId);
                ps.setLong(4, startTime);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert siege record", e);
            }
            return -1L;
        });
    }

    /** Patches the end time onto a previously-inserted siege history row. No-ops if id is -1. */
    public void updateSiegeEnd(long siegeId, long endTime) {
        if (siegeId < 0) return;
        db.executeAsync(() -> {
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE swagclaims_siege SET end_time = ? WHERE id = ?")) {
                ps.setLong(1, endTime);
                ps.setLong(2, siegeId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update siege end time for #" + siegeId, e);
            }
        });
    }

    /** Bulk-saves every currently-loaded player data entry (used by the periodic autosave task). */
    public void savePlayerDataBulk(List<PlayerClaimData> allData) {
        if (allData.isEmpty()) return;
        db.executeAsync(() -> {
            String sql = "INSERT INTO swagclaims_playerdata (uuid, accrued_blocks, bonus_blocks, last_login, ignore_claims) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    (db.isMySQL()
                            ? "ON DUPLICATE KEY UPDATE accrued_blocks = VALUES(accrued_blocks), bonus_blocks = VALUES(bonus_blocks), " +
                              "last_login = VALUES(last_login), ignore_claims = VALUES(ignore_claims)"
                            : "ON CONFLICT(uuid) DO UPDATE SET accrued_blocks = excluded.accrued_blocks, " +
                              "bonus_blocks = excluded.bonus_blocks, last_login = excluded.last_login, " +
                              "ignore_claims = excluded.ignore_claims");
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                for (PlayerClaimData data : new ArrayList<>(allData)) {
                    ps.setString(1, data.getUuid().toString());
                    ps.setLong(2, data.getAccruedBlocks());
                    ps.setLong(3, data.getBonusBlocks());
                    ps.setLong(4, data.getLastLogin());
                    ps.setBoolean(5, data.isIgnoreClaims());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to bulk-save player claim data", e);
            }
        });
    }
}
