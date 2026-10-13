package id.kyou.tycoon.company;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** SQLite-backed company lifecycle and membership operations. */
public final class CompanyManager {
    private final Connection connection;
    private final int memberLimit;

    public CompanyManager(Connection connection, int memberLimit) {
        this.connection = java.util.Objects.requireNonNull(connection, "connection");
        if (memberLimit < 1) throw new IllegalArgumentException("memberLimit must be positive");
        this.memberLimit = memberLimit;
    }

    public Optional<Company> findByMember(UUID player) throws SQLException {
        String sql = "SELECT c.* FROM companies c JOIN company_members m ON m.company_id=c.id WHERE m.player_uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readCompany(rows)) : Optional.empty();
            }
        }
    }

    public Optional<Company> findById(long companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM companies WHERE id=?")) {
            statement.setLong(1, companyId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readCompany(rows)) : Optional.empty();
            }
        }
    }

    public Optional<Company> create(UUID owner, String requestedName, long now) throws SQLException {
        if (owner == null) throw new IllegalArgumentException("owner cannot be null");
        String name = normalizeName(requestedName);
        if (findByMember(owner).isPresent()) return Optional.empty();
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO companies(name,owner_uuid,revenue,assets,reputation,created_at) VALUES(?,?,0,0,50,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name);
                statement.setString(2, owner.toString());
                statement.setLong(3, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("SQLite did not return the company ID");
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO company_members(company_id,player_uuid,role,joined_at) VALUES(?,?,?,?)")) {
                statement.setLong(1, id);
                statement.setString(2, owner.toString());
                statement.setString(3, CompanyRole.CEO.name());
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            connection.commit();
            return Optional.of(new Company(id, name, owner, 0, 0, 50, now));
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    public boolean addMember(long companyId, UUID actor, UUID target, CompanyRole role, long now) throws SQLException {
        if (actor == null || target == null || role == null) throw new IllegalArgumentException("Member parameters cannot be null");
        if (findByMember(target).isPresent()) return false;
        String actorRole = findRole(companyId, actor);
        if (!CompanyRole.parse(actorRole).canManageMembers()) return false;
        if (countMembers(companyId) >= memberLimit) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO company_members(company_id,player_uuid,role,joined_at) VALUES(?,?,?,?)")) {
            statement.setLong(1, companyId);
            statement.setString(2, target.toString());
            statement.setString(3, role == CompanyRole.CEO ? CompanyRole.WORKER.name() : role.name());
            statement.setLong(4, now);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 19) return false;
            throw exception;
        }
    }

    public boolean removeMember(long companyId, UUID actor, UUID target) throws SQLException {
        String actorRole = findRole(companyId, actor);
        if (!CompanyRole.parse(actorRole).canManageMembers() || actor.equals(target)) return false;
        String targetRole = findRole(companyId, target);
        if (CompanyRole.parse(targetRole) == CompanyRole.CEO) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM company_members WHERE company_id=? AND player_uuid=?")) {
            statement.setLong(1, companyId);
            statement.setString(2, target.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean leave(UUID player) throws SQLException {
        Optional<Company> company = findByMember(player);
        if (company.isEmpty()) return false;
        if (company.get().ownerUuid().equals(player)) return false;
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM company_members WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public List<Member> members(long companyId) throws SQLException {
        List<Member> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid,role,joined_at FROM company_members WHERE company_id=? ORDER BY joined_at,player_uuid")) {
            statement.setLong(1, companyId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Member(UUID.fromString(rows.getString("player_uuid")),
                        CompanyRole.parse(rows.getString("role")), rows.getLong("joined_at")));
            }
        }
        return List.copyOf(result);
    }

    public String findRole(long companyId, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM company_members WHERE company_id=? AND player_uuid=?")) {
            statement.setLong(1, companyId);
            statement.setString(2, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private int countMembers(long companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM company_members WHERE company_id=?")) {
            statement.setLong(1, companyId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        }
    }

    private String normalizeName(String requestedName) {
        if (requestedName == null) throw new IllegalArgumentException("Company name cannot be null");
        String name = requestedName.trim().replaceAll("\\s+", " ");
        if (name.length() < 3 || name.length() > 24 || !name.matches("[A-Za-z0-9 _-]+")) {
            throw new IllegalArgumentException("Company name must be 3-24 letters/numbers/spaces/_/-");
        }
        return name;
    }

    private Company readCompany(ResultSet rows) throws SQLException {
        return new Company(rows.getLong("id"), rows.getString("name"),
                UUID.fromString(rows.getString("owner_uuid")), rows.getDouble("revenue"),
                rows.getDouble("assets"), rows.getInt("reputation"), rows.getLong("created_at"));
    }

    public record Member(UUID uuid, CompanyRole role, long joinedAt) {}
}
