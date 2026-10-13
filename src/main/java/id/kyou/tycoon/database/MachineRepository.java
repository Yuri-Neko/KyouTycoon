package id.kyou.tycoon.database;

import java.sql.*;
import java.util.*;

/** Persistence repository for machine location, owner and production timestamps. */
public final class MachineRepository {
    private final Connection connection;
    public MachineRepository(Connection connection) { this.connection = Objects.requireNonNull(connection, "connection"); }

    public List<MachineRecord> findAll() throws SQLException {
        List<MachineRecord> out = new ArrayList<>();
        try (Statement q = connection.createStatement(); ResultSet r = q.executeQuery("SELECT * FROM machines ORDER BY created_at")) {
            while (r.next()) out.add(read(r));
        }
        return List.copyOf(out);
    }

    public List<MachineRecord> findOwned(UUID owner) throws SQLException {
        List<MachineRecord> out = new ArrayList<>();
        try (PreparedStatement q = connection.prepareStatement("SELECT * FROM machines WHERE owner_uuid=? ORDER BY created_at")) {
            q.setString(1, owner.toString());
            try (ResultSet r = q.executeQuery()) { while (r.next()) out.add(read(r)); }
        }
        return List.copyOf(out);
    }

    public Optional<MachineRecord> findById(UUID id) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT * FROM machines WHERE uuid=?")) {
            q.setString(1, id.toString());
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public void save(MachineRecord m) throws SQLException {
        Objects.requireNonNull(m, "machine");
        try (PreparedStatement q = connection.prepareStatement(
                "INSERT INTO machines(uuid,owner_uuid,world,x,y,z,machine_type,level,state,progress,created_at,last_processed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(uuid) DO UPDATE SET owner_uuid=excluded.owner_uuid,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z," +
                "machine_type=excluded.machine_type,level=excluded.level,state=excluded.state,progress=excluded.progress,last_processed=excluded.last_processed")) {
            q.setString(1, m.uuid().toString()); q.setString(2, m.owner().toString()); q.setString(3, m.world());
            q.setInt(4, m.x()); q.setInt(5, m.y()); q.setInt(6, m.z()); q.setString(7, m.type());
            q.setInt(8, m.level()); q.setString(9, m.state()); q.setDouble(10, m.progress());
            q.setLong(11, m.createdAt()); q.setLong(12, m.lastProcessed()); q.executeUpdate();
        }
    }

    public boolean delete(UUID id) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("DELETE FROM machines WHERE uuid=?")) {
            q.setString(1, id.toString()); return q.executeUpdate() == 1;
        }
    }

    public int deleteOwned(UUID owner) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("DELETE FROM machines WHERE owner_uuid=?")) {
            q.setString(1, owner.toString()); return q.executeUpdate();
        }
    }

    private MachineRecord read(ResultSet r) throws SQLException {
        return new MachineRecord(UUID.fromString(r.getString("uuid")), UUID.fromString(r.getString("owner_uuid")),
                r.getString("world"), r.getInt("x"), r.getInt("y"), r.getInt("z"), r.getString("machine_type"),
                r.getInt("level"), r.getString("state"), r.getDouble("progress"), r.getLong("created_at"), r.getLong("last_processed"));
    }

    public record MachineRecord(UUID uuid, UUID owner, String world, int x, int y, int z, String type,
                                int level, String state, double progress, long createdAt, long lastProcessed) {
        public MachineRecord {
            Objects.requireNonNull(uuid, "uuid"); Objects.requireNonNull(owner, "owner");
            if (world == null || world.isBlank() || type == null || type.isBlank() || state == null || state.isBlank()) {
                throw new IllegalArgumentException("Machine world, type and state are required");
            }
            if (level < 1 || !Double.isFinite(progress) || progress < 0 || createdAt < 0 || lastProcessed < 0) {
                throw new IllegalArgumentException("Invalid machine values");
            }
        }
    }
}
