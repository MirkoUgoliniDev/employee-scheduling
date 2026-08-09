package org.acme.employeescheduling.rest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class OrmDatasourcePragmaTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        try {
            Path target = Path.of("target").toAbsolutePath();
            Files.createDirectories(target);
            Path database = target.resolve("orm-datasource-pragma-test.db");
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(Path.of(database + "-shm"));
            createLegacyBaseSchema(database);
            return Map.ofEntries(
                    // No ORM URL override: verifies canonical expansion of
                    // jdbc:sqlite:${demo.db.name} from application.properties.
                    Map.entry("demo.db.name", database.toString().replace('\\', '/')),
                    Map.entry("app.database.kind", "sqlite"),
                    Map.entry("app.database.serialize-writers", "true"),
                    Map.entry("app.sqlite.legacy-bootstrap", "true"),
                    Map.entry("quarkus.datasource.db-kind", "sqlite"),
                    Map.entry("quarkus.datasource.jdbc.url", "jdbc:sqlite:" + database.toString().replace('\\', '/')),
                    Map.entry("quarkus.hibernate-orm.dialect", "org.hibernate.community.dialect.SQLiteDialect"),
                    Map.entry("backup.dir", target.resolve("orm-pragma-backups").toString()),
                    Map.entry("backup.settings.file", target.resolve("orm-pragma-backup.properties").toString()),
                    Map.entry("quarkus.flyway.active", "false"),
                    Map.entry("quarkus.http.test-port", "0"),
                    Map.entry("quarkus.scheduler.enabled", "false"));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot prepare isolated datasource pragma database", exception);
        }
    }

    private static void createLegacyBaseSchema(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "l_order INTEGER, code TEXT, structure_id INTEGER NOT NULL DEFAULT 1, active INTEGER NOT NULL DEFAULT 1, "
                    + "specialist_id INTEGER)");
            statement.execute("CREATE TABLE shifts (id INTEGER PRIMARY KEY AUTOINCREMENT, location_id INTEGER NOT NULL, "
                    + "start_time TEXT NOT NULL, end_time TEXT NOT NULL, employee_id INTEGER, pinned INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE skills (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, "
                    + "skill_order INTEGER, active INTEGER NOT NULL DEFAULT 1)");
            statement.execute("CREATE TABLE employees (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, "
                    + "first_name TEXT NOT NULL, last_name TEXT NOT NULL, structure_id INTEGER NOT NULL DEFAULT 1, "
                    + "active INTEGER NOT NULL DEFAULT 1, email TEXT NOT NULL DEFAULT '')");
            statement.execute("CREATE TABLE location_skills (id INTEGER PRIMARY KEY AUTOINCREMENT, location_id INTEGER NOT NULL, "
                    + "skill_id INTEGER NOT NULL, skill_type_id INTEGER NOT NULL)");
            statement.execute("CREATE TABLE shift_skills (id INTEGER PRIMARY KEY AUTOINCREMENT, shift_id INTEGER NOT NULL, "
                    + "skill_id INTEGER NOT NULL, skill_type_id INTEGER NOT NULL)");
            statement.execute("CREATE TABLE employee_skills (id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL, "
                    + "skill_id INTEGER NOT NULL)");
            statement.execute("CREATE TABLE employee_dates (id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL, "
                    + "date_start TEXT NOT NULL, date_end TEXT NOT NULL, date_type_id INTEGER NOT NULL)");
        }
    }
}
