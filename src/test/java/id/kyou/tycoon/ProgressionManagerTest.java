package id.kyou.tycoon;

import id.kyou.tycoon.progression.AchievementManager;
import id.kyou.tycoon.progression.PrestigeManager;
import id.kyou.tycoon.progression.ResearchManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionManagerTest {
    private Connection connection;
    private UUID player;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE players(uuid TEXT PRIMARY KEY,name TEXT,balance REAL NOT NULL,revenue REAL NOT NULL DEFAULT 0,expense REAL NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL DEFAULT 0)");
            s.executeUpdate("CREATE TABLE research(player_uuid TEXT,research_id TEXT,unlocked_at INTEGER,PRIMARY KEY(player_uuid,research_id))");
            s.executeUpdate("CREATE TABLE achievements(player_uuid TEXT,achievement_id TEXT,unlocked_at INTEGER,PRIMARY KEY(player_uuid,achievement_id))");
            s.executeUpdate("CREATE TABLE prestige(player_uuid TEXT PRIMARY KEY,level INTEGER)");
            s.executeUpdate("CREATE TABLE machines(uuid TEXT PRIMARY KEY,owner_uuid TEXT)");
            s.executeUpdate("CREATE TABLE warehouses(owner_uuid TEXT PRIMARY KEY,capacity INTEGER NOT NULL,items TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE employees(id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER,type TEXT,salary REAL,active INTEGER)");
            s.executeUpdate("CREATE TABLE companies(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,owner_uuid TEXT,revenue REAL,assets REAL,reputation INTEGER,created_at INTEGER)");
            s.executeUpdate("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT,player_uuid TEXT,type TEXT,amount REAL,note TEXT,created_at INTEGER)");
        }
        player=UUID.randomUUID();
        try(var ps=connection.prepareStatement("INSERT INTO players(uuid,name,balance) VALUES(?,?,?)")){
            ps.setString(1,player.toString());ps.setString(2,"Tester");ps.setDouble(3,1000);ps.executeUpdate();
        }
        config=new YamlConfiguration();
        config.set("research.enabled",true);
        config.set("research.basics.cost",200);
        config.set("research.basics.production_speed",0.10);
        config.set("research.basics.prerequisites",java.util.List.of());
        config.set("research.advanced.cost",500);
        config.set("research.advanced.prerequisites",java.util.List.of("basics"));
        config.set("achievements.enabled",true);
        config.set("achievements.items.first_plot.enabled",true);
        config.set("achievements.items.first_plot.name","First Plot");
        config.set("prestige.enabled",true);
        config.set("prestige.minimum_company_value",1000);
        config.set("prestige.reset.money",true);
        config.set("prestige.reset.machines",true);
        config.set("prestige.reset.warehouse",true);
        config.set("prestige.reset.research",true);
        config.set("prestige.reset.employees",true);
        config.set("economy.starting_balance",100);
        config.set("prestige.bonuses.production_per_level",0.05);
        config.set("prestige.bonuses.revenue_per_level",0.02);
    }

    @Test
    void researchChecksPrerequisitesAndDeductsMoneyAtomically() throws Exception {
        ResearchManager manager=new ResearchManager(connection,config);
        assertEquals(ResearchManager.UnlockResult.MISSING_PREREQUISITE,manager.unlock(player,"advanced",100));
        assertEquals(ResearchManager.UnlockResult.UNLOCKED,manager.unlock(player,"basics",101));
        assertTrue(manager.isUnlocked(player,"basics"));
        assertEquals(800.0,balance(),0.001);
        assertEquals(ResearchManager.UnlockResult.ALREADY_UNLOCKED,manager.unlock(player,"basics",102));
    }

    @Test
    void achievementUnlockIsIdempotent() throws Exception {
        AchievementManager manager=new AchievementManager(connection,config);
        assertTrue(manager.unlock(player,"first_plot",100));
        assertFalse(manager.unlock(player,"first_plot",101));
        assertEquals(1,manager.list(player).size());
    }

    @Test
    void prestigeUsesConfiguredThresholdAndResetsBalance() throws Exception {
        PrestigeManager manager=new PrestigeManager(connection,config);
        assertFalse(manager.prestige(player,999,100).success());
        var result=manager.prestige(player,1000,101);
        assertTrue(result.success());
        assertEquals(1,result.newLevel());
        assertEquals(100.0,balance(),0.001);
        assertEquals(0.05,manager.productionBonus(player),0.001);
    }

    private double balance() throws Exception {
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT balance FROM players WHERE uuid='"+player+"'")){
            assertTrue(r.next());return r.getDouble(1);
        }
    }
}
