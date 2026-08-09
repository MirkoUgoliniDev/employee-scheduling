package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/** Real SQLite restore over HTTP against the isolated test-sqlite profile database. */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-sqlite")
class SqliteBackupRestoreTest {

    @Inject
    DatabaseBackupService backup;

    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "backup.admin-token")
    String backupAdminToken;

    @Test
    void listBackupsNeverPublishesStagingFiles() throws Exception {
        Path staging = Path.of("target", "sqlite-test-backups", ".sqlite-backup-adversarial.part");
        Files.createDirectories(staging.getParent());
        Files.writeString(staging, "incomplete");
        try {
            assertTrue(backup.listBackups().stream()
                    .noneMatch(item -> staging.getFileName().toString().equals(item.get("filename"))));
            assertNull(backup.resolveBackup(staging.getFileName().toString()));
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    @Test
    void rejectsSameTableNamesWithDifferentSchemaBeforeRestore() throws Exception {
        String marker = "zz_sqlite_rejected_restore_probe";
        deleteLanguage(marker);
        Map<String, Object> created = backup.performBackup("manual");
        String filename = (String) created.get("filename");
        Path file = backup.resolveBackup(filename);
        assertNotNull(file);
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE languages ADD COLUMN adversarial_column TEXT");
            }
            insertLanguage(marker);

            given().header(BackupAdminFilter.TOKEN_HEADER, backupAdminToken)
                    .contentType("application/json").body(Map.of("filename", filename))
                    .when().post("/backup/restore").then().statusCode(422)
                    .body("error", equalTo(RestoreOutcome.INCOMPATIBLE_DATABASE));
            assertTrue(languageExists(marker), "il rifiuto preflight non deve toccare il DB vivo");
        } finally {
            deleteLanguage(marker);
            if (Files.exists(file)) backup.delete(file);
        }
    }

    @Test
    void restoresAConsistentSnapshotAndKeepsThePoolUsable() throws Exception {
        String marker = "zz_sqlite_restore_probe";
        deleteLanguage(marker);
        Map<String, Object> created = backup.performBackup("manual");
        String filename = (String) created.get("filename");
        Path file = backup.resolveBackup(filename);
        assertNotNull(file);
        try {
            insertLanguage(marker);
            assertTrue(languageExists(marker));

            given().header(BackupAdminFilter.TOKEN_HEADER, backupAdminToken)
                    .contentType("application/json").body(Map.of("filename", filename))
                    .when().post("/backup/restore").then().statusCode(200)
                    .body("restored", equalTo(true)).body("status", equalTo("RESTORED"));

            assertFalse(languageExists(marker));
            assertTrue(tableCount() >= 28);
        } finally {
            deleteLanguage(marker);
            for (Map<String, Object> item : backup.listBackups()) {
                String candidate = (String) item.get("filename");
                if (candidate.equals(filename) || "prerestore".equals(item.get("tag"))) {
                    Path candidateFile = backup.resolveBackup(candidate);
                    if (candidateFile != null) backup.delete(candidateFile);
                }
            }
        }
    }

    private void insertLanguage(String code) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO languages(code, description, active) VALUES (?, 'restore probe', 0)")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    private void deleteLanguage(String code) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM languages WHERE code = ?")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    private boolean languageExists(String code) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM languages WHERE code = ?")) {
            statement.setString(1, code);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private int tableCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM sqlite_master WHERE type = 'table'");
             var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}
