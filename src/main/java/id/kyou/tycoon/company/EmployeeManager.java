package id.kyou.tycoon.company;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Employee hiring and periodic salary settlement backed by SQLite. */
public final class EmployeeManager {
    private final Connection connection;

    public EmployeeManager(Connection connection) {
        this.connection = java.util.Objects.requireNonNull(connection, "connection");
    }

    public List<Employee> list(long companyId) throws SQLException {
        List<Employee> employees = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,company_id,type,salary,active FROM employees WHERE company_id=? ORDER BY id")) {
            statement.setLong(1, companyId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) employees.add(read(rows));
            }
        }
        return List.copyOf(employees);
    }

    public Employee hire(long companyId, UUID actor, String type, double salary) throws SQLException {
        if (!Double.isFinite(salary) || salary < 0) throw new IllegalArgumentException("Salary must be finite and non-negative");
        if (actor == null || type == null || type.isBlank()) throw new IllegalArgumentException("Actor and type are required");
        String role;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM company_members WHERE company_id=? AND player_uuid=?")) {
            statement.setLong(1, companyId);
            statement.setString(2, actor.toString());
            try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) return null; role = rows.getString(1); }
        }
        if (!CompanyRole.parse(role).canManageEmployees()) return null;
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO employees(company_id,type,salary,active) VALUES(?,?,?,1)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, companyId);
            statement.setString(2, type.trim().toLowerCase(java.util.Locale.ROOT));
            statement.setDouble(3, salary);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Missing employee ID"); id = keys.getLong(1); }
        }
        return new Employee(id, companyId, type.trim().toLowerCase(java.util.Locale.ROOT), salary, true);
    }

    public boolean setActive(long companyId, UUID actor, long employeeId, boolean active) throws SQLException {
        String role;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM company_members WHERE company_id=? AND player_uuid=?")) {
            statement.setLong(1, companyId);
            statement.setString(2, actor.toString());
            try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) return false; role = rows.getString(1); }
        }
        if (!CompanyRole.parse(role).canManageEmployees()) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE employees SET active=? WHERE company_id=? AND id=?")) {
            statement.setInt(1, active ? 1 : 0);
            statement.setLong(2, companyId);
            statement.setLong(3, employeeId);
            return statement.executeUpdate() == 1;
        }
    }

    public PayrollResult payPayroll(long companyId, long now) throws SQLException {
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            UUID owner;
            try (PreparedStatement statement = connection.prepareStatement("SELECT owner_uuid FROM companies WHERE id=?")) {
                statement.setLong(1, companyId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return new PayrollResult(false, 0, "company-not-found"); }
                    owner = UUID.fromString(rows.getString(1));
                }
            }
            double total = 0;
            int count = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT salary FROM employees WHERE company_id=? AND active=1")) {
                statement.setLong(1, companyId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) { total += rows.getDouble(1); count++; }
                }
            }
            if (!Double.isFinite(total)) throw new SQLException("Invalid payroll total");
            double balance;
            try (PreparedStatement statement = connection.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return new PayrollResult(false, count, "owner-not-found"); }
                    balance = rows.getDouble(1);
                }
            }
            if (balance < total) { connection.rollback(); return new PayrollResult(false, count, "insufficient-funds"); }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE players SET balance=balance-?,expense=expense+? WHERE uuid=?")) {
                statement.setDouble(1, total); statement.setDouble(2, total); statement.setString(3, owner.toString()); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")) {
                statement.setString(1, owner.toString()); statement.setString(2, "EMPLOYEE_PAYROLL");
                statement.setDouble(3, total); statement.setString(4, count + " employees"); statement.setLong(5, now); statement.executeUpdate();
            }
            connection.commit();
            return new PayrollResult(true, count, "paid");
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    private Employee read(ResultSet rows) throws SQLException {
        return new Employee(rows.getLong("id"), rows.getLong("company_id"),
                rows.getString("type"), rows.getDouble("salary"), rows.getInt("active") == 1);
    }

    public record PayrollResult(boolean paid, int employeesPaid, String reason) {}
}
