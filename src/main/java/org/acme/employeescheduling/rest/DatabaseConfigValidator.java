package org.acme.employeescheduling.rest;

import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;

/**
 * @brief Blocks startup when properties describing the database disagree.
 *
 * @details
 * Database configuration is expressed by three properties that profile files keep aligned by
 * construction, but an external override (environment variable, `-D`, deployment configuration)
 * can change individually:
 * <ul>
 *   <li>{@code app.database.kind} — selects application behavior (legacy bootstrap,
 *       writer serialization, backup implementation);</li>
 *   <li>{@code quarkus.datasource.db-kind} — determines which engine Hibernate actually uses;</li>
 *   <li>{@code demo.db.name} — the SQLite file that {@link BackupService} saves and restores.</li>
 * </ul>
 *
 * <p>A mismatch produces no visible error: the application starts and works. Damage emerges
 * during backups, at the worst possible time. If {@code quarkus.datasource.jdbc.url} points to
 * a file other than {@code demo.db.name}, automatic backups capture a database nobody writes,
 * and a restore overwrites a file that is not live: real data remains behind without any FK or
 * transaction objecting. Similarly, if the declared engine is not the actual one, the wrong
 * backup path remains active.</p>
 *
 * <p>The check runs at {@code APPLICATION} priority (2000), before the DDL/seed bootstrap in
 * {@link DemoDataRepository} (default priority 2500): startup stops before touching the wrong
 * database.</p>
 */
@ApplicationScoped
public class DatabaseConfigValidator {

    private static final Logger logger = Logger.getLogger(DatabaseConfigValidator.class.getName());
    private static final String SQLITE = "sqlite";
    private static final String JDBC_SQLITE_PREFIX = "jdbc:sqlite:";

    @ConfigProperty(name = "app.database.kind")
    String appDatabaseKind;

    @ConfigProperty(name = "quarkus.datasource.db-kind")
    String datasourceKind;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "demo.db.name")
    String dbName;

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent ignored) {
        validate();
    }

    /** @brief Runs the checks; throws IllegalStateException with the correction to apply. */
    void validate() {
        String declared = normalizeKind(appDatabaseKind);
        String effective = normalizeKind(datasourceKind);

        if (!declared.equals(effective))
            throw new IllegalStateException(
                    "Configurazione database incoerente: app.database.kind=" + declared
                    + " ma quarkus.datasource.db-kind=" + effective
                    + ". L'applicazione userebbe il percorso di backup e di bootstrap del motore sbagliato."
                    + " Allineare le due proprieta' oppure selezionare il profilo corretto"
                    + " (-Dquarkus.profile=sqlite oppure -Dquarkus.profile=postgresql).");

        if (!SQLITE.equals(effective)) return;

        Path fromUrl = sqliteFileFromUrl(jdbcUrl);
        if (fromUrl == null) {
            // SQLite URL that cannot be mapped to a file (for example, :memory:): a file backup
            // would have no consistent source to capture anyway.
            throw new IllegalStateException(
                    "Configurazione database incoerente: quarkus.datasource.jdbc.url=" + jdbcUrl
                    + " non punta a un file SQLite, mentre demo.db.name=" + dbName
                    + " indica il file che il servizio di backup salva e ripristina.");
        }

        Path fromName = absolute(Path.of(dbName.trim()));
        if (!fromUrl.equals(fromName))
            throw new IllegalStateException(
                    "Configurazione database incoerente: quarkus.datasource.jdbc.url punta a "
                    + fromUrl + " mentre demo.db.name punta a " + fromName
                    + ". I backup fotograferebbero un database diverso da quello in uso e un"
                    + " ripristino sovrascriverebbe il file sbagliato. Impostare demo.db.name e"
                    + " lasciare che l'URL derivi da esso (jdbc:sqlite:${demo.db.name}).");

        logger.fine(() -> "Configurazione database coerente: " + effective + " su " + fromUrl);
    }

    private static String normalizeKind(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @brief Extracts the file from a SQLite JDBC URL.
     * @return the normalized absolute path, or null if the URL does not designate a file
     */
    private static Path sqliteFileFromUrl(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, JDBC_SQLITE_PREFIX, 0, JDBC_SQLITE_PREFIX.length())) return null;
        String file = trimmed.substring(JDBC_SQLITE_PREFIX.length());
        int query = file.indexOf('?');
        if (query >= 0) file = file.substring(0, query);
        if (file.isBlank() || file.contains(":memory:") || file.startsWith("file:")) return null;
        return absolute(Path.of(file));
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
