package id.kyou.tycoon.database;

import java.sql.*;
import java.util.*;

/** SQLite repository for the geometric identity and ownership of each plot. */
public final class PlotRepository {
    private final Connection connection;
    public PlotRepository(Connection connection) { this.connection = Objects.requireNonNull(connection, "connection"); }

    public List<PlotRecord> findAll() throws SQLException {
        List<PlotRecord> out = new ArrayList<>();
        try (Statement q = connection.createStatement(); ResultSet r = q.executeQuery("SELECT * FROM plots ORDER BY id")) {
            while (r.next()) out.add(read(r));
        }
        return List.copyOf(out);
    }

    public Optional<PlotRecord> findById(int id) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT * FROM plots WHERE id=?")) {
            q.setInt(1, id);
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public Optional<PlotRecord> findOwned(UUID owner) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("SELECT * FROM plots WHERE owner_uuid=? ORDER BY id LIMIT 1")) {
            q.setString(1, owner.toString());
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public Optional<PlotRecord> findAt(String world, int x, int z) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT * FROM plots WHERE world=? AND min_x<=? AND max_x>=? AND min_z<=? AND max_z>=? LIMIT 1")) {
            q.setString(1, world); q.setInt(2, x); q.setInt(3, x); q.setInt(4, z); q.setInt(5, z);
            try (ResultSet r = q.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    /** Claim is a compare-and-set, so two callers cannot claim the same empty plot. */
    public Optional<PlotRecord> claim(int id, UUID owner) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        try (PreparedStatement q = connection.prepareStatement("UPDATE plots SET owner_uuid=? WHERE id=? AND owner_uuid IS NULL")) {
            q.setString(1, owner.toString()); q.setInt(2, id);
            if (q.executeUpdate() != 1) return Optional.empty();
        }
        return findById(id);
    }

    public boolean updateLevel(int id, int level) throws SQLException {
        if (level < 1) throw new IllegalArgumentException("Plot level must be positive");
        try (PreparedStatement q = connection.prepareStatement("UPDATE plots SET level=? WHERE id=?")) {
            q.setInt(1, level); q.setInt(2, id); return q.executeUpdate() == 1;
        }
    }

    public void upsert(PlotRecord plot) throws SQLException {
        Objects.requireNonNull(plot, "plot");
        try (PreparedStatement q = connection.prepareStatement(
                "INSERT INTO plots(id,world,min_x,max_x,min_z,max_z,owner_uuid,level,created_at) VALUES(?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET world=excluded.world,min_x=excluded.min_x,max_x=excluded.max_x," +
                "min_z=excluded.min_z,max_z=excluded.max_z,owner_uuid=excluded.owner_uuid,level=excluded.level")) {
            q.setInt(1, plot.id()); q.setString(2, plot.world()); q.setInt(3, plot.minX()); q.setInt(4, plot.maxX());
            q.setInt(5, plot.minZ()); q.setInt(6, plot.maxZ());
            q.setString(7, plot.owner() == null ? null : plot.owner().toString()); q.setInt(8, plot.level());
            q.setLong(9, plot.createdAt()); q.executeUpdate();
        }
    }

    private PlotRecord read(ResultSet r) throws SQLException {
        String owner = r.getString("owner_uuid");
        return new PlotRecord(r.getInt("id"), r.getString("world"), r.getInt("min_x"), r.getInt("max_x"),
                r.getInt("min_z"), r.getInt("max_z"), owner == null ? null : UUID.fromString(owner),
                r.getInt("level"), r.getLong("created_at"));
    }

    public record PlotRecord(int id, String world, int minX, int maxX, int minZ, int maxZ,
                             UUID owner, int level, long createdAt) {
        public PlotRecord {
            if (id < 1 || world == null || world.isBlank() || minX > maxX || minZ > maxZ || level < 1) {
                throw new IllegalArgumentException("Invalid plot record");
            }
        }
        public boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        public boolean isOwned() { return owner != null; }
        public double centerX() { return (minX + maxX + 1) / 2.0; }
        public double centerZ() { return (minZ + maxZ + 1) / 2.0; }
    }
}
