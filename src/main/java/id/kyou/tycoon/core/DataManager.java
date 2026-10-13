package id.kyou.tycoon.core;

import id.kyou.tycoon.database.*;
import java.sql.Connection;
import java.util.Objects;

/** Shared data-access composition root for runtime and test usage. */
public final class DataManager {
    private final Connection connection;
    private final PlayerRepository players;
    private final PlotRepository plots;
    private final MachineRepository machines;
    private final CompanyRepository companies;

    public DataManager(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.players = new PlayerRepository(connection);
        this.plots = new PlotRepository(connection);
        this.machines = new MachineRepository(connection);
        this.companies = new CompanyRepository(connection);
    }

    public Connection connection() { return connection; }
    public PlayerRepository players() { return players; }
    public PlotRepository plots() { return plots; }
    public MachineRepository machines() { return machines; }
    public CompanyRepository companies() { return companies; }
}
