package id.kyou.tycoon;

import id.kyou.tycoon.economy.ContractManager;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContractManagerTest {
    private Connection connection;
    private UUID player;
    private ContractManager manager;

    @BeforeEach
    void setUp() throws Exception {
        connection=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement s=connection.createStatement()){
            s.executeUpdate("CREATE TABLE players(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,balance REAL NOT NULL,revenue REAL NOT NULL DEFAULT 0,expense REAL NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL DEFAULT 0)");
            s.executeUpdate("CREATE TABLE contracts(id INTEGER PRIMARY KEY AUTOINCREMENT,player_uuid TEXT,contract_id TEXT,progress TEXT,expires_at INTEGER,completed INTEGER DEFAULT 0)");
            s.executeUpdate("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT,player_uuid TEXT,type TEXT,amount REAL,note TEXT,created_at INTEGER)");
            s.executeUpdate("CREATE TABLE companies(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,owner_uuid TEXT,revenue REAL DEFAULT 0,assets REAL DEFAULT 0,reputation INTEGER DEFAULT 50,created_at INTEGER)");
            s.executeUpdate("CREATE TABLE company_members(company_id INTEGER,player_uuid TEXT UNIQUE,role TEXT,joined_at INTEGER,PRIMARY KEY(company_id,player_uuid))");
        }
        player=UUID.randomUUID();
        try(var p=connection.prepareStatement("INSERT INTO players(uuid,name,balance) VALUES(?,?,?)")){
            p.setString(1,player.toString());p.setString(2,"Tester");p.setDouble(3,500);p.executeUpdate();
        }
        try(var c=connection.prepareStatement("INSERT INTO companies(name,owner_uuid,revenue,assets,reputation,created_at) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            c.setString(1,"Kyou Corp");c.setString(2,player.toString());c.setDouble(3,0);c.setDouble(4,0);c.setInt(5,50);c.setLong(6,1);c.executeUpdate();
            try(var key=c.getGeneratedKeys()){
                assertTrue(key.next());
                try(var m=connection.prepareStatement("INSERT INTO company_members(company_id,player_uuid,role,joined_at) VALUES(?,?,?,?)")){
                    m.setLong(1,key.getLong(1));m.setString(2,player.toString());m.setString(3,"CEO");m.setLong(4,1);m.executeUpdate();
                }
            }
        }
        manager=new ContractManager(connection,java.util.List.of(
                new ContractManager.ContractDefinition("bakery",Map.of(Material.BREAD,3),100,5,60)),0,100);
    }

    @Test
    void contractProgressCompletesAndPaysExactlyOnce() throws Exception {
        assertTrue(manager.accept(player,"bakery",100));
        assertFalse(manager.accept(player,"bakery",101));
        assertEquals(1,manager.recordProduction(player,Material.BREAD,2,110));
        assertFalse(manager.active(player,110).getFirst().readyToComplete());
        assertEquals(1,manager.recordProduction(player,Material.BREAD,1,120));
        assertTrue(manager.active(player,120).getFirst().readyToComplete());
        var complete=manager.complete(player,"bakery",121).orElseThrow();
        assertTrue(complete.success());
        assertEquals(100,complete.reward(),0.001);
        assertTrue(manager.complete(player,"bakery",122).isEmpty());
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT balance FROM players WHERE uuid='"+player+"'")){
            assertTrue(r.next());assertEquals(600,r.getDouble(1),0.001);
        }
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT reputation FROM companies WHERE name='Kyou Corp'")){
            assertTrue(r.next());assertEquals(55,r.getInt(1));
        }
    }

    @Test
    void overdueContractsExpireAndPenalizeReputation() throws Exception {
        assertTrue(manager.accept(player,"bakery",100));
        assertEquals(1,manager.expireDue(161));
        assertTrue(manager.active(player,161).isEmpty());
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT completed FROM contracts WHERE player_uuid='"+player+"'")){
            assertTrue(r.next());assertEquals(2,r.getInt(1));
        }
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT reputation FROM companies WHERE name='Kyou Corp'")){
            assertTrue(r.next());assertEquals(40,r.getInt(1));
        }
    }
}
