package id.kyou.tycoon.economy;

import org.bukkit.Material;

import java.sql.*;
import java.util.*;

/** Timed, persistent contracts; all rewards are committed atomically in SQLite. */
public final class ContractManager {
    private final Connection connection;
    private final Map<String, ContractDefinition> definitions;
    private final int reputationMinimum;
    private final int reputationMaximum;

    public ContractManager(Connection connection, Collection<ContractDefinition> definitions,
                           int reputationMinimum, int reputationMaximum) {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (reputationMinimum > reputationMaximum) throw new IllegalArgumentException("Invalid reputation bounds");
        Map<String, ContractDefinition> loaded = new LinkedHashMap<>();
        for (ContractDefinition definition : definitions) {
            if (loaded.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate contract: " + definition.id());
            }
        }
        this.definitions = Collections.unmodifiableMap(loaded);
        this.reputationMinimum = reputationMinimum;
        this.reputationMaximum = reputationMaximum;
    }

    public List<ContractDefinition> definitions() { return List.copyOf(definitions.values()); }

    public boolean accept(UUID player, String contractId, long now) throws SQLException {
        Objects.requireNonNull(player, "player");
        ContractDefinition definition = definitions.get(contractId);
        if (definition == null) return false;
        synchronized (connection) {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM contracts WHERE player_uuid=? AND contract_id=? AND completed=0 AND expires_at>=? LIMIT 1")) {
                check.setString(1, player.toString());
                check.setString(2, contractId);
                check.setLong(3, now);
                try (ResultSet rows = check.executeQuery()) { if (rows.next()) return false; }
            }
            long expiresAt = saturatingAdd(now, definition.durationSeconds());
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO contracts(player_uuid,contract_id,progress,expires_at,completed) VALUES(?,?,?,?,0)")) {
                insert.setString(1, player.toString());
                insert.setString(2, contractId);
                insert.setString(3, encodeProgress(Map.of()));
                insert.setLong(4, expiresAt);
                return insert.executeUpdate() == 1;
            }
        }
    }

    /** Adds a produced amount to each matching active contract for this player. */
    public int recordProduction(UUID player, Material material, int amount, long now) throws SQLException {
        if (player == null || material == null || amount < 1) return 0;
        synchronized (connection) {
            List<ActiveContract> active = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id,contract_id,progress,expires_at FROM contracts WHERE player_uuid=? AND completed=0 AND expires_at>=?")) {
                query.setString(1, player.toString());
                query.setLong(2, now);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) active.add(new ActiveContract(rows.getLong("id"), rows.getString("contract_id"),
                            decodeProgress(rows.getString("progress")), rows.getLong("expires_at")));
                }
            }
            int updatedRows = 0;
            for (ActiveContract contract : active) {
                ContractDefinition definition = definitions.get(contract.contractId());
                if (definition == null) continue;
                Integer required = definition.requirements().get(material);
                if (required == null) continue;
                int current = contract.progress().getOrDefault(material, 0);
                int next = (int) Math.min((long) required, (long) current + amount);
                contract.progress().put(material, next);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE contracts SET progress=? WHERE id=? AND completed=0")) {
                    update.setString(1, encodeProgress(contract.progress()));
                    update.setLong(2, contract.id());
                    updatedRows += update.executeUpdate();
                }
            }
            return updatedRows;
        }
    }

    public Optional<Completion> complete(UUID player, String contractId, long now) throws SQLException {
        if (player == null) return Optional.empty();
        ContractDefinition definition = definitions.get(contractId);
        if (definition == null) return Optional.empty();
        synchronized (connection) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ActiveContract active = findActive(player, contractId);
                if (active == null) {
                    connection.rollback();
                    return Optional.empty();
                }
                if (now > active.expiresAt()) {
                    try (PreparedStatement update = connection.prepareStatement("UPDATE contracts SET completed=2 WHERE id=? AND completed=0")) {
                        update.setLong(1, active.id());
                        update.executeUpdate();
                    }
                    adjustCompanyReputation(player, -10);
                    connection.commit();
                    return Optional.of(new Completion(false, 0, "expired"));
                }
                for (Map.Entry<Material, Integer> requirement : definition.requirements().entrySet()) {
                    if (active.progress().getOrDefault(requirement.getKey(), 0) < requirement.getValue()) {
                        connection.rollback();
                        return Optional.of(new Completion(false, 0, "requirements-incomplete"));
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE contracts SET completed=1 WHERE id=? AND completed=0")) {
                    update.setLong(1, active.id());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                try (PreparedStatement reward = connection.prepareStatement(
                        "UPDATE players SET balance=balance+?,revenue=revenue+? WHERE uuid=?")) {
                    reward.setDouble(1, definition.reward());
                    reward.setDouble(2, definition.reward());
                    reward.setString(3, player.toString());
                    if (reward.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.of(new Completion(false, 0, "player-not-found"));
                    }
                }
                try (PreparedStatement transaction = connection.prepareStatement(
                        "INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")) {
                    transaction.setString(1, player.toString());
                    transaction.setString(2, "CONTRACT_REWARD");
                    transaction.setDouble(3, definition.reward());
                    transaction.setString(4, contractId);
                    transaction.setLong(5, now);
                    transaction.executeUpdate();
                }
                adjustCompanyReputation(player, definition.reputationReward());
                connection.commit();
                return Optional.of(new Completion(true, definition.reward(), "completed"));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public List<ContractStatus> active(UUID player, long now) throws SQLException {
        List<ContractStatus> result = new ArrayList<>();
        synchronized (connection) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT contract_id,progress,expires_at FROM contracts WHERE player_uuid=? AND completed=0 AND expires_at>=? ORDER BY id")) {
                query.setString(1, player.toString());
                query.setLong(2, now);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString("contract_id");
                        Map<Material, Integer> progress = decodeProgress(rows.getString("progress"));
                        ContractDefinition definition = definitions.get(id);
                        boolean done = definition != null && definition.requirements().entrySet().stream()
                                .allMatch(e -> progress.getOrDefault(e.getKey(), 0) >= e.getValue());
                        result.add(new ContractStatus(id, progress, rows.getLong("expires_at"), done));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public int expireDue(long now) throws SQLException {
        synchronized (connection) {
            List<UUID> owners = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT DISTINCT player_uuid FROM contracts WHERE completed=0 AND expires_at<?")) {
                query.setLong(1, now);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) owners.add(UUID.fromString(rows.getString(1)));
                }
            }
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE contracts SET completed=2 WHERE completed=0 AND expires_at<?")) {
                update.setLong(1, now);
                int count = update.executeUpdate();
                for (UUID owner : owners) adjustCompanyReputation(owner, -10);
                connection.commit();
                return count;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    private ActiveContract findActive(UUID player, String contractId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,contract_id,progress,expires_at FROM contracts WHERE player_uuid=? AND contract_id=? AND completed=0 ORDER BY id DESC LIMIT 1")) {
            query.setString(1, player.toString());
            query.setString(2, contractId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new ActiveContract(rows.getLong("id"), rows.getString("contract_id"),
                        decodeProgress(rows.getString("progress")), rows.getLong("expires_at")) : null;
            }
        }
    }

    private void adjustCompanyReputation(UUID player, int delta) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT c.id,c.reputation FROM companies c JOIN company_members m ON m.company_id=c.id WHERE m.player_uuid=?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return;
                long companyId = rows.getLong(1);
                long changed = (long) rows.getInt(2) + delta;
                int reputation = (int) Math.max(reputationMinimum, Math.min(reputationMaximum, changed));
                try (PreparedStatement update = connection.prepareStatement("UPDATE companies SET reputation=? WHERE id=?")) {
                    update.setInt(1, reputation);
                    update.setLong(2, companyId);
                    update.executeUpdate();
                }
            }
        }
    }

    private static String encodeProgress(Map<Material, Integer> progress) {
        return progress.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
                .map(e -> e.getKey().name() + "=" + e.getValue()).reduce((a, b) -> a + ";" + b).orElse("");
    }

    private static Map<Material, Integer> decodeProgress(String encoded) {
        Map<Material, Integer> progress = new EnumMap<>(Material.class);
        if (encoded == null || encoded.isBlank()) return progress;
        for (String item : encoded.split(";")) {
            int divider = item.indexOf('=');
            if (divider < 1) continue;
            Material material = Material.matchMaterial(item.substring(0, divider));
            if (material == null) continue;
            try {
                int amount = Integer.parseInt(item.substring(divider + 1));
                if (amount > 0) progress.put(material, amount);
            } catch (NumberFormatException ignored) { }
        }
        return progress;
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    public record ContractDefinition(String id, Map<Material, Integer> requirements,
                                     double reward, int reputationReward, long durationSeconds) {
        public ContractDefinition {
            if (id == null || !id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid contract ID");
            if (requirements == null || requirements.isEmpty()) throw new IllegalArgumentException("Requirements cannot be empty");
            Map<Material, Integer> copy = new EnumMap<>(Material.class);
            requirements.forEach((material, amount) -> {
                if (material == null || amount == null || amount < 1) throw new IllegalArgumentException("Invalid contract requirement");
                copy.put(material, amount);
            });
            requirements = Collections.unmodifiableMap(copy);
            if (!Double.isFinite(reward) || reward < 0) throw new IllegalArgumentException("Reward must be finite and non-negative");
            if (durationSeconds < 1) throw new IllegalArgumentException("Contract duration must be positive");
        }
    }
    public record Completion(boolean success, double reward, String reason) {}
    public record ContractStatus(String id, Map<Material, Integer> progress, long expiresAt, boolean readyToComplete) {
        public ContractStatus { progress = Map.copyOf(progress); }
    }
    private record ActiveContract(long id, String contractId, Map<Material, Integer> progress, long expiresAt) { }
}
