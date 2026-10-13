package id.kyou.tycoon.progression;

import java.sql.*;
import java.util.*;

/** Purchasable persistent research with prerequisites, debited atomically. */
public final class ResearchManager {
    private final Connection connection;
    private final Map<String, Research> research;

    public ResearchManager(Connection connection, Collection<Research> definitions) {
        this.connection = Objects.requireNonNull(connection, "connection");
        Map<String, Research> values = new LinkedHashMap<>();
        for (Research item : definitions) {
            if (values.putIfAbsent(item.id(), item) != null) throw new IllegalArgumentException("Duplicate research ID: " + item.id());
        }
        for (Research item : values.values()) {
            for (String prerequisite : item.prerequisites()) {
                if (!values.containsKey(prerequisite)) throw new IllegalArgumentException("Unknown prerequisite: " + prerequisite);
                if (prerequisite.equals(item.id())) throw new IllegalArgumentException("Research cannot require itself");
            }
        }
        this.research = Collections.unmodifiableMap(values);
        validateAcyclic(values);
    }

    public List<Research> definitions() { return List.copyOf(research.values()); }

    public Set<String> unlocked(UUID player) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (PreparedStatement q = connection.prepareStatement("SELECT research_id FROM research WHERE player_uuid=? ORDER BY research_id")) {
            q.setString(1, player.toString());
            try (ResultSet rows = q.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return Collections.unmodifiableSet(result);
    }

    public PurchaseResult purchase(UUID player, String id, long now) throws SQLException {
        Objects.requireNonNull(player, "player");
        Research item = research.get(id);
        if (item == null) return new PurchaseResult(false, "unknown-research", 0);
        boolean oldAuto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Set<String> current = unlocked(player);
            if (current.contains(id)) { connection.rollback(); return new PurchaseResult(false, "already-unlocked", 0); }
            if (!current.containsAll(item.prerequisites())) { connection.rollback(); return new PurchaseResult(false, "missing-prerequisite", 0); }
            double balance;
            try (PreparedStatement q = connection.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (ResultSet rows = q.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return new PurchaseResult(false, "player-not-found", 0); }
                    balance = rows.getDouble(1);
                }
            }
            if (balance < item.cost()) { connection.rollback(); return new PurchaseResult(false, "insufficient-funds", 0); }
            try (PreparedStatement q = connection.prepareStatement("UPDATE players SET balance=balance-?,expense=expense+? WHERE uuid=?")) {
                q.setDouble(1, item.cost()); q.setDouble(2, item.cost()); q.setString(3, player.toString()); q.executeUpdate();
            }
            try (PreparedStatement q = connection.prepareStatement("INSERT INTO research(player_uuid,research_id,unlocked_at) VALUES(?,?,?)")) {
                q.setString(1, player.toString()); q.setString(2, id); q.setLong(3, now); q.executeUpdate();
            }
            try (PreparedStatement q = connection.prepareStatement("INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")) {
                q.setString(1, player.toString()); q.setString(2, "RESEARCH_PURCHASE"); q.setDouble(3, item.cost()); q.setString(4, id); q.setLong(5, now); q.executeUpdate();
            }
            connection.commit();
            return new PurchaseResult(true, "purchased", item.cost());
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(oldAuto);
        }
    }

    public double totalProductionSpeedBonus(UUID player) throws SQLException {
        Set<String> unlocked = unlocked(player);
        double sum = 0;
        for (String id : unlocked) {
            Research item = research.get(id);
            if (item != null) sum += item.productionSpeedBonus();
        }
        return sum;
    }

    private static void validateAcyclic(Map<String, Research> all) {
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String id : all.keySet()) visit(id, all, visiting, visited);
    }

    private static void visit(String id, Map<String, Research> all, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Research prerequisite cycle at " + id);
        for (String prerequisite : all.get(id).prerequisites()) visit(prerequisite, all, visiting, visited);
        visiting.remove(id);
        visited.add(id);
    }

    public record Research(String id, String name, double cost, double productionSpeedBonus, List<String> prerequisites) {
        public Research {
            if (id == null || !id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid research ID");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Research name cannot be blank");
            if (!Double.isFinite(cost) || cost < 0) throw new IllegalArgumentException("Cost must be finite and non-negative");
            if (!Double.isFinite(productionSpeedBonus) || productionSpeedBonus < 0) throw new IllegalArgumentException("Bonus must be finite and non-negative");
            prerequisites = List.copyOf(prerequisites == null ? List.of() : prerequisites);
        }
    }
    public record PurchaseResult(boolean success, String reason, double price) { }
}
