package id.kyou.tycoon.progression;

import java.sql.*;
import java.util.*;

/** Persistent, idempotent player achievement unlocks. */
public final class AchievementManager {
    private final Connection connection;
    private final Map<String, Achievement> definitions;

    public AchievementManager(Connection connection, Collection<Achievement> definitions) {
        this.connection = Objects.requireNonNull(connection, "connection");
        Map<String, Achievement> loaded = new LinkedHashMap<>();
        for (Achievement achievement : definitions) {
            if (loaded.putIfAbsent(achievement.id(), achievement) != null) throw new IllegalArgumentException("Duplicate achievement: " + achievement.id());
        }
        this.definitions = Collections.unmodifiableMap(loaded);
    }

    public Optional<Achievement> definition(String id) { return Optional.ofNullable(definitions.get(id)); }
    public List<Achievement> definitions() { return List.copyOf(definitions.values()); }

    public Set<String> unlocked(UUID player) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (PreparedStatement q = connection.prepareStatement("SELECT achievement_id FROM achievements WHERE player_uuid=? ORDER BY achievement_id")) {
            q.setString(1, player.toString());
            try (ResultSet rows = q.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return Collections.unmodifiableSet(result);
    }

    public Optional<Achievement> unlock(UUID player, String id, long now) throws SQLException {
        Objects.requireNonNull(player, "player");
        Achievement achievement = definitions.get(id);
        if (achievement == null) return Optional.empty();
        try (PreparedStatement q = connection.prepareStatement("INSERT OR IGNORE INTO achievements(player_uuid,achievement_id,unlocked_at) VALUES(?,?,?)")) {
            q.setString(1, player.toString()); q.setString(2, id); q.setLong(3, now);
            return q.executeUpdate() == 1 ? Optional.of(achievement) : Optional.empty();
        }
    }

    public List<Achievement> evaluate(UUID player, Stats stats, long now) throws SQLException {
        Objects.requireNonNull(stats, "stats");
        Set<String> existing = unlocked(player);
        List<Achievement> newlyUnlocked = new ArrayList<>();
        for (Achievement achievement : definitions.values()) {
            if (existing.contains(achievement.id()) || !achievement.condition().met(stats)) continue;
            unlock(player, achievement.id(), now).ifPresent(newlyUnlocked::add);
        }
        return List.copyOf(newlyUnlocked);
    }

    public record Achievement(String id, String name, String description, Condition condition) {
        public Achievement {
            if (id == null || !id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid achievement ID");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Name cannot be blank");
            description = description == null ? "" : description;
            Objects.requireNonNull(condition, "condition");
        }
    }
    public record Stats(long machinesOwned, double lifetimeRevenue, double balance, int plotLevel, int prestigeLevel) {
        public Stats {
            if (machinesOwned < 0 || plotLevel < 0 || prestigeLevel < 0) throw new IllegalArgumentException("Counters cannot be negative");
            if (!Double.isFinite(lifetimeRevenue) || lifetimeRevenue < 0 || !Double.isFinite(balance) || balance < 0) {
                throw new IllegalArgumentException("Money values must be finite and non-negative");
            }
        }
    }
    public record Condition(long minimumMachines, double minimumRevenue, double minimumBalance, int minimumPlotLevel, int minimumPrestigeLevel) {
        public Condition {
            if (minimumMachines < 0 || minimumPlotLevel < 0 || minimumPrestigeLevel < 0
                    || !Double.isFinite(minimumRevenue) || minimumRevenue < 0
                    || !Double.isFinite(minimumBalance) || minimumBalance < 0) {
                throw new IllegalArgumentException("Invalid achievement condition");
            }
        }
        boolean met(Stats stats) {
            return stats.machinesOwned() >= minimumMachines
                    && stats.lifetimeRevenue() >= minimumRevenue
                    && stats.balance() >= minimumBalance
                    && stats.plotLevel() >= minimumPlotLevel
                    && stats.prestigeLevel() >= minimumPrestigeLevel;
        }
    }
}
