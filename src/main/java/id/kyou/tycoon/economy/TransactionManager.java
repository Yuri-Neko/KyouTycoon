package id.kyou.tycoon.economy;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Audit trail for economy changes. */
public final class TransactionManager {
    private final Connection connection;
    public TransactionManager(Connection connection) { this.connection = java.util.Objects.requireNonNull(connection, "connection"); }

    public long record(UUID player, String type, double amount, String note, long epochSeconds) throws SQLException {
        if (player == null || type == null || type.isBlank()) throw new IllegalArgumentException("player and type are required");
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("amount must be finite and non-negative");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, player.toString()); statement.setString(2, type.trim().toUpperCase(java.util.Locale.ROOT));
            statement.setDouble(3, amount); statement.setString(4, note == null ? "" : note); statement.setLong(5, epochSeconds);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1; }
        }
    }

    public List<Transaction> recent(UUID player, int limit) throws SQLException {
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<Transaction> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,player_uuid,type,amount,note,created_at FROM transactions WHERE player_uuid=? ORDER BY id DESC LIMIT ?")) {
            statement.setString(1, player.toString()); statement.setInt(2, safeLimit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new Transaction(result.getLong("id"), player,
                        result.getString("type"), result.getDouble("amount"), result.getString("note"), result.getLong("created_at")));
            }
        }
        return List.copyOf(rows);
    }

    public record Transaction(long id, UUID player, String type, double amount, String note, long createdAt) {}
}
