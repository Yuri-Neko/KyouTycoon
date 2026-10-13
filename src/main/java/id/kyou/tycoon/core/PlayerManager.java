package id.kyou.tycoon.core;

import id.kyou.tycoon.database.PlayerRepository;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory player profile cache backed by PlayerRepository. */
public final class PlayerManager {
    private final PlayerRepository repository;
    private final Map<UUID, PlayerRepository.PlayerRecord> cache = new ConcurrentHashMap<>();

    public PlayerManager(PlayerRepository repository) { this.repository = Objects.requireNonNull(repository, "repository"); }

    public PlayerRepository.PlayerRecord loadOrCreate(UUID uuid, String name, double startingBalance, long now) throws SQLException {
        PlayerRepository.PlayerRecord player = repository.createIfAbsent(uuid, name, startingBalance, now);
        cache.put(uuid, player);
        return player;
    }

    public Optional<PlayerRepository.PlayerRecord> find(UUID uuid) throws SQLException {
        PlayerRepository.PlayerRecord cached = cache.get(uuid);
        if (cached != null) return Optional.of(cached);
        Optional<PlayerRepository.PlayerRecord> loaded = repository.find(uuid);
        loaded.ifPresent(value -> cache.put(uuid, value));
        return loaded;
    }

    public void refresh(UUID uuid) throws SQLException {
        repository.find(uuid).ifPresentOrElse(value -> cache.put(uuid, value), () -> cache.remove(uuid));
    }

    public List<PlayerRepository.PlayerRecord> cachedPlayers() {
        return cache.values().stream().sorted(Comparator.comparing(PlayerRepository.PlayerRecord::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void invalidate(UUID uuid) { cache.remove(uuid); }
    public void clear() { cache.clear(); }
}
