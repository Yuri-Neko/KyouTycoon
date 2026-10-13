package id.kyou.tycoon.progression;

import org.bukkit.configuration.ConfigurationSection;

import java.sql.*;
import java.util.*;

/** SQLite-backed research trees with prerequisite and balance checks. */
public final class ResearchManager {
    private final Connection connection;
    private final ConfigurationSection config;

    public ResearchManager(Connection connection, ConfigurationSection config) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.config = Objects.requireNonNull(config, "config");
    }

    public boolean isUnlocked(UUID player, String researchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM research WHERE player_uuid=? AND research_id=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, researchId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    public List<String> unlocked(UUID player) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT research_id FROM research WHERE player_uuid=? ORDER BY research_id")) {
            statement.setString(1, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Unlocks a research node and deducts the cost in the same SQLite transaction.
     * @return a result identifying why the request succeeded or failed
     */
    public UnlockResult unlock(UUID player, String researchId, long nowEpochSeconds) throws SQLException {
        Objects.requireNonNull(player, "player");
        if (researchId == null || !researchId.matches("[a-zA-Z0-9_-]{1,64}")) return UnlockResult.INVALID;
        String base = "research." + researchId;
        if (!config.getBoolean("research.enabled", true)
                || !config.getBoolean(base + ".enabled", true)
                || !config.isConfigurationSection(base)) return UnlockResult.UNKNOWN;
        double cost = config.getDouble(base + ".cost", -1);
        if (!Double.isFinite(cost) || cost < 0) return UnlockResult.INVALID;
        List<String> prerequisites = config.getStringList(base + ".prerequisites");
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (isUnlocked(player, researchId)) {
                connection.rollback();
                return UnlockResult.ALREADY_UNLOCKED;
            }
            for (String prerequisite : prerequisites) {
                if (!isUnlocked(player, prerequisite)) {
                    connection.rollback();
                    return UnlockResult.MISSING_PREREQUISITE;
                }
            }
            double balance;
            try (PreparedStatement statement = connection.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
                statement.setString(1, player.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        connection.rollback();
                        return UnlockResult.PLAYER_NOT_FOUND;
                    }
                    balance = rows.getDouble(1);
                }
            }
            if (balance < cost) {
                connection.rollback();
                return UnlockResult.INSUFFICIENT_FUNDS;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE players SET balance=balance-?,expense=expense+? WHERE uuid=? AND balance>=?")) {
                statement.setDouble(1, cost);
                statement.setDouble(2, cost);
                statement.setString(3, player.toString());
                statement.setDouble(4, cost);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return UnlockResult.INSUFFICIENT_FUNDS;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO research(player_uuid,research_id,unlocked_at) VALUES(?,?,?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, researchId);
                statement.setLong(3, nowEpochSeconds);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, "RESEARCH");
                statement.setDouble(3, cost);
                statement.setString(4, researchId);
                statement.setLong(5, nowEpochSeconds);
                statement.executeUpdate();
            }
            connection.commit();
            return UnlockResult.UNLOCKED;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    public double productionSpeedBonus(UUID player) throws SQLException {
        double total = 0;
        for (String id : unlocked(player)) {
            total += Math.max(0, config.getDouble("research." + id + ".production_speed", 0));
        }
        return Math.min(0.90, total);
    }

    public enum UnlockResult {
        UNLOCKED, ALREADY_UNLOCKED, MISSING_PREREQUISITE, INSUFFICIENT_FUNDS,
        PLAYER_NOT_FOUND, UNKNOWN, INVALID
    }
}
