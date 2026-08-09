package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Shared persistence contract. The identical class is executed with the real
 * SQLite and PostgreSQL profiles; H2 is deliberately not involved.
 */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class DatabasePortabilityContractTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "count_distribution", "demo_data_parameters", "email_log", "email_settings",
            "email_templates", "employee_date_type", "employee_dates", "employee_skills",
            "employees", "general_settings", "labels", "languages", "localizzazioni",
            "location_skills", "locations", "operator_specialist_affinity", "pdf_templates",
            "shift_skills", "shift_template_headers", "shift_template_meta",
            "shift_template_skills", "shift_templates", "shifts", "skill_type", "skills",
            "solver_settings", "specialists", "structures");

    @Inject DataSource dataSource;
    @Inject Flyway flyway;
    @Inject SystemInfoResource systemInfoResource;

    @Test
    void selectedProfileUsesTheExpectedDatabaseEngine() throws Exception {
        String profile = System.getProperty("quarkus.test.profile");
        String expectedProduct = "test-postgresql".equals(profile) ? "PostgreSQL" : "SQLite";
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(expectedProduct, connection.getMetaData().getDatabaseProductName());
        }
        SystemInfoResource.SystemInfo info = systemInfoResource.get();
        assertEquals(expectedProduct, info.databaseProductName());
        assertTrue(!info.databaseProductVersion().isBlank());
        assertTrue(!info.jdbcDriverName().isBlank());
        assertTrue(!info.jdbcDriverVersion().isBlank());
        assertEquals("PostgreSQL".equals(expectedProduct) ? "postgresql" : "sqlite",
                info.databaseUpdateComponent());
    }

    @Test
    void flywayIsCurrentAndCreatesTheLogicalSchema() throws Exception {
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted,
                "A second migrate must be idempotent");

        Set<String> tables = new TreeSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.getMetaData().getTables(null, null, "%", new String[] { "TABLE" })) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME").toLowerCase();
                if (!table.equals("flyway_schema_history") && !table.startsWith("pg_")) {
                    tables.add(table);
                }
            }
        }
        assertTrue(tables.containsAll(EXPECTED_TABLES),
                () -> "Missing logical tables: " + difference(EXPECTED_TABLES, tables));
    }

    @Test
    void identityUniqueForeignKeyAndRollbackHaveTheSameContract() throws Exception {
        int structureId;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO structures(name, address, phone) VALUES (?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, "Portability contract");
                insert.setString(2, "");
                insert.setString(3, "");
                assertEquals(1, insert.executeUpdate());
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    structureId = keys.getInt(1);
                    assertTrue(structureId > 0);
                }
            }
            connection.commit();
        }

        String code = "PORTABILITY-" + structureId;
        insertEmployee(code, structureId);
        assertThrows(SQLException.class, () -> insertEmployee(code, structureId),
                "The database, not a pre-check, must enforce uniqueness");
        assertThrows(SQLException.class, () -> insertEmployee("ORPHAN-" + structureId, Integer.MAX_VALUE),
                "Foreign keys must be active on both engines");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "UPDATE employees SET last_name = ? WHERE code = ?")) {
                statement.setString(1, "Rolled back");
                statement.setString(2, code);
                assertEquals(1, statement.executeUpdate());
            }
            connection.rollback();
        }

        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT last_name FROM employees WHERE code = ? ORDER BY id")) {
            statement.setString(1, code);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("User", rs.getString(1));
            }
        }
    }

    private void insertEmployee(String code, int structureId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO employees(code, first_name, last_name, structure_id, active, email) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, code);
            statement.setString(2, "Test");
            statement.setString(3, "User");
            statement.setInt(4, structureId);
            statement.setInt(5, 1);
            statement.setString(6, "portability@example.test");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }
}
