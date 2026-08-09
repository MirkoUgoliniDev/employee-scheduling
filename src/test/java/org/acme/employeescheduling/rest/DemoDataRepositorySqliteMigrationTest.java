package org.acme.employeescheduling.rest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoDataRepositorySqliteMigrationTest {

    @Test
    void hybridSkillTypeSeedsEveryRequiredLabelColumn() throws Exception {
        withDatabase("hybrid-skill-type", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE skill_type(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "name TEXT NOT NULL,description TEXT NOT NULL)");
            }
            DemoDataRepository.ensureSkillTypeTable(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id,name,description FROM skill_type ORDER BY id")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("id"));
                assertEquals("required", result.getString("name"));
                assertEquals("required", result.getString("description"));
                assertTrue(result.next());
                assertEquals(2, result.getInt("id"));
                assertEquals("optional", result.getString("name"));
                assertEquals("optional", result.getString("description"));
            }
        });
    }

    @Test
    void unseedableSkillTypeFailsInsteadOfIgnoringConstraint() throws Exception {
        withDatabase("unseedable-skill-type", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE skill_type(id INTEGER PRIMARY KEY,description TEXT,tenant TEXT NOT NULL)");
            }
            SQLException failure = assertThrows(SQLException.class,
                    () -> DemoDataRepository.ensureSkillTypeTable(connection));
            assertTrue(failure.getMessage().contains("unsupported required column tenant"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM skill_type"));
        });
    }

    @Test
    void partialForeignKeysAreRebuiltWithCascadeAndSequenceIsPreserved() throws Exception {
        withDatabase("partial-shift-skills-fk", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("CREATE TABLE shifts(id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE skills(id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE skill_type(id INTEGER PRIMARY KEY,description TEXT)");
                statement.execute("INSERT INTO shifts VALUES(1)");
                statement.execute("INSERT INTO skills VALUES(2)");
                statement.execute("INSERT INTO skill_type VALUES(1,'required'),(2,'optional'),(3,'legacy')");
                statement.execute("CREATE TABLE shift_skills(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "shift_id INTEGER NOT NULL,skill_id INTEGER NOT NULL,skill_type_id INTEGER NOT NULL,"
                        + "FOREIGN KEY(shift_id) REFERENCES shifts(id))");
                statement.execute("INSERT INTO shift_skills VALUES(7,1,2,1)");
                statement.execute("INSERT INTO shift_skills VALUES(100,1,2,3)");
            }

            DemoDataRepository.migrateShiftSkillsForeignKey(connection);

            Map<String, String> targets = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA foreign_key_list('shift_skills')")) {
                while (result.next()) {
                    targets.put(result.getString("from"), result.getString("table"));
                    assertEquals("CASCADE", result.getString("on_delete"));
                }
            }
            assertEquals(Map.of("shift_id", "shifts", "skill_id", "skills",
                    "skill_type_id", "skill_type"), targets);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM shift_skills"));
            assertEquals(7, scalar(connection, "SELECT id FROM shift_skills"));
            assertEquals(100, scalar(connection,
                    "SELECT seq FROM sqlite_sequence WHERE name='shift_skills'"));
        });
    }

    @Test
    void unsupportedMetadataStopsBeforeDroppingOriginalTable() throws Exception {
        withDatabase("custom-shift-skills-metadata", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE shifts(id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE skills(id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE skill_type(id INTEGER PRIMARY KEY,description TEXT)");
                statement.execute("CREATE TABLE shift_skills(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "shift_id INTEGER,skill_id INTEGER,skill_type_id INTEGER)");
                statement.execute("CREATE TRIGGER custom_shift_skill_audit AFTER INSERT ON shift_skills BEGIN SELECT 1; END");
                statement.execute("INSERT INTO shift_skills VALUES(9,1,2,1)");
            }
            SQLException failure = assertThrows(SQLException.class,
                    () -> DemoDataRepository.migrateShiftSkillsForeignKey(connection));
            assertTrue(failure.getMessage().contains("custom_shift_skill_audit"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM shift_skills WHERE id=9"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sqlite_master "
                    + "WHERE type='trigger' AND name='custom_shift_skill_audit'"));
        });
    }

    @Test
    void globalForeignKeyAuditLeavesLegacyOrphansUntouched() throws Exception {
        withDatabase("global-fk-audit", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("CREATE TABLE locations(id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE skills(id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE skill_type(id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE location_skills(id INTEGER PRIMARY KEY,location_id INTEGER,"
                        + "skill_id INTEGER,skill_type_id INTEGER,"
                        + "FOREIGN KEY(location_id) REFERENCES locations(id),"
                        + "FOREIGN KEY(skill_id) REFERENCES skills(id),"
                        + "FOREIGN KEY(skill_type_id) REFERENCES skill_type(id))");
                statement.execute("INSERT INTO skills VALUES(1)");
                statement.execute("INSERT INTO skill_type VALUES(1)");
                statement.execute("INSERT INTO location_skills VALUES(44,999,1,1)");
            }
            DemoDataRepository.validateGlobalForeignKeys(connection);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM location_skills WHERE id=44"));
        });
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void withDatabase(String name, SqlConsumer test) throws Exception {
        Path database = Path.of("target", name + ".db").toAbsolutePath();
        Files.deleteIfExists(database);
        Files.deleteIfExists(Path.of(database + "-wal"));
        Files.deleteIfExists(Path.of(database + "-shm"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            test.accept(connection);
        } finally {
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(Path.of(database + "-shm"));
        }
    }

    @FunctionalInterface
    private interface SqlConsumer {
        void accept(Connection connection) throws Exception;
    }
}
