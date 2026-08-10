package org.acme.employeescheduling.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @brief Renames the SQLite file from the historical name to the current one.
 *
 * @details Until 9 August 2026 the database file was called {@code large_data.db}, a name
 *          inherited from the Timefold quickstart that says nothing about this application and
 *          matches nothing on the PostgreSQL side, where both the database and the role are
 *          called {@code employee_scheduling}. The file is now called
 *          {@code employee_scheduling.db} and the two engines finally agree.
 *
 *          <p>A rename alone would have been a data-loss trap of the worst kind — the silent
 *          one. An existing installation keeps its file under the old name; the application
 *          would look for the new name, not find it, and Flyway would create a fresh empty
 *          database. The user opens the application, sees no employees and no shifts, and their
 *          real data is still on disk two centimetres away. Hence this migration.</p>
 *
 *          <p><b>It must run before Quarkus boots.</b> Flyway migrates during runtime
 *          initialization, so a {@code StartupEvent} observer would arrive too late: Flyway
 *          would already have created the empty file, the target would exist, and this class
 *          would correctly decline to overwrite it — leaving exactly the failure it exists to
 *          prevent. {@link AppMain} therefore calls it from {@code main()}, the same reason
 *          {@link SingleInstanceGuard} lives there.</p>
 *
 *          <p>Best effort throughout: a failure here must never stop startup. The worst case is
 *          that the rename does not happen and the operator moves the file by hand.</p>
 */
public final class LegacyDatabaseName {

    /** Historical file name, still found in installations predating the rename. */
    public static final String LEGACY_FILE = "large_data.db";
    /** Current file name, aligned with the PostgreSQL database name. */
    public static final String CURRENT_FILE = "employee_scheduling.db";
    /** The same two names without the extension, as used to prefix backup files. */
    public static final String LEGACY_BASE = "large_data";
    public static final String CURRENT_BASE = "employee_scheduling";

    /** Default location when no data directory is configured (development, {@code java -jar}). */
    private static final String DEFAULT_DEV_DIR = "databases";

    private static final Logger LOGGER = Logger.getLogger(LegacyDatabaseName.class.getName());

    private LegacyDatabaseName() {
    }

    /**
     * @brief Renames the database file, and its WAL companions, if only the old name exists.
     *
     * @details Deliberately does nothing when the target is already there: an existing
     *          {@code employee_scheduling.db} is the live database, and overwriting it with an
     *          older file would destroy data instead of saving it.
     */
    public static void migrate() {
        migrate(resolveTarget());
    }

    /**
     * @brief The rename itself, against an explicit target. Package-private for the tests:
     *        {@link #resolveTarget()} depends on the process environment, this does not.
     */
    static void migrate(Path target) {
        if (target == null) return;
        Path legacy = target.resolveSibling(LEGACY_FILE);
        if (Files.exists(target) || !Files.isRegularFile(legacy)) return;

        // Companions FIRST, main file last. -wal holds committed transactions not yet folded
        // into the main file, so the two must travel together. With the main file moved first,
        // an interruption in between — a second power cut, an OOM kill — leaves the new name in
        // place and the WAL orphaned under the old one; the next startup sees the target exists,
        // returns early, and SQLite opens a database silently missing those transactions.
        // In this order the invariant holds: if the main file moved, its WAL moved too.
        List<Path[]> moved = new ArrayList<>();
        for (String suffix : new String[] { "-wal", "-shm" }) {
            Path from = legacy.resolveSibling(LEGACY_FILE + suffix);
            Path to = target.resolveSibling(CURRENT_FILE + suffix);
            if (!Files.exists(from)) continue;
            if (!move(from, to)) {
                rollback(moved);
                LOGGER.severe("Could not move " + from.getFileName() + ": the database was NOT"
                        + " renamed. Move " + LEGACY_FILE + " and its -wal/-shm companions by"
                        + " hand, together, before starting the application again.");
                return;
            }
            moved.add(new Path[] { to, from });
        }

        if (!move(legacy, target)) {
            // The companions are already under the new name and the main file is not: put them
            // back, so the legacy set stays complete and consistent for a manual move.
            rollback(moved);
            return;
        }
        LOGGER.info("Database renamed from " + LEGACY_FILE + " to " + CURRENT_FILE
                + " in " + target.getParent());
    }

    private static void rollback(List<Path[]> moved) {
        for (Path[] pair : moved)
            move(pair[0], pair[1]);
    }

    private static boolean move(Path from, Path to) {
        try {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(from, to);
            }
            return true;
        } catch (IOException | SecurityException exc) {
            LOGGER.log(Level.WARNING, "Could not rename " + from + " to " + to
                    + ": rename it by hand, or the application will start on an empty database",
                    exc);
            return false;
        }
    }

    /**
     * @brief Where the database file is expected, mirroring how the configuration resolves it.
     *
     * @details The configuration is not available yet at this point — that is the whole reason
     *          this runs early — so the two rules that decide the path are reproduced here:
     *          an explicit path always wins, otherwise the resolved data directory, otherwise
     *          the development default. Keep this in step with
     *          {@link AppDataDirConfigSource} and the {@code demo.db.name} entries in the
     *          {@code application-*.properties} files.
     */
    private static Path resolveTarget() {
        // An explicit system property names the file itself: the operator chose it, so there is
        // nothing to rename and second-guessing them would be wrong.
        if (System.getProperty("demo.db.name") != null)
            return null;
        try {
            // APP_DATABASE_PATH is used as the TARGET, not as a reason to give up. Treating it
            // as a suppression signal was wrong in the one case that matters: the Windows
            // wizard writes it into `.env`, which Quarkus reads as a config source and NOT as a
            // process environment variable. So getenv saw nothing, the rename went ahead, and
            // Quarkus then opened the path from `.env` — the old name, now gone — and Flyway
            // created an empty database next to the real one. Reading it here means a stale
            // `.env` still pointing at the old name simply makes target == legacy, and the
            // early return in migrate() leaves everything alone.
            String explicit = System.getenv("APP_DATABASE_PATH");
            if (explicit == null) explicit = dotEnvValue("APP_DATABASE_PATH");
            if (explicit != null && !explicit.isBlank())
                return Path.of(explicit.trim()).toAbsolutePath();

            Path base = AppDataDirectory.base();
            return base != null ? base.resolve(CURRENT_FILE)
                                : Path.of(DEFAULT_DEV_DIR).toAbsolutePath().resolve(CURRENT_FILE);
        } catch (RuntimeException exc) {
            LOGGER.log(Level.FINE, "Data directory not resolvable: skipping the rename", exc);
            return null;
        }
    }

    /**
     * @brief One value from the {@code .env} beside the working directory, or null.
     *
     * @details Quarkus reads that file at ordinal 295 through its own config source; the value
     *          never becomes a real environment variable, so this has to be read by hand to see
     *          what the application will actually use. Deliberately minimal — one key, no
     *          interpolation, no export syntax — because it only has to agree with Quarkus on
     *          the plain {@code KEY=value} line the installers write.
     */
    private static String dotEnvValue(String key) {
        Path env = Path.of(".env").toAbsolutePath();
        if (!Files.isRegularFile(env)) return null;
        try {
            for (String line : Files.readAllLines(env)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.startsWith(key + "=")) continue;
                String value = trimmed.substring(key.length() + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))))
                    value = value.substring(1, value.length() - 1);
                return value;
            }
        } catch (IOException | RuntimeException exc) {
            LOGGER.log(Level.FINE, "Unreadable .env: ignoring it for the rename", exc);
        }
        return null;
    }
}
