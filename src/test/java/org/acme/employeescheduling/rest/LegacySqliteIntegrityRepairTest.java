package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in, strictly verified repair of orphaned location-skill associations in legacy SQLite
 * databases. It does not run in the regular suite.
 */
@EnabledIfSystemProperty(named = "repair.sqlite.path", matches = ".+")
class LegacySqliteIntegrityRepairTest {

    private static final String CONFIRMATION = "DELETE_LOCATION_SKILL_ORPHANS";

    @Test
    void deleteOnlyTheExplicitlyConfirmedLocationSkillOrphans() throws Exception {
        assertEquals(CONFIRMATION, System.getProperty("repair.confirm"),
                "Explicit repair confirmation is required");
        Set<Integer> expectedIds = parseIds(System.getProperty("repair.expected-ids", ""));
        Path database = Path.of(System.getProperty("repair.sqlite.path")).toAbsolutePath().normalize();
        String url = "jdbc:sqlite:file:" + database.toString().replace('\\', '/') + "?mode=rw";

        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=ON");
                    statement.execute("PRAGMA busy_timeout=5000");
                }
                assertEquals("ok", pragmaScalar(connection, "PRAGMA integrity_check"));
                Set<Integer> actualIds = orphanIds(connection);
                assertEquals(expectedIds, actualIds,
                        "The orphan set changed; aborting without deleting anything");

                int deleted;
                try (Statement statement = connection.createStatement()) {
                    deleted = statement.executeUpdate(
                            "DELETE FROM location_skills WHERE id IN ("
                                    + expectedIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-1")
                                    + ") AND NOT EXISTS (SELECT 1 FROM locations l WHERE l.id=location_skills.location_id)");
                }
                assertEquals(expectedIds.size(), deleted);
                assertEquals(Set.of(), orphanIds(connection));
                assertEquals(0, foreignKeyViolationCount(connection));
                assertEquals("ok", pragmaScalar(connection, "PRAGMA integrity_check"));
                connection.commit();
                System.out.println("Legacy SQLite repair committed: removed location_skills " + expectedIds);
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static Set<Integer> orphanIds(Connection connection) throws Exception {
        Set<Integer> ids = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT ls.id FROM location_skills ls LEFT JOIN locations l ON l.id=ls.location_id "
                                + "WHERE l.id IS NULL ORDER BY ls.id")) {
            while (rows.next()) ids.add(rows.getInt(1));
        }
        return ids;
    }

    private static int foreignKeyViolationCount(Connection connection) throws Exception {
        int count = 0;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (rows.next()) count++;
        }
        return count;
    }

    private static String pragmaScalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Set<Integer> parseIds(String value) {
        Set<Integer> ids = new TreeSet<>();
        if (value == null || value.isBlank()) return ids;
        for (String item : value.split(",")) ids.add(Integer.parseInt(item.trim()));
        return ids;
    }
}
