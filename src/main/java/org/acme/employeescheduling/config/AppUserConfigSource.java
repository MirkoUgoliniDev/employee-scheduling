package org.acme.employeescheduling.config;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @brief User-editable configuration stored alongside user data.
 *
 * @details A single MSI serves every installation: settings that differ between installations —
 *          port, SMTP server, registration mode — are written to
 *          {@code %LOCALAPPDATA%\EmployeeScheduling\config.properties} and take effect after
 *          restart, without recompiling or modifying the installation directory.
 *
 *          <p>On first startup the file is created with every entry commented out, so anyone
 *          opening it can immediately see what can be changed and which values are accepted.</p>
 *
 *          <p>Ordinal 450, therefore <b>above</b> the package's system properties (400): options
 *          hardcoded in jpackage's {@code .cfg} are defaults, and the user must be able to
 *          correct them — port 8080 being occupied by another program is the typical case.
 *          {@code app.data.dir} is the exception and is ignored: by the time this file is read,
 *          the data directory has already been resolved, and accepting it here would produce a
 *          configuration that says one thing while the application does another.</p>
 */
public class AppUserConfigSource implements ConfigSource {

    /** @brief Configuration file name inside the data directory. */
    static final String FILE_NAME = "config.properties";

    /** @brief Keys that do not make sense here because they were applied before this file was read. */
    private static final Set<String> IGNORED = Set.of("app.data.dir", "app.data.dir.resolved");

    private final Map<String, String> properties;

    public AppUserConfigSource() {
        this.properties = load();
    }

    private static Map<String, String> load() {
        Path base = AppDataDirectory.base();
        if (base == null)
            return Map.of();

        Path file = base.resolve(FILE_NAME);
        try {
            writeTemplate(file);
        } catch (Exception ignored) {
            // File already present (the normal case from the second startup onward), or template
            // not writable: in either case continue with the defaults.
        }

        Properties loaded = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (Exception ignored) {
            // Missing, unreadable, or malformed file: continue without overrides.
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String name : loaded.stringPropertyNames()) {
            String value = loaded.getProperty(name);
            if (value == null || value.isBlank() || IGNORED.contains(name)) continue;
            values.put(name, value.trim());
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * @brief Writes the commented template: it documents itself without requiring a manual.
     * @details {@code CREATE_NEW} makes creation atomic and fails if the file already exists.
     *          With a "check whether it exists, then write" sequence, two processes on first
     *          startup could both pass the check, and the second writer could truncate the file
     *          while the first process was reading it.
     */
    private static void writeTemplate(Path file) throws Exception {
        String template = """
                # ============================================================================
                #  Employee Scheduling - local configuration
                #
                #  Remove the # before a line, save, and RESTART the application.
                #  Values written here override settings selected during installation.
                #  Lines left commented out have no effect.
                #
                #  Updates and uninstallation NEVER modify this file.
                # ============================================================================

                # ── Listening port ──────────────────────────────────────────────────────────
                # Change this if port 8080 is already used by another program.
                #quarkus.http.port=8080

                # ── Open the browser automatically at startup ───────────────────────────────
                #app.open-browser-on-start=true

                # ── Email delivery (shift reminders, notifications) ─────────────────────────
                # With mock=true, no email is actually sent: the message goes to the log.
                #quarkus.mailer.mock=false
                #quarkus.mailer.host=smtp.example.com
                #quarkus.mailer.port=587
                #quarkus.mailer.username=
                #quarkus.mailer.password=
                #quarkus.mailer.from=turni@example.com
                #quarkus.mailer.start-tls=REQUIRED

                # ── User registration ───────────────────────────────────────────────────────
                # auto       = inferred from the database (SQLite: no email, PostgreSQL: OTP)
                # standalone = username and password, no email
                # server     = email-address verification with a one-time code
                #app.registration.mode=auto

                # ── Security ─────────────────────────────────────────────────────────────────
                # Key that encrypts the session cookie: at least 16 characters, preferably 32+.
                # Changing it invalidates every active login and requires signing in again.
                #quarkus.http.auth.session.encryption-key=

                # Token required by backup administration calls.
                #backup.admin-token=

                # ── Logging ──────────────────────────────────────────────────────────────────
                # Useful levels: INFO (default), WARNING, SEVERE.
                #quarkus.log.file.level=INFO
                """;
        Files.writeString(file, template, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return AppUserConfigSource.class.getSimpleName();
    }

    @Override
    public int getOrdinal() {
        return 450;
    }
}
