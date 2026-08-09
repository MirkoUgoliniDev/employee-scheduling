package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Copia transazionale controllata SQLite -> PostgreSQL.
 * By default, the target datasource is always restored through rollback; committing requires
 * explicit confirmation and the exact name of the
 * target database. The source is always opened in read-only mode.
 */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "population.source", matches = ".+")
class SqliteToPostgresqlPopulationTest {

    private static final List<String> TABLES = List.of(
            "structures", "skill_type", "skills", "employee_date_type", "languages", "labels",
            "specialists", "locations", "employees", "employee_dates", "employee_skills",
            "location_skills", "shifts", "shift_skills", "operator_specialist_affinity",
            "shift_template_headers", "shift_templates", "shift_template_skills", "shift_template_meta",
            "localizzazioni", "general_settings", "email_templates", "pdf_templates", "email_settings",
            "email_log", "solver_settings", "count_distribution", "demo_data_parameters");

    @Inject
    DataSource targetDataSource;

    @Test
    void existingRowsCanBeDryRunAgainstPostgresql() throws Exception {
        Path sourcePath = Path.of(System.getProperty("population.source")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(sourcePath), "SQLite source is not a regular file: " + sourcePath);
        String sqliteUrl = "jdbc:sqlite:file:" + sourcePath.toString().replace('\\', '/') + "?mode=ro";
        List<String> rejectedRows = new ArrayList<>();
        long totalSourceRows = 0;
        long totalInsertedRows = 0;

        try (Connection source = DriverManager.getConnection(sqliteUrl);
                Connection target = targetDataSource.getConnection()) {
            assertEquals("SQLite", source.getMetaData().getDatabaseProductName());
            assertEquals("PostgreSQL", target.getMetaData().getDatabaseProductName());
            boolean commitRequested = "REPLACE_TARGET_DATABASE".equals(System.getProperty("population.commit"));
            String targetDatabase = target.getCatalog();
            if (commitRequested) {
                assertEquals("I_UNDERSTAND_TRUNCATE_IS_DESTRUCTIVE",
                        System.getProperty("population.confirm-destructive"),
                        "Refusing population commit: destructive replacement was not confirmed");
                assertEquals(System.getProperty("population.expected-target-database"), targetDatabase,
                        "Refusing population commit: unexpected target database");
                assertEquals(System.getProperty("population.expected-target-url"), target.getMetaData().getURL(),
                        "Refusing population commit: unexpected target JDBC URL");
                assertTrue(targetDatabase != null && !targetDatabase.isBlank(),
                        "Refusing population commit: target database name is unavailable");
                assertEquals(System.getProperty("population.expected-source-sha256"), sha256(sourcePath),
                        "Refusing population commit: SQLite source hash changed");
                Path backupPath = Path.of(System.getProperty("population.backup-path", ""))
                        .toAbsolutePath().normalize();
                assertTrue(Files.isRegularFile(backupPath) && Files.size(backupPath) > 0,
                        "Refusing population commit: PostgreSQL backup is missing or empty");
                assertEquals(System.getProperty("population.expected-backup-sha256"), sha256(backupPath),
                        "Refusing population commit: PostgreSQL backup hash changed");
            }
            source.setAutoCommit(false);
            try (Statement sourceGuard = source.createStatement()) {
                sourceGuard.execute("PRAGMA query_only=ON");
            }
            target.setAutoCommit(false);
            boolean targetCommitted = false;
            try {
                if (commitRequested) validateOperationalTarget(target);
                try (Statement statement = target.createStatement()) {
                    statement.execute("TRUNCATE TABLE " + String.join(",", TABLES)
                            + " RESTART IDENTITY CASCADE");
                }

                for (String table : TABLES) {
                    List<String> columns = sharedColumns(source, target, table, commitRequested);
                    assertTrue(!columns.isEmpty(), "No shared columns for " + table);
                    String columnSql = columns.stream().map(SqliteToPostgresqlPopulationTest::quote)
                            .reduce((left, right) -> left + "," + right).orElseThrow();
                    String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
                    String insertSql = "INSERT INTO " + quote(table) + " (" + columnSql + ") VALUES ("
                            + placeholders + ")";
                    long sourceCount = 0;
                    long insertedCount = 0;
                    try (Statement read = source.createStatement();
                            ResultSet rows = read.executeQuery("SELECT " + columnSql + " FROM " + quote(table));
                            PreparedStatement insert = target.prepareStatement(insertSql)) {
                        while (rows.next()) {
                            sourceCount++;
                            for (int index = 0; index < columns.size(); index++)
                                insert.setObject(index + 1, rows.getObject(index + 1));
                            Savepoint rowSavepoint = target.setSavepoint();
                            try {
                                insert.executeUpdate();
                                insertedCount++;
                                target.releaseSavepoint(rowSavepoint);
                            } catch (Exception failure) {
                                target.rollback(rowSavepoint);
                                rejectedRows.add(table + "[" + rowIdentity(columns, rows) + "]: "
                                        + concise(failure.getMessage()));
                            }
                        }
                    }
                    totalSourceRows += sourceCount;
                    totalInsertedRows += insertedCount;
                    assertEquals(sourceCount - rejectedRows.stream().filter(row -> row.startsWith(table + "[")).count(),
                            insertedCount, "Unexpected inserted row count for " + table);
                    assertEquals(insertedCount, countRows(target, table), "Target row count mismatch for " + table);
                }

                synchronizeSequences(target);

                int expectedRejections = Integer.getInteger("population.expected-rejections", 0);
                if (commitRequested) assertEquals(0, expectedRejections,
                        "Committed population cannot permit rejected rows");
                System.out.println("Population " + (commitRequested ? "commit" : "dry-run") + ": source="
                        + totalSourceRows + ", inserted="
                        + totalInsertedRows + ", rejected=" + rejectedRows.size());
                assertEquals(expectedRejections, rejectedRows.size(), () ->
                        "Rows incompatible with PostgreSQL schema (" + rejectedRows.size() + "):\n"
                                + String.join("\n", rejectedRows));
                if (commitRequested) {
                    try (Statement constraints = target.createStatement()) {
                        constraints.execute("SET CONSTRAINTS ALL IMMEDIATE");
                    }
                    target.commit();
                    targetCommitted = true;
                }
            } catch (Exception | AssertionError failure) {
                target.rollback();
                throw failure;
            } finally {
                if (!targetCommitted && !target.getAutoCommit()) target.rollback();
            }
        }
    }

    private static void validateOperationalTarget(Connection target) throws Exception {
        assertEquals("public", target.getSchema(), "Refusing population commit: target schema is not public");
        try (Statement statement = target.createStatement();
                ResultSet lock = statement.executeQuery("SELECT pg_try_advisory_xact_lock(71520260719)")) {
            lock.next();
            assertTrue(lock.getBoolean(1), "Refusing population commit: another population is running");
        }
        Set<String> expectedTables = new HashSet<>(TABLES);
        expectedTables.add("flyway_schema_history");
        Set<String> actualTables = new HashSet<>();
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_type='BASE TABLE'")) {
            while (rows.next()) actualTables.add(rows.getString(1));
        }
        assertEquals(expectedTables, actualTables, "Refusing population commit: unexpected target tables");
        try (Statement statement = target.createStatement(); ResultSet flyway = statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success "
                        + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(flyway.next(), "Refusing population commit: Flyway history is missing");
            assertEquals("1", flyway.getString(1), "Refusing population commit: unexpected Flyway version");
        }
    }

    private static void synchronizeSequences(Connection target) throws Exception {
        for (String table : TABLES) {
            boolean hasId;
            try (PreparedStatement column = target.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name=? AND column_name='id')")) {
                column.setString(1, table);
                try (ResultSet result = column.executeQuery()) {
                    result.next();
                    hasId = result.getBoolean(1);
                }
            }
            if (!hasId) continue;
            try (PreparedStatement sequence = target.prepareStatement(
                    "SELECT pg_get_serial_sequence(?, 'id')")) {
                sequence.setString(1, table);
                try (ResultSet result = sequence.executeQuery()) {
                    if (!result.next() || result.getString(1) == null) continue;
                    String sequenceName = result.getString(1);
                    try (Statement update = target.createStatement()) {
                        update.execute("SELECT setval('" + sequenceName.replace("'", "''") + "', "
                                + "COALESCE((SELECT MAX(id) FROM " + quote(table) + "), 1), "
                                + "EXISTS (SELECT 1 FROM " + quote(table) + "))");
                    }
                }
            }
        }
    }

    private static List<String> sharedColumns(Connection source, Connection target, String table,
            boolean requireExactMatch) throws Exception {
        List<String> sourceColumns = new ArrayList<>();
        try (Statement statement = source.createStatement();
                ResultSet columns = statement.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (columns.next()) sourceColumns.add(columns.getString("name"));
        }
        Set<String> targetColumns = new HashSet<>();
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position")) {
            statement.setString(1, table);
            try (ResultSet columns = statement.executeQuery()) {
                while (columns.next()) targetColumns.add(columns.getString(1));
            }
        }
        if (requireExactMatch) assertEquals(new HashSet<>(sourceColumns), targetColumns,
                "Refusing population commit: column mismatch for " + table);
        return sourceColumns.stream().filter(targetColumns::contains).toList();
    }

    private static String sha256(Path path) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static long countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String rowIdentity(List<String> columns, ResultSet row) throws Exception {
        int idIndex = columns.indexOf("id");
        return idIndex >= 0 ? "id=" + row.getObject(idIndex + 1) : "row";
    }

    private static String concise(String message) {
        if (message == null) return "unknown error";
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240) + "...";
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
