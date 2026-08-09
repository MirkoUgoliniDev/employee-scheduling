package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.equalTo;
import static io.restassured.RestAssured.given;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.test.junit.QuarkusTest;
import io.agroal.api.AgroalDataSource;

/**
 * Verifies PostgreSQL backup against the real test database: `pg_dump` is actually invoked.
 *
 * <p>Runs only under the PostgreSQL profile — under the other profile the bean is not even in the
 * container — and fails if the client tools are missing: an "available" backup that produces no
 * file is precisely the defect these tests must prevent.</p>
 */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-postgresql")
class PostgresqlBackupServiceTest {

    @Inject
    DatabaseBackupService backup;

    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "backup.admin-token")
    String backupAdminToken;

    @Test
    void theServiceIsThePostgresqlOneAndReportsItselfAvailable() {
        assertTrue(backup instanceof PostgresqlBackupService,
                "sul profilo postgresql deve essere attivo il servizio PostgreSQL");
        assertTrue(backup.isAvailable(),
                "pg_dump non trovato: " + ((PostgresqlBackupService) backup).unavailableReason());
    }

    @Test
    void performBackupWritesARealDumpFile() throws Exception {
        Map<String, Object> info = backup.performBackup("manual");

        String filename = (String) info.get("filename");
        assertNotNull(filename);
        assertTrue(filename.endsWith(".dump"), "atteso un archivio custom, trovato " + filename);
        assertEquals("manual", info.get("tag"));
        assertNotNull(info.get("timestamp"));

        Path file = backup.resolveBackup(filename);
        assertNotNull(file, "il file appena creato deve essere risolvibile");
        assertTrue(Files.isRegularFile(file));
        long size = ((Number) info.get("size")).longValue();
        assertEquals(Files.size(file), size);
        assertTrue(size > 0, "un dump vuoto significa pg_dump fallito in silenzio");
        // Signature of pg_dump's custom format: distinguishes a real archive from an error file.
        try (var in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(5);
            assertEquals("PGDMP", new String(magic));
        }

        assertTrue(backup.delete(file));
        assertNull(backup.resolveBackup(filename), "dopo la cancellazione non deve piu' risolvere");
    }

    @Test
    void listBackupsSeesWhatPerformBackupCreated() throws Exception {
        Map<String, Object> created = backup.performBackup("preop");
        String filename = (String) created.get("filename");
        try {
            List<Map<String, Object>> listed = backup.listBackups();
            assertTrue(listed.stream().anyMatch(item -> filename.equals(item.get("filename"))),
                    "il backup appena creato deve comparire nell'elenco");
            assertTrue(listed.stream().allMatch(item -> item.containsKey("size")));
        } finally {
            Path file = backup.resolveBackup(filename);
            if (file != null) backup.delete(file);
        }
    }

    @Test
    void listBackupsNeverPublishesStagingFiles() throws Exception {
        Path staging = Path.of("target", "postgresql-test-backups", ".pg-backup-adversarial.part");
        Files.createDirectories(staging.getParent());
        Files.writeString(staging, "PGDMP-incomplete", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            assertTrue(backup.listBackups().stream()
                    .noneMatch(item -> staging.getFileName().toString().equals(item.get("filename"))));
            assertNull(backup.resolveBackup(staging.getFileName().toString()));
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    @Test
    void resolveBackupRejectsAnythingOutsideTheWhitelist() {
        assertNull(backup.resolveBackup(null));
        assertNull(backup.resolveBackup("../../etc/passwd"));
        assertNull(backup.resolveBackup("..\\..\\windows\\system32\\config\\sam"));
        assertNull(backup.resolveBackup("employee_scheduling_test_20260801_101500_manual.db"),
                "l'estensione SQLite non deve essere accettata dal servizio PostgreSQL");
        assertNull(backup.resolveBackup("mai_esistito_20260801_101500_manual.dump"));
    }

    @Test
    void settingsRoundTripKeepsTheFiveKeysAndDeclaresRestoreCapability() throws Exception {
        Map<String, Object> before = backup.getSettings();
        // The five settings plus capability. pg_restore is a prerequisite for this suite,
        // so the UI must offer restore.
        assertEquals(6, before.size());
        assertEquals(Boolean.TRUE, before.get("restoreSupported"));
        try {
            backup.saveSettings(45, 20, 60, 10, 5);
            Map<String, Object> after = backup.getSettings();
            assertEquals(45, after.get("intervalMinutes"));
            assertEquals(20, after.get("autoRetentionDays"));
            assertEquals(60, after.get("otherRetentionDays"));
            assertEquals(10, after.get("autoKeep"));
            assertEquals(5, after.get("otherKeep"));
            assertEquals(Boolean.TRUE, after.get("restoreSupported"));
        } finally {
            backup.saveSettings(number(before, "intervalMinutes"), number(before, "autoRetentionDays"),
                    number(before, "otherRetentionDays"), number(before, "autoKeep"), number(before, "otherKeep"));
        }
    }

    private static int number(Map<String, Object> settings, String key) {
        return ((Number) settings.get(key)).intValue();
    }

    @Test
    void safetyBackupNeverThrows() {
        // Called unconditionally by the domain before destructive operations.
        backup.safetyBackup("preop");
        backup.listBackups().stream()
                .filter(item -> "preop".equals(item.get("tag")))
                .forEach(item -> {
                    Path file = backup.resolveBackup((String) item.get("filename"));
                    try {
                        if (file != null) backup.delete(file);
                    } catch (Exception ignored) {
                        // Best-effort cleanup.
                    }
                });
    }

    /**
     * A URL with a hostile or merely unusual database name must disable the feature with a reason,
     * not produce backups. One check closes two distinct defects: `--dbname=` is a connection
     * string (host and port can be overridden), and a name outside BACKUP_NAME would create files
     * that are never listed, deletable, or rotated.
     */
    @Test
    void ahostileOrUnusualDatabaseNameDisablesBackupsInsteadOfCreatingGhostFiles() {
        assertFalse(parses("jdbc:postgresql://localhost:5432/db=x host=192.0.2.77 port=19999"),
                "conninfo iniettata in --dbname: il dump verrebbe preso da un altro server");
        assertFalse(parses("jdbc:postgresql://localhost:5432/my.db"),
                "il punto rompe BACKUP_NAME: backup creato ma invisibile e mai ruotato");
        assertFalse(parses("jdbc:postgresql://localhost:5432/employee scheduling"));
        assertFalse(parses("jdbc:postgresql://localhost:5432/ostersund_"  + "ä"));
        assertFalse(parses("non-e-un-url-jdbc"));

        assertTrue(parses("jdbc:postgresql://localhost:5432/employee_scheduling"));
        assertTrue(parses("jdbc:postgresql://localhost/employee-scheduling"));
        assertTrue(parses("jdbc:postgresql://localhost:5432/db2026?ApplicationName=test"));
    }

    /** @brief Builds an isolated service and reports whether the URL was accepted. */
    private static boolean parses(String jdbcUrl) {
        PostgresqlBackupService service = new PostgresqlBackupService();
        service.jdbcUrl = jdbcUrl;
        service.username = java.util.Optional.of("employee_scheduling");
        service.parseJdbcUrl();
        return service.unavailableReason() == null;
    }

    @Test
    void restoreRejectsAnUnreadableArchiveWithoutTouchingTheDatabase() throws Exception {
        RestoreOutcome outcome = backup.restore(Path.of("qualsiasi.dump"));

        assertEquals(RestoreOutcome.Status.REJECTED, outcome.status());
        assertEquals(RestoreOutcome.NOT_A_DATABASE, outcome.reason());
        assertFalse(outcome.isRestored());
        assertNull(outcome.recoveryFile(), "nulla da recuperare: il database non e' stato toccato");
    }

    @Test
    void restoreAppliesAValidDumpAndKeepsTheDatasourceUsable() throws Exception {
        String marker = "zz_restore_success_probe";
        deleteLanguage(marker);
        execute("DROP SCHEMA IF EXISTS zz_restore_aux CASCADE");
        execute("CREATE SCHEMA zz_restore_aux");
        execute("CREATE TABLE zz_restore_aux.marker(value integer)");
        execute("INSERT INTO zz_restore_aux.marker VALUES (42)");
        Set<String> filesBefore = backupFiles();
        Map<String, Object> created = backup.performBackup("manual");
        Path dump = backup.resolveBackup((String) created.get("filename"));
        assertNotNull(dump);
        try {
            insertLanguage(marker);
            assertTrue(languageExists(marker));

            given().header(BackupAdminFilter.TOKEN_HEADER, backupAdminToken)
                    .contentType("application/json")
                    .body(Map.of("filename", dump.getFileName().toString()))
                    .when().post("/backup/restore").then().statusCode(200)
                    .body("restored", equalTo(true)).body("status", equalTo("RESTORED"));
            assertFalse(languageExists(marker), "il dato successivo al dump deve scomparire");
            // A fresh query after flush(ALL) proves the pool retains no stale plans/OIDs.
            assertTrue(tableCount() >= 28);
            assertEquals(0, parkedSchemaCount());
            assertEquals(42, scalarInt("SELECT value FROM zz_restore_aux.marker"),
                    "il restore confinato a public non deve toccare altri schemi");
        } finally {
            deleteLanguage(marker);
            execute("DROP SCHEMA IF EXISTS zz_restore_aux CASCADE");
            deleteNewBackupFiles(filesBefore);
        }
    }

    @Test
    void truncatedPayloadIsRejectedBeforeTouchingTheLiveSchema() throws Exception {
        String marker = "zz_restore_rollback_probe";
        deleteLanguage(marker);
        Set<String> filesBefore = backupFiles();
        Map<String, Object> created = backup.performBackup("manual");
        Path source = backup.resolveBackup((String) created.get("filename"));
        assertNotNull(source);
        Path damaged = source.resolveSibling(source.getFileName().toString().replace("_manual.dump", "_preop.dump"));
        Files.copy(source, damaged);
        try (var channel = Files.newByteChannel(damaged, StandardOpenOption.WRITE)) {
            channel.truncate(Math.max(16_384, Files.size(source) * 3 / 4));
        }
        try {
            insertLanguage(marker);
            RestoreOutcome outcome = backup.restore(damaged);

            assertEquals(RestoreOutcome.Status.REJECTED, outcome.status(), outcome.detail());
            assertEquals(RestoreOutcome.INCOMPATIBLE_DATABASE, outcome.reason());
            assertTrue(languageExists(marker), "la validazione payload non deve toccare lo stato vivo");
            assertNull(outcome.recoveryFile());
            assertEquals(0, parkedSchemaCount());
        } finally {
            deleteLanguage(marker);
            deleteNewBackupFiles(filesBefore);
        }
    }

    @Test
    void restoreRejectsAReadableDumpFromAnIncompatibleSchema() throws Exception {
        Set<String> filesBefore = backupFiles();
        String marker = "zz_restore_incompatible_sentinel";
        execute("DROP TABLE IF EXISTS zz_restore_incompatible");
        deleteLanguage(marker);
        try {
            execute("CREATE TABLE zz_restore_incompatible(id integer primary key)");
            Map<String, Object> created = backup.performBackup("manual");
            Path dump = backup.resolveBackup((String) created.get("filename"));
            assertNotNull(dump);
            execute("DROP TABLE zz_restore_incompatible");
            insertLanguage(marker);

            RestoreOutcome outcome = backup.restore(dump);

            assertEquals(RestoreOutcome.Status.REJECTED, outcome.status());
            assertEquals(RestoreOutcome.INCOMPATIBLE_DATABASE, outcome.reason());
            assertTrue(languageExists(marker), "un rifiuto TOC non deve modificare i dati vivi");
            assertEquals(0, parkedSchemaCount());
        } finally {
            deleteLanguage(marker);
            execute("DROP TABLE IF EXISTS zz_restore_incompatible");
            deleteNewBackupFiles(filesBefore);
        }
    }

    @Test
    void restoreRejectsACompleteTocMismatchEvenWhenTableNamesMatch() throws Exception {
        Set<String> filesBefore = backupFiles();
        execute("DROP VIEW IF EXISTS zz_restore_incompatible_view");
        try {
            execute("CREATE VIEW zz_restore_incompatible_view AS SELECT 1 AS value");
            Map<String, Object> created = backup.performBackup("manual");
            Path dump = backup.resolveBackup((String) created.get("filename"));
            assertNotNull(dump);
            execute("DROP VIEW zz_restore_incompatible_view");

            RestoreOutcome outcome = backup.restore(dump);

            assertEquals(RestoreOutcome.Status.REJECTED, outcome.status());
            assertEquals(RestoreOutcome.INCOMPATIBLE_DATABASE, outcome.reason());
        } finally {
            execute("DROP VIEW IF EXISTS zz_restore_incompatible_view");
            deleteNewBackupFiles(filesBefore);
        }
    }

    @Test
    void sameViewIdentityWithDifferentDefinitionIsRejectedBeforePromotion() throws Exception {
        Set<String> filesBefore = backupFiles();
        execute("DROP VIEW IF EXISTS zz_restore_semantic_view");
        try {
            execute("CREATE VIEW zz_restore_semantic_view AS SELECT 1 AS value");
            Map<String, Object> created = backup.performBackup("manual");
            Path dump = backup.resolveBackup((String) created.get("filename"));
            assertNotNull(dump);
            execute("CREATE OR REPLACE VIEW zz_restore_semantic_view AS SELECT 2 AS value");

            RestoreOutcome outcome = backup.restore(dump);

            assertEquals(RestoreOutcome.Status.REJECTED, outcome.status(), outcome.detail());
            assertEquals(RestoreOutcome.INCOMPATIBLE_DATABASE, outcome.reason());
            assertEquals(2, scalarInt("SELECT value FROM zz_restore_semantic_view"),
                    "il preflight DDL non deve toccare la definizione viva");
        } finally {
            execute("DROP VIEW IF EXISTS zz_restore_semantic_view");
            deleteNewBackupFiles(filesBefore);
        }
    }

    @Test
    void sameTableIdentityWithDifferentPersistenceIsRejectedBeforePromotion() throws Exception {
        Set<String> filesBefore = backupFiles();
        execute("DROP TABLE IF EXISTS zz_restore_persistence");
        try {
            execute("CREATE UNLOGGED TABLE zz_restore_persistence(id integer)");
            Map<String, Object> created = backup.performBackup("manual");
            Path dump = backup.resolveBackup((String) created.get("filename"));
            assertNotNull(dump);
            execute("ALTER TABLE zz_restore_persistence SET LOGGED");

            RestoreOutcome outcome = backup.restore(dump);

            assertEquals(RestoreOutcome.Status.REJECTED, outcome.status(), outcome.detail());
            assertEquals(RestoreOutcome.INCOMPATIBLE_DATABASE, outcome.reason());
            assertEquals("p", scalarString("""
                    SELECT c.relpersistence FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = 'zz_restore_persistence'
                    """), "la tabella viva deve restare LOGGED");
        } finally {
            execute("DROP TABLE IF EXISTS zz_restore_persistence");
            deleteNewBackupFiles(filesBefore);
        }
    }

    @Test
    void multilineFunctionBodyCannotCollideWithDdlPreflight() throws Exception {
        Set<String> filesBefore = backupFiles();
        execute("DROP FUNCTION IF EXISTS zz_restore_multiline()");
        try {
            execute("""
                    CREATE FUNCTION zz_restore_multiline() RETURNS text LANGUAGE sql IMMUTABLE
                    AS $fn$
                    SELECT $value$line1
                    --VALUE_A
                    line3$value$
                    $fn$
                    """);
            Map<String, Object> created = backup.performBackup("manual");
            Path dump = backup.resolveBackup((String) created.get("filename"));
            assertNotNull(dump);
            execute("""
                    CREATE OR REPLACE FUNCTION zz_restore_multiline() RETURNS text LANGUAGE sql IMMUTABLE
                    AS $fn$
                    SELECT $value$line1
                    --VALUE_B
                    line3$value$
                    $fn$
                    """);

            RestoreOutcome outcome = backup.restore(dump);

            assertEquals(RestoreOutcome.Status.REJECTED, outcome.status(), outcome.detail());
            assertEquals(RestoreOutcome.INCOMPATIBLE_DATABASE, outcome.reason());
            assertTrue(scalarString("SELECT zz_restore_multiline()").contains("VALUE_B"),
                    "il corpo funzione vivo non deve essere promosso");
        } finally {
            execute("DROP FUNCTION IF EXISTS zz_restore_multiline()");
            deleteNewBackupFiles(filesBefore);
        }
    }

    private Set<String> backupFiles() {
        return backup.listBackups().stream().map(item -> (String) item.get("filename"))
                .collect(Collectors.toSet());
    }

    private void deleteNewBackupFiles(Set<String> filesBefore) throws Exception {
        for (Map<String, Object> item : backup.listBackups()) {
            String filename = (String) item.get("filename");
            if (!filesBefore.contains(filename)) {
                Path file = backup.resolveBackup(filename);
                if (file != null) backup.delete(file);
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
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'");
             var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private int parkedSchemaCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'restore_old_%'");
             var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private String scalarString(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             var result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }
}
