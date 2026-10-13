package id.kyou.tycoon.database;

import id.kyou.tycoon.company.Company;
import id.kyou.tycoon.company.CompanyRole;

import java.sql.*;
import java.util.*;

/** SQL-only repository for company rows and membership lookup. */
public final class CompanyRepository {
    private final Connection connection;
    public CompanyRepository(Connection connection) { this.connection = Objects.requireNonNull(connection, "connection"); }

    public Optional<Company> findById(long id) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT * FROM companies WHERE id=?")) {
            q.setLong(1, id);
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public Optional<Company> findByMember(UUID uuid) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT c.* FROM companies c JOIN company_members m ON m.company_id=c.id WHERE m.player_uuid=?")) {
            q.setString(1, uuid.toString());
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public Optional<Company> findByName(String name) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT * FROM companies WHERE name=? COLLATE NOCASE")) {
            q.setString(1, name);
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public String role(long companyId, UUID uuid) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT role FROM company_members WHERE company_id=? AND player_uuid=?")) {
            q.setLong(1, companyId); q.setString(2, uuid.toString());
            try (ResultSet r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    public boolean addMember(long companyId, UUID uuid, CompanyRole role, long now) throws SQLException {
        Objects.requireNonNull(role, "role");
        if (findByMember(uuid).isPresent()) return false;
        try (PreparedStatement q = connection.prepareStatement(
                "INSERT INTO company_members(company_id,player_uuid,role,joined_at) VALUES(?,?,?,?)")) {
            q.setLong(1, companyId); q.setString(2, uuid.toString());
            q.setString(3, role == CompanyRole.CEO ? CompanyRole.WORKER.name() : role.name()); q.setLong(4, now);
            return q.executeUpdate() == 1;
        } catch (SQLException e) {
            if (e.getErrorCode() == 19) return false;
            throw e;
        }
    }

    private Company read(ResultSet r) throws SQLException {
        return new Company(r.getLong("id"), r.getString("name"), UUID.fromString(r.getString("owner_uuid")),
                r.getDouble("revenue"), r.getDouble("assets"), r.getInt("reputation"), r.getLong("created_at"));
    }
}
