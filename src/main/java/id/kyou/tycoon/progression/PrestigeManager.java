package id.kyou.tycoon.progression;

import java.sql.*;
import java.util.Objects;
import java.util.UUID;

/** Atomic prestige reset policy. Kept achievements and prestige level are never deleted. */
public final class PrestigeManager {
    private final Connection connection;
    private final double startingBalance;
    private final double minimumCompanyValue;
    private final ResetPolicy resetPolicy;

    public PrestigeManager(Connection connection, double startingBalance, double minimumCompanyValue, ResetPolicy resetPolicy) {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (!Double.isFinite(startingBalance) || startingBalance < 0) throw new IllegalArgumentException("Invalid starting balance");
        if (!Double.isFinite(minimumCompanyValue) || minimumCompanyValue < 0) throw new IllegalArgumentException("Invalid prestige threshold");
        this.startingBalance = startingBalance;
        this.minimumCompanyValue = minimumCompanyValue;
        this.resetPolicy = Objects.requireNonNull(resetPolicy, "resetPolicy");
    }

    public Result prestige(UUID player, double companyValue) throws SQLException {
        Objects.requireNonNull(player, "player");
        if (!Double.isFinite(companyValue) || companyValue < minimumCompanyValue) return new Result(false, 0, "threshold-not-met");
        boolean oldAuto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            double currentBalance;
            try (PreparedStatement q = connection.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (ResultSet rows = q.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return new Result(false, 0, "player-not-found"); }
                    currentBalance = rows.getDouble(1);
                }
            }
            int previous;
            try (PreparedStatement q = connection.prepareStatement("SELECT level FROM prestige WHERE player_uuid=?")) {
                q.setString(1, player.toString());
                try (ResultSet rows = q.executeQuery()) { previous = rows.next() ? rows.getInt(1) : 0; }
            }
            int level = Math.addExact(previous, 1);
            try (PreparedStatement q = connection.prepareStatement(
                    "INSERT INTO prestige(player_uuid,level) VALUES(?,?) ON CONFLICT(player_uuid) DO UPDATE SET level=excluded.level")) {
                q.setString(1, player.toString()); q.setInt(2, level); q.executeUpdate();
            }
            if (resetPolicy.money()) {
                try (PreparedStatement q = connection.prepareStatement("UPDATE players SET balance=?,revenue=0,expense=0 WHERE uuid=?")) {
                    q.setDouble(1, startingBalance); q.setString(2, player.toString()); q.executeUpdate();
                }
            }
            if (resetPolicy.machines()) try (PreparedStatement q = connection.prepareStatement("DELETE FROM machines WHERE owner_uuid=?")) {
                q.setString(1, player.toString()); q.executeUpdate();
            }
            if (resetPolicy.warehouse()) try (PreparedStatement q = connection.prepareStatement("DELETE FROM warehouses WHERE owner_uuid=?")) {
                q.setString(1, player.toString()); q.executeUpdate();
            }
            if (resetPolicy.energy()) try (PreparedStatement q = connection.prepareStatement("DELETE FROM player_energy WHERE owner_uuid=?")) {
                q.setString(1, player.toString()); q.executeUpdate();
            }
            if (resetPolicy.research()) try (PreparedStatement q = connection.prepareStatement("DELETE FROM research WHERE player_uuid=?")) {
                q.setString(1, player.toString()); q.executeUpdate();
            }
            if (resetPolicy.contracts()) try (PreparedStatement q = connection.prepareStatement("DELETE FROM contracts WHERE player_uuid=? AND completed=0")) {
                q.setString(1, player.toString()); q.executeUpdate();
            }
            if (resetPolicy.employees()) {
                try (PreparedStatement q = connection.prepareStatement(
                        "DELETE FROM employees WHERE company_id IN (SELECT company_id FROM company_members WHERE player_uuid=?)")) {
                    q.setString(1, player.toString()); q.executeUpdate();
                }
            }
            try (PreparedStatement q = connection.prepareStatement(
                    "INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")) {
                q.setString(1, player.toString()); q.setString(2, "PRESTIGE");
                q.setDouble(3, currentBalance); q.setString(4, "level=" + level);
                q.setLong(5, System.currentTimeMillis() / 1000); q.executeUpdate();
            }
            connection.commit();
            return new Result(true, level, "prestige-complete");
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(oldAuto);
        }
    }

    public double productionMultiplier(int level, double bonusPerLevel) {
        if (level < 0 || !Double.isFinite(bonusPerLevel) || bonusPerLevel < 0) throw new IllegalArgumentException("Invalid prestige bonus");
        return 1.0 + level * bonusPerLevel;
    }

    public record ResetPolicy(boolean money, boolean machines, boolean warehouse, boolean energy,
                              boolean research, boolean contracts, boolean employees) { }
    public record Result(boolean success, int level, String reason) { }
}
