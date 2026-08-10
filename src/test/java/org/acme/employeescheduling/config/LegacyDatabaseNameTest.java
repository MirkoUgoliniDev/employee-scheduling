package org.acme.employeescheduling.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @brief Guards the rename of the SQLite file from {@code large_data.db}.
 *
 * @details The migration is one of the few places where a bug destroys data rather than raising
 *          an error: getting it wrong means Flyway creates an empty database while the real one
 *          sits next to it, and the application looks freshly installed. These tests pin the two
 *          properties that matter — it renames when it should, and it never overwrites.
 */
class LegacyDatabaseNameTest {

    private static final byte[] REAL_DATA = "the user's real database".getBytes(StandardCharsets.UTF_8);

    @Test
    void renamesTheLegacyFileWhenTheCurrentOneIsAbsent(@TempDir Path dir) throws IOException {
        Path legacy = Files.write(dir.resolve(LegacyDatabaseName.LEGACY_FILE), REAL_DATA);
        Path target = dir.resolve(LegacyDatabaseName.CURRENT_FILE);

        LegacyDatabaseName.migrate(target);

        assertFalse(Files.exists(legacy), "the old file must not be left behind");
        assertTrue(Files.exists(target), "the database must be found under the new name");
        assertArrayEquals(REAL_DATA, Files.readAllBytes(target), "content must survive intact");
    }

    @Test
    void movesTheWalCompanionsToo(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve(LegacyDatabaseName.LEGACY_FILE), REAL_DATA);
        Files.write(dir.resolve(LegacyDatabaseName.LEGACY_FILE + "-wal"), "committed".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(LegacyDatabaseName.LEGACY_FILE + "-shm"), new byte[] { 1 });

        LegacyDatabaseName.migrate(dir.resolve(LegacyDatabaseName.CURRENT_FILE));

        // -wal holds committed transactions not yet folded into the main file: leaving it under
        // the old name silently discards them.
        assertTrue(Files.exists(dir.resolve(LegacyDatabaseName.CURRENT_FILE + "-wal")));
        assertTrue(Files.exists(dir.resolve(LegacyDatabaseName.CURRENT_FILE + "-shm")));
    }

    @Test
    void theWalNeverStaysBehindWhenTheMainFileMoves(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve(LegacyDatabaseName.LEGACY_FILE), REAL_DATA);
        Files.write(dir.resolve(LegacyDatabaseName.LEGACY_FILE + "-wal"), "committed".getBytes(StandardCharsets.UTF_8));

        LegacyDatabaseName.migrate(dir.resolve(LegacyDatabaseName.CURRENT_FILE));

        // The invariant that matters: main file under the new name IMPLIES its WAL is too.
        // The other way round the next startup would see the target present, return early, and
        // open a database missing every transaction still held in the orphaned WAL.
        boolean mainMoved = Files.exists(dir.resolve(LegacyDatabaseName.CURRENT_FILE));
        boolean walMoved = Files.exists(dir.resolve(LegacyDatabaseName.CURRENT_FILE + "-wal"));
        assertFalse(mainMoved && !walMoved, "the main file moved without its WAL");
        assertFalse(Files.exists(dir.resolve(LegacyDatabaseName.LEGACY_FILE + "-wal")),
                "no WAL may be left under the old name");
    }

    @Test
    void neverOverwritesAnExistingCurrentDatabase(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve(LegacyDatabaseName.LEGACY_FILE), "stale leftover".getBytes(StandardCharsets.UTF_8));
        Path target = Files.write(dir.resolve(LegacyDatabaseName.CURRENT_FILE), REAL_DATA);

        LegacyDatabaseName.migrate(target);

        // The current file is the live database. Overwriting it with an older leftover would
        // destroy data instead of rescuing it — the opposite of this class's purpose.
        assertArrayEquals(REAL_DATA, Files.readAllBytes(target));
        assertTrue(Files.exists(dir.resolve(LegacyDatabaseName.LEGACY_FILE)),
                "the leftover is left where it is, for the operator to inspect");
    }

    @Test
    void doesNothingOnAFreshInstallation(@TempDir Path dir) {
        Path target = dir.resolve(LegacyDatabaseName.CURRENT_FILE);

        LegacyDatabaseName.migrate(target);

        assertFalse(Files.exists(target), "no file must be conjured up: Flyway creates it");
    }

    @Test
    void toleratesANullTarget() {
        // resolveTarget() returns null when the operator set an explicit path: nothing to do,
        // and certainly nothing to crash the startup for.
        LegacyDatabaseName.migrate((Path) null);
    }
}
