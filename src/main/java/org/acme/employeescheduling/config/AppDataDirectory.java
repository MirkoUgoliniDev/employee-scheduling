package org.acme.employeescheduling.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @brief Location of the installed application's data directory.
 *
 * @details Resolved only once, before any bean: it is used by both
 *          {@link AppDataDirConfigSource} (for the DB, backups, and logs) and
 *          {@link AppUserConfigSource} (for the user-editable configuration file).
 *          Outside the package it is {@code null}, and neither source provides properties:
 *          development and tests keep using {@code databases/} and {@code target/}.
 */
public final class AppDataDirectory {

    /** @brief Value of {@code app.data.dir} that requests automatic resolution. */
    private static final String AUTO = "auto";
    /** @brief Directory name under %LOCALAPPDATA% (or under the home directory elsewhere). */
    private static final String APP_FOLDER = "EmployeeScheduling";

    private static final Path BASE = resolve();

    private AppDataDirectory() {
    }

    /** @return the data directory, or {@code null} if the application is not running from the package. */
    public static Path base() {
        return BASE;
    }

    private static Path resolve() {
        String requested = System.getProperty("app.data.dir", System.getenv("APP_DATA_DIR"));
        if (requested == null || requested.isBlank())
            return null;

        Path base = AUTO.equalsIgnoreCase(requested.trim())
                ? automatic()
                : Path.of(requested.trim()).toAbsolutePath().normalize();

        // Created immediately: Quarkus opens the log file before any application bean can run,
        // and it would fail without the directory.
        try {
            Files.createDirectories(base.resolve("backups"));
        } catch (Exception ignored) {
            // Best effort: individual services will fail with their own, more useful message
            // instead of an exception while reading the configuration.
        }
        return base;
    }

    /**
     * @brief %LOCALAPPDATA%\EmployeeScheduling on Windows, {@code ~/.employee-scheduling} elsewhere.
     * @details If LOCALAPPDATA is missing (service, restricted shell), fall back to the home
     *          directory: a private directory in an unusual location is better than the
     *          installation directory.
     */
    private static Path automatic() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank())
            return Path.of(localAppData, APP_FOLDER).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home", "."), ".employee-scheduling")
                .toAbsolutePath().normalize();
    }
}
