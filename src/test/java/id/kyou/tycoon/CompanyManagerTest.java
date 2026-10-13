package id.kyou.tycoon;

import id.kyou.tycoon.company.CompanyManager;
import id.kyou.tycoon.company.CompanyRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompanyManagerTest {
    private Connection connection;
    private CompanyManager manager;
    private UUID owner;

    @BeforeEach
    void setUp() throws Exception {
        connection=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement=connection.createStatement()){
            statement.executeUpdate("CREATE TABLE companies(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,owner_uuid TEXT,revenue REAL DEFAULT 0,assets REAL DEFAULT 0,reputation INTEGER DEFAULT 50,created_at INTEGER)");
            statement.executeUpdate("CREATE TABLE company_members(company_id INTEGER,player_uuid TEXT UNIQUE,role TEXT,joined_at INTEGER,PRIMARY KEY(company_id,player_uuid))");
        }
        owner=UUID.randomUUID();
        manager=new CompanyManager(connection,3);
    }

    @Test
    void createSetsOwnerAsCeoAndAddsMembersWithinLimit() throws Exception {
        var company=manager.create(owner,"Kyou Industries",1000);
        assertTrue(company.isPresent());
        assertEquals(owner,company.get().ownerUuid());
        assertEquals("CEO",manager.findRole(company.get().id(),owner));
        assertTrue(manager.addMember(company.get().id(),owner,UUID.randomUUID(),CompanyRole.WORKER,1001));
        assertTrue(manager.addMember(company.get().id(),owner,UUID.randomUUID(),CompanyRole.MANAGER,1002));
        assertFalse(manager.addMember(company.get().id(),owner,UUID.randomUUID(),CompanyRole.WORKER,1003));
        assertEquals(3,manager.members(company.get().id()).size());
    }

    @Test
    void membersCannotJoinTwoCompaniesAndCeoCannotLeave() throws Exception {
        var one=manager.create(owner,"First Company",1000).orElseThrow();
        UUID member=UUID.randomUUID();
        assertTrue(manager.addMember(one.id(),owner,member,CompanyRole.WORKER,1001));
        assertFalse(manager.leave(owner));
        var two=manager.create(UUID.randomUUID(),"Second Company",1002).orElseThrow();
        assertFalse(manager.addMember(two.id(),two.ownerUuid(),member,CompanyRole.WORKER,1003));
        assertTrue(manager.leave(member));
        assertTrue(manager.addMember(two.id(),two.ownerUuid(),member,CompanyRole.WORKER,1004));
    }

    @Test
    void companyNameValidationRejectsUnsafeOrOverlongNames() {
        assertThrows(IllegalArgumentException.class,()->manager.create(owner,"x",1000));
        assertThrows(IllegalArgumentException.class,()->manager.create(owner,"invalid/slug",1000));
        assertThrows(IllegalArgumentException.class,()->manager.create(owner,"x".repeat(25),1000));
    }
}
