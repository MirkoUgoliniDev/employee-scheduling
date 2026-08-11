package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @brief Rotation is the only function of the backup subsystem whose job is deleting
 *        files, and the only one without tests (audit point I1).
 *
 * @details The filename pattern mirrors {@link BackupService#BACKUP_NAME}:
 *          {@code base_yyyyMMdd_HHmmss[_n]_tag.db}. Each test controls both the
 *          ordering (mtime, then name) and the deletion predicates (keep per tag,
 *          retention days, newest always protected).
 */
class BackupFileManagerTest {

    private static final Pattern NAME = Pattern
            .compile("^([A-Za-z0-9_-]+)_(\\d{8}_\\d{6})(?:_\\d+)?_([a-z]+)\\.db$");

    @TempDir
    Path tmp;

    private Path backup(String filename, Instant mtime) throws IOException {
        Path p = tmp.resolve(filename);
        Files.createFile(p);
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(mtime));
        return p;
    }

    /** @brief Timestamps strictly increasing by one minute, for deterministic ordering. */
    private Instant t(int minutesAgo) {
        return Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
    }

    @Test
    void keepsTheNewestFilesPerTag() throws IOException {
        Path oldest = backup("db_20260801_100000_auto.db", t(300));
        backup("db_20260801_100000_1_auto.db", t(200));
        backup("db_20260801_100000_2_auto.db", t(100));
        Path newest = backup("db_20260801_100000_3_auto.db", t(10));

        BackupFileManager.rotate(tmp, NAME, 2, 20, 3650, 3650);

        assertTrue(Files.exists(newest), "Il backup più recente non deve mai essere cancellato");
        assertEquals(2, countFiles("_auto.db"),
                "keep=2 deve lasciare esattamente i due più recenti");
        assertTrue(Files.notExists(oldest), "Il terzo più recente deve essere cancellato");
    }

    @Test
    void newestIsProtectedEvenWhenKeepIsOne() throws IOException {
        backup("db_20260801_100000_auto.db", t(300));
        Path newest = backup("db_20260801_100000_1_auto.db", t(10));

        BackupFileManager.rotate(tmp, NAME, 1, 20, 0, 3650);

        assertTrue(Files.exists(newest), "Con retention 0 il più recente resta comunque");
        assertEquals(1, countFiles("_auto.db"));
    }

    @Test
    void retentionDeletesFilesOlderThanCutoff() throws IOException {
        Path old = backup("db_20260801_100000_auto.db", t(3 * 24 * 60));
        Path recent = backup("db_20260801_100000_1_auto.db", t(6 * 60));
        Path newest = backup("db_20260801_100000_2_auto.db", t(10));

        BackupFileManager.rotate(tmp, NAME, 100, 100, 1, 1);

        assertTrue(Files.notExists(old), "File più vecchio di retentionDays deve sparire");
        assertTrue(Files.exists(recent), "File dentro retentionDays deve restare");
        assertTrue(Files.exists(newest));
    }

    @Test
    void tagsAreRotatedIndependently() throws IOException {
        backup("db_20260801_100000_auto.db", t(300));
        backup("db_20260801_100000_1_auto.db", t(200));
        Path manualNewest = backup("db_20260801_100000_manual.db", t(10));

        BackupFileManager.rotate(tmp, NAME, 1, 5, 3650, 3650);

        assertEquals(1, countFiles("_auto.db"), "autoKeep=1 vale solo per il tag auto");
        assertTrue(Files.exists(manualNewest), "Il tag manual non tocca autoKeep");
        assertEquals(1, countFiles("_manual.db"));
    }

    @Test
    void filesOutsideThePatternAreLeftAlone() throws IOException {
        backup("db_20260801_100000_auto.db", t(300));
        Path random = backup("not-a-backup.txt", t(5));
        Path wrongExtension = backup("db_20260801_100000_auto.bak", t(5));

        BackupFileManager.rotate(tmp, NAME, 1, 1, 0, 0);

        assertTrue(Files.exists(random));
        assertTrue(Files.exists(wrongExtension));
    }

    @Test
    void directoriesMatchingThePatternAreIgnored() throws IOException {
        Path dir = tmp.resolve("db_20260801_100000_auto.db");
        Files.createDirectory(dir);

        assertDoesNotThrow(() -> BackupFileManager.rotate(tmp, NAME, 1, 1, 0, 0));
        assertTrue(Files.isDirectory(dir), "Le directory non si toccano");
    }

    @Test
    void missingDirectoryIsANoOp() {
        assertDoesNotThrow(
                () -> BackupFileManager.rotate(tmp.resolve("absent"), NAME, 1, 1, 0, 0));
    }

    @Test
    void sameSecondBackupsResolveTiesByNameReversed() throws IOException {
        // With equal mtime the tie-break is the name in REVERSE order: the higher
        // instance suffix (…_2) wins over (…_1). This pins the current semantics on
        // purpose — any future change to this rule must be a deliberate decision.
        Instant tie = t(60);
        Path kept = backup("db_20260801_100000_2_auto.db", tie);
        backup("db_20260801_100000_1_auto.db", tie);

        BackupFileManager.rotate(tmp, NAME, 1, 20, 3650, 3650);

        assertTrue(Files.exists(kept));
        assertEquals(1, countFiles("_auto.db"));
    }

    private long countFiles(String suffix) throws IOException {
        try (var files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }
}
