package org.acme.employeescheduling.config;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * @brief Places application data outside the installation directory.
 *
 * @details In the desktop package, the database, backups, settings, and logs used to live in
 *          {@code $APPDIR\data}, inside the installation. This caused three concrete problems
 *          on Windows:
 *          <ul>
 *            <li>uninstalling deleted the user's database along with the program;</li>
 *            <li>it could not remove {@code app.log} while the application was still open;</li>
 *            <li>hardening backup-directory permissions excluded SYSTEM, and the uninstaller —
 *                which runs as SYSTEM — failed with "Error getting
 *                file security ... GetLastError: 5".</li>
 *          </ul>
 *          Data therefore lives in {@code %LOCALAPPDATA%\EmployeeScheduling} (see
 *          {@link AppDataDirectory}), which is already private to the user, survives upgrades,
 *          and is not touched during uninstall.
 *
 *          <p>The path cannot be written in jpackage's {@code .cfg}, which expands only
 *          {@code $APPDIR}, not environment variables: the package passes
 *          {@code -Dapp.data.dir=auto}, and resolution happens here at startup.</p>
 *
 *          <p>Ordinal 320: takes precedence over {@code application.properties} (250) and the
 *          {@code .env} file (295), but yields to explicit system properties (400), so a manually
 *          supplied {@code -Ddemo.db.name=...} still takes precedence.</p>
 */
public class AppDataDirConfigSource implements ConfigSource {

    private final Map<String, String> properties;

    public AppDataDirConfigSource() {
        this.properties = build();
    }

    private static Map<String, String> build() {
        Path base = AppDataDirectory.base();
        if (base == null)
            return Map.of();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("app.data.dir.resolved", base.toString());
        values.put("demo.db.name", base.resolve("large_data.db").toString());
        values.put("backup.dir", base.resolve("backups").toString());
        values.put("backup.settings.file", base.resolve("backup-settings.properties").toString());
        values.put("quarkus.log.file.path", base.resolve("app.log").toString());
        return Collections.unmodifiableMap(values);
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
        return AppDataDirConfigSource.class.getSimpleName();
    }

    @Override
    public int getOrdinal() {
        return 320;
    }
}
