package id.kyou.tycoon.economy;

import java.sql.*;
import java.util.OptionalInt;

/** Bounds company reputation to the configured minimum and maximum. */
public final class ReputationManager {
    private final Connection connection;
    private final int minimum;
    private final int maximum;

    public ReputationManager(Connection connection, int minimum, int maximum) {
        this.connection = java.util.Objects.requireNonNull(connection, "connection");
        if (minimum > maximum) throw new IllegalArgumentException("minimum cannot exceed maximum");
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public OptionalInt get(long companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT reputation FROM companies WHERE id=?")) {
            statement.setLong(1, companyId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? OptionalInt.of(clamp(rows.getInt(1))) : OptionalInt.empty();
            }
        }
    }

    public OptionalInt change(long companyId, int delta) throws SQLException {
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int current;
            try (PreparedStatement statement = connection.prepareStatement("SELECT reputation FROM companies WHERE id=?")) {
                statement.setLong(1, companyId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return OptionalInt.empty(); }
                    current = rows.getInt(1);
                }
            }
            int updated = clamp((long) current + delta);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE companies SET reputation=? WHERE id=?")) {
                statement.setInt(1, updated);
                statement.setLong(2, companyId);
                statement.executeUpdate();
            }
            connection.commit();
            return OptionalInt.of(updated);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    private int clamp(long value) { return (int) Math.max(minimum, Math.min(maximum, value)); }
}
