package id.kyou.tycoon;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

final class Database {
    private final KyouTycoon plugin;
    private Connection c;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"KyouTycoon-SQLite");t.setDaemon(true);return t;});
    Database(KyouTycoon plugin){this.plugin=plugin;}
    void open() throws SQLException{
        File f=new File(plugin.getDataFolder(),plugin.getConfig().getString("database.file","kyoutycoon.db"));
        c=DriverManager.getConnection("jdbc:sqlite:"+f.getAbsolutePath());
        try(Statement s=c.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=NORMAL");s.execute("PRAGMA foreign_keys=ON");}
        try(Statement s=c.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,balance REAL NOT NULL,revenue REAL NOT NULL DEFAULT 0,expense REAL NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS plots(id INTEGER PRIMARY KEY,world TEXT NOT NULL,min_x INTEGER,max_x INTEGER,min_z INTEGER,max_z INTEGER,owner_uuid TEXT,level INTEGER,created_at INTEGER)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS warehouses(owner_uuid TEXT PRIMARY KEY,capacity INTEGER NOT NULL,items TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS machines(uuid TEXT PRIMARY KEY,owner_uuid TEXT,world TEXT,x INTEGER,y INTEGER,z INTEGER,machine_type TEXT,level INTEGER,state TEXT,progress REAL,created_at INTEGER,last_processed INTEGER)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS companies(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,owner_uuid TEXT,revenue REAL DEFAULT 0,assets REAL DEFAULT 0,reputation INTEGER DEFAULT 50,created_at INTEGER)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS company_members(company_id INTEGER,player_uuid TEXT UNIQUE,role TEXT,joined_at INTEGER,PRIMARY KEY(company_id,player_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS employees(id INTEGER PRIMARY KEY AUTOINCREMENT,company_id INTEGER,type TEXT,salary REAL,active INTEGER DEFAULT 1)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS research(player_uuid TEXT,research_id TEXT,unlocked_at INTEGER,PRIMARY KEY(player_uuid,research_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS contracts(id INTEGER PRIMARY KEY AUTOINCREMENT,player_uuid TEXT,contract_id TEXT,progress TEXT,expires_at INTEGER,completed INTEGER DEFAULT 0)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS achievements(player_uuid TEXT,achievement_id TEXT,unlocked_at INTEGER,PRIMARY KEY(player_uuid,achievement_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS transactions(id INTEGER PRIMARY KEY AUTOINCREMENT,player_uuid TEXT,type TEXT,amount REAL,note TEXT,created_at INTEGER)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS market_prices(item TEXT PRIMARY KEY,base_price REAL,multiplier REAL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS prestige(player_uuid TEXT PRIMARY KEY,level INTEGER)");
        }
    }
    Connection c(){return c;} ExecutorService executor(){return executor;}
    void close(){executor.shutdown();try{if(c!=null)c.close();}catch(SQLException ignored){}}
}
