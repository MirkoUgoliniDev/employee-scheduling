package org.acme.employeescheduling.config;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @brief Creates the data directory on first startup if it does not exist.
 *
 * @details In the desktop package, data lives in {@code $APPDIR\data} (the same directory
 *          as the executable): DB, backups, and logs. The installer does not create the
 *          directory (jpackage copies its input into {@code app\}), so the application creates
 *          it at startup before SQLite opens the file. Idempotent and best effort.
 */
@ApplicationScoped
@Startup
public class DataDirInitializer {

    private static final Logger logger = Logger.getLogger(DataDirInitializer.class.getName());

    @ConfigProperty(name = "demo.db.name")
    String dbName;

    @ConfigProperty(name = "backup.dir", defaultValue = "databases/backups")
    String backupDir;

    /**
     * @details Without {@code @PostConstruct}, the method was never invoked: {@code @Startup}
     *          on the class forces bean instantiation, not invocation of an arbitrary method.
     *          The data directory existed only because the installer copied one into the package.
     */
    @PostConstruct
    void ensureDirs() {
        try {
            Path dbPath = Path.of(dbName).toAbsolutePath();
            Path parent = dbPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.createDirectories(Path.of(backupDir).toAbsolutePath());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Impossibile creare le cartelle dati", e);
        }
    }
}
