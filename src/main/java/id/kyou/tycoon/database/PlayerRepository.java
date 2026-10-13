package id.kyou.tycoon.database;

import java.sql.*;
import java.util.*;

/** Prepared-statement repository for player economy profiles. */
public final class PlayerRepository {
    private static final String COLUMNS = "uuid,name,balance,revenue,expense,last_seen";
    private final Connection connection;

    public PlayerRepository(Connection connection) { this.connection = Objects.requireNonNull(connection, "connection"); }

    public Optional<PlayerRecord> find(UUID uuid) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT " + COLUMNS + " FROM players WHERE uuid=?")) {
            q.setString(1, uuid.toString());
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public List<PlayerRecord> findAll() throws SQLException {
        List<PlayerRecord> result = new ArrayList<>();
        try (Statement q = connection.createStatement(); ResultSet r = q.executeQuery("SELECT " + COLUMNS + " FROM players ORDER BY last_seen DESC")) {
            while (r.next()) result.add(read(r));
        }
        return List.copyOf(result);
    }

    public PlayerRecord createIfAbsent(UUID uuid, String name, double startingBalance, long now) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Player name cannot be blank");
        if (!Double.isFinite(startingBalance) || startingBalance < 0) throw new IllegalArgumentException("Invalid starting balance");
        try (PreparedStatement q = connection.prepareStatement(
                "INSERT OR IGNORE INTO players(uuid,name,balance,revenue,expense,last_seen) VALUES(?,?,?,0,0,?)")) {
            q.setString(1, uuid.toString()); q.setString(2, name); q.setDouble(3, startingBalance); q.setLong(4, now); q.executeUpdate();
        }
        try (PreparedStatement q = connection.prepareStatement("UPDATE players SET name=?,last_seen=? WHERE uuid=?")) {
            q.setString(1, name); q.setLong(2, now); q.setString(3, uuid.toString()); q.executeUpdate();
        }
        return find(uuid).orElseThrow(() -> new SQLException("Player row was not created"));
    }

    public void save(PlayerRecord player) throws SQLException {
        Objects.requireNonNull(player, "player");
        try (PreparedStatement q = connection.prepareStatement(
                "INSERT INTO players(uuid,name,balance,revenue,expense,last_seen) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,balance=excluded.balance,revenue=excluded.revenue,expense=excluded.expense,last_seen=excluded.last_seen")) {
            q.setString(1, player.uuid().toString()); q.setString(2, player.name()); q.setDouble(3, player.balance());
            q.setDouble(4, player.revenue()); q.setDouble(5, player.expense()); q.setLong(6, player.lastSeen()); q.executeUpdate();
        }
    }

    private PlayerRecord read(ResultSet r) throws SQLException {
        return new PlayerRecord(UUID.fromString(r.getString("uuid")), r.getString("name"), r.getDouble("balance"),
                r.getDouble("revenue"), r.getDouble("expense"), r.getLong("last_seen"));
    }

    public record PlayerRecord(UUID uuid, String name, double balance, double revenue, double expense, long lastSeen) {
        public PlayerRecord {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Player name cannot be blank");
            if (!Double.isFinite(balance) || balance < 0 || !Double.isFinite(revenue) || revenue < 0
                    || !Double.isFinite(expense) || expense < 0) throw new IllegalArgumentException("Invalid money value");
        }
    }
}
