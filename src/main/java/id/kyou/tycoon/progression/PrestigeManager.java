package id.kyou.tycoon.progression;

import org.bukkit.configuration.ConfigurationSection;

import java.sql.*;
import java.util.Objects;
import java.util.UUID;

/** Transaction-safe prestige progress and configured reset behaviour. */
public final class PrestigeManager {
    private final Connection connection;
    private final ConfigurationSection config;

    public PrestigeManager(Connection connection, ConfigurationSection config) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.config = Objects.requireNonNull(config, "config");
    }

    public int level(UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT level FROM prestige WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Math.max(0, rows.getInt(1)) : 0; }
        }
    }

    public PrestigeResult prestige(UUID player, double companyValue, long nowEpochSeconds) throws SQLException {
        Objects.requireNonNull(player, "player");
        if (!config.getBoolean("prestige.enabled", true)) return PrestigeResult.DISABLED;
        double required = config.getDouble("prestige.minimum_company_value", 10_000_000_000d);
        if (!Double.isFinite(companyValue) || companyValue < required) return PrestigeResult.REQUIREMENT_NOT_MET;
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int nextLevel = level(player) + 1;
            if (config.getBoolean("prestige.reset.money", true)) {
                double starting = config.getDouble("economy.starting_balance", 10_000);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE players SET balance=?,revenue=0,expense=0 WHERE uuid=?")) {
                    statement.setDouble(1, Math.max(0, starting));
                    statement.setString(2, player.toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return PrestigeResult.PLAYER_NOT_FOUND;
                    }
                }
            }
            if (config.getBoolean("prestige.reset.machines", true)) {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM machines WHERE owner_uuid=?")) {
                    statement.setString(1, player.toString()); statement.executeUpdate();
                }
            }
            if (config.getBoolean("prestige.reset.warehouse", true)) {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM warehouses WHERE owner_uuid=?")) {
                    statement.setString(1, player.toString()); statement.executeUpdate();
                }
            }
            if (config.getBoolean("prestige.reset.research", true)) {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM research WHERE player_uuid=?")) {
                    statement.setString(1, player.toString()); statement.executeUpdate();
                }
            }
            if (config.getBoolean("prestige.reset.employees", true)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM employees WHERE company_id IN (SELECT id FROM companies WHERE owner_uuid=?)")) {
                    statement.setString(1, player.toString()); statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO prestige(player_uuid,level) VALUES(?,?) ON CONFLICT(player_uuid) DO UPDATE SET level=excluded.level")) {
                statement.setString(1, player.toString()); statement.setInt(2, nextLevel); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")) {
                statement.setString(1, player.toString()); statement.setString(2, "PRESTIGE");
                statement.setDouble(3, 0); statement.setString(4, "level=" + nextLevel);
                statement.setLong(5, nowEpochSeconds); statement.executeUpdate();
            }
            connection.commit();
            return PrestigeResult.success(nextLevel);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    public double productionBonus(UUID player) throws SQLException {
        int level = level(player);
        return Math.max(0, config.getDouble("prestige.bonuses.production_per_level", 0.05)) * level;
    }

    public double revenueBonus(UUID player) throws SQLException {
        int level = level(player);
        return Math.max(0, config.getDouble("prestige.bonuses.revenue_per_level", 0.02)) * level;
    }

    public record PrestigeResult(boolean success, int newLevel, Reason reason) {
        static PrestigeResult success(int level) { return new PrestigeResult(true, level, Reason.SUCCESS); }
        static PrestigeResult of(Reason reason) { return new PrestigeResult(false, 0, reason); }
        static final PrestigeResult DISABLED = of(Reason.DISABLED);
        static final PrestigeResult REQUIREMENT_NOT_MET = of(Reason.REQUIREMENT_NOT_MET);
        static final PrestigeResult PLAYER_NOT_FOUND = of(Reason.PLAYER_NOT_FOUND);
    }
    public enum Reason { SUCCESS, DISABLED, REQUIREMENT_NOT_MET, PLAYER_NOT_FOUND }
}
