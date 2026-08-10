package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * @brief Verifies that a divergent database configuration stops startup.
 *
 * @details The dangerous case is not one that breaks the application, but one that lets it run
 *          while backups operate on a file other than the live database.
 */
class DatabaseConfigValidatorTest {

    private static DatabaseConfigValidator validator(String appKind, String dsKind, String url, String dbName) {
        DatabaseConfigValidator validator = new DatabaseConfigValidator();
        validator.appDatabaseKind = appKind;
        validator.datasourceKind = dsKind;
        validator.jdbcUrl = url;
        validator.dbName = dbName;
        return validator;
    }

    @Test
    void acceptsTheSqliteProfileWhereTheUrlDerivesFromDbName() {
        assertDoesNotThrow(() -> validator("sqlite", "sqlite",
                "jdbc:sqlite:databases/employee_scheduling.db", "databases/employee_scheduling.db").validate());
    }

    @Test
    void acceptsEquivalentPathsWrittenDifferently() {
        assertDoesNotThrow(() -> validator("sqlite", "sqlite",
                "jdbc:sqlite:databases/../databases/employee_scheduling.db", "databases/employee_scheduling.db").validate());
    }

    @Test
    void acceptsSqliteUrlWithConnectionParameters() {
        assertDoesNotThrow(() -> validator("sqlite", "sqlite",
                "jdbc:sqlite:databases/employee_scheduling.db?busy_timeout=5000", "databases/employee_scheduling.db").validate());
    }

    @Test
    void acceptsPostgresqlProfileIgnoringTheSqliteFile() {
        assertDoesNotThrow(() -> validator("postgresql", "postgresql",
                "jdbc:postgresql://localhost:5432/employee_scheduling", "databases/employee_scheduling.db").validate());
    }

    @Test
    void rejectsUrlPointingToADifferentSqliteFileThanTheBackupSource() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> validator("sqlite", "sqlite",
                "jdbc:sqlite:databases/other.db", "databases/employee_scheduling.db").validate());
        assertTrue(error.getMessage().contains(Path.of("databases/other.db").toAbsolutePath().normalize().toString()),
                "il messaggio deve indicare il file effettivamente in uso: " + error.getMessage());
    }

    @Test
    void rejectsRuntimeEngineDifferentFromTheDeclaredOne() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> validator("sqlite", "postgresql",
                "jdbc:postgresql://localhost:5432/employee_scheduling", "databases/employee_scheduling.db").validate());
        assertTrue(error.getMessage().contains("app.database.kind"), error.getMessage());
    }

    @Test
    void rejectsDeclaredPostgresqlRunningOnSqlite() {
        assertThrows(IllegalStateException.class, () -> validator("postgresql", "sqlite",
                "jdbc:sqlite:databases/employee_scheduling.db", "databases/employee_scheduling.db").validate());
    }

    @Test
    void rejectsInMemorySqliteBecauseNoFileCanBeBackedUp() {
        assertThrows(IllegalStateException.class, () -> validator("sqlite", "sqlite",
                "jdbc:sqlite::memory:", "databases/employee_scheduling.db").validate());
    }

    @Test
    void toleratesSpacingAndCaseInTheDeclaredKind() {
        assertDoesNotThrow(() -> validator(" SQLite ", "sqlite",
                "jdbc:sqlite:databases/employee_scheduling.db", " databases/employee_scheduling.db ").validate());
    }
}
