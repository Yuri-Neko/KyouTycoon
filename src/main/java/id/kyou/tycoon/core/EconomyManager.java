package id.kyou.tycoon.core;

import java.sql.*;
import java.util.Objects;
import java.util.UUID;

/** Atomic SQL economy operations with a durable transaction log. */
public final class EconomyManager {
    private final Connection connection;
    public EconomyManager(Connection connection) { this.connection = Objects.requireNonNull(connection, "connection"); }

    public double balance(UUID player) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
            q.setString(1, player.toString());
            try (ResultSet r = q.executeQuery()) { if (!r.next()) throw new SQLException("Player does not exist: " + player); return r.getDouble(1); }
        }
    }

    public boolean withdraw(UUID player, double amount, String reason, long now) throws SQLException {
        validateAmount(amount);
        synchronized (connection) {
            boolean oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement q = connection.prepareStatement(
                    "UPDATE players SET balance=balance-?,expense=expense+? WHERE uuid=? AND balance>=?")) {
                q.setDouble(1, amount); q.setDouble(2, amount); q.setString(3, player.toString()); q.setDouble(4, amount);
                if (q.executeUpdate() != 1) { connection.rollback(); return false; }
                record(player, "WITHDRAW", amount, reason, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback(); throw ex;
            } finally { connection.setAutoCommit(oldAuto); }
        }
    }

    public void deposit(UUID player, double amount, String reason, long now) throws SQLException {
        validateAmount(amount);
        synchronized (connection) {
            boolean oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement q = connection.prepareStatement(
                    "UPDATE players SET balance=balance+?,revenue=revenue+? WHERE uuid=?")) {
                q.setDouble(1, amount); q.setDouble(2, amount); q.setString(3, player.toString());
                if (q.executeUpdate() != 1) throw new SQLException("Player does not exist: " + player);
                record(player, "DEPOSIT", amount, reason, now); connection.commit();
            } catch (SQLException | RuntimeException ex) { connection.rollback(); throw ex; }
            finally { connection.setAutoCommit(oldAuto); }
        }
    }

    public boolean transfer(UUID from, UUID to, double amount, long now) throws SQLException {
        validateAmount(amount);
        if (from.equals(to) || amount <= 0) return false;
        synchronized (connection) {
            boolean oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement q = connection.prepareStatement(
                        "UPDATE players SET balance=balance-?,expense=expense+? WHERE uuid=? AND balance>=?")) {
                    q.setDouble(1, amount); q.setDouble(2, amount); q.setString(3, from.toString()); q.setDouble(4, amount);
                    if (q.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                try (PreparedStatement q = connection.prepareStatement("UPDATE players SET balance=balance+?,revenue=revenue+? WHERE uuid=?")) {
                    q.setDouble(1, amount); q.setDouble(2, amount); q.setString(3, to.toString());
                    if (q.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                record(from, "TRANSFER_OUT", amount, "to=" + to, now);
                record(to, "TRANSFER_IN", amount, "from=" + from, now);
                connection.commit(); return true;
            } catch (SQLException | RuntimeException ex) { connection.rollback(); throw ex; }
            finally { connection.setAutoCommit(oldAuto); }
        }
    }

    private void record(UUID player, String type, double amount, String note, long now) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement(
                "INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")) {
            q.setString(1, player.toString()); q.setString(2, type); q.setDouble(3, amount);
            q.setString(4, note == null ? "" : note); q.setLong(5, now); q.executeUpdate();
        }
    }

    private void validateAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Amount must be finite and non-negative");
    }
}
