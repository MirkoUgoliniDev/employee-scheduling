package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BackupServiceSchedulingTest {

    @Test
    void automaticBackupDoesNotSkipAnUnchangedWalDatabase() throws Exception {
		Class.forName("org.sqlite.JDBC");
        Path directory = Path.of("target", "backup-scheduling-test-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(directory);
        Path database = directory.resolve("automatic.db");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO sample (value) VALUES ('unchanged')");
        }

        BackupService service = new BackupService();
        service.dbName = database.toString();
        service.backupDir = directory.resolve("backups").toString();
        service.settingsFile = directory.resolve("settings.properties").toString();
        service.defaultAutoKeep = 10;
        service.defaultOtherKeep = 10;
        service.defaultAutoRetentionDays = 30;
        service.defaultOtherRetentionDays = 30;
        service.loadSettings();

        service.scheduledBackup();
        resetAutomaticRun(service);
        service.scheduledBackup();

        assertEquals(2, service.listBackups().stream()
                .filter(item -> "auto".equals(item.get("tag"))).count());
    }

    private static void resetAutomaticRun(BackupService service) throws Exception {
        Field field = BackupService.class.getDeclaredField("lastAutomaticRun");
        field.setAccessible(true);
        field.setLong(service, 0L);
    }
}
