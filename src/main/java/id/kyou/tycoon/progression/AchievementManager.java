package id.kyou.tycoon.progression;

import org.bukkit.configuration.ConfigurationSection;

import java.sql.*;
import java.util.*;

/** Persistent, idempotent achievement unlocks. */
public final class AchievementManager {
    private final Connection connection;
    private final ConfigurationSection config;

    public AchievementManager(Connection connection, ConfigurationSection config) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.config = Objects.requireNonNull(config, "config");
    }

    public boolean unlock(UUID player, String achievementId, long nowEpochSeconds) throws SQLException {
        Objects.requireNonNull(player, "player");
        if (achievementId == null || !achievementId.matches("[a-zA-Z0-9_-]{1,64}")) return false;
        String path = "achievements.items." + achievementId;
        if (!config.getBoolean("achievements.enabled", true) || !config.isConfigurationSection(path)
                || !config.getBoolean(path + ".enabled", true)) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO achievements(player_uuid,achievement_id,unlocked_at) VALUES(?,?,?)")) {
            statement.setString(1, player.toString());
            statement.setString(2, achievementId);
            statement.setLong(3, nowEpochSeconds);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean has(UUID player, String achievementId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM achievements WHERE player_uuid=? AND achievement_id=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, achievementId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    public List<Achievement> list(UUID player) throws SQLException {
        List<Achievement> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT achievement_id,unlocked_at FROM achievements WHERE player_uuid=? ORDER BY unlocked_at,achievement_id")) {
            statement.setString(1, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("achievement_id");
                    String path = "achievements.items." + id;
                    result.add(new Achievement(id, config.getString(path + ".name", id),
                            config.getString(path + ".description", ""), rows.getLong("unlocked_at")));
                }
            }
        }
        return List.copyOf(result);
    }

    public enum Event {
        FIRST_PLOT("first_plot"), FIRST_MACHINE("first_machine"), MILLIONAIRE("millionaire");
        private final String id;
        Event(String id) { this.id = id; }
        public String id() { return id; }
    }

    public record Achievement(String id, String name, String description, long unlockedAt) {}
}
