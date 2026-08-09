package org.acme.employeescheduling.rest;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

public class OrmRegressionTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        try {
            Path target = Path.of("target").toAbsolutePath();
            Files.createDirectories(target);
            Path database = target.resolve("orm-regression-test.db");
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(Path.of(database + "-shm"));
            createLegacyBaseSchema(database);
            String dbPath = database.toString();
            return Map.ofEntries(
                    Map.entry("demo.db.name", dbPath),
                    Map.entry("app.database.kind", "sqlite"),
                    Map.entry("app.database.serialize-writers", "true"),
                    Map.entry("app.sqlite.legacy-bootstrap", "true"),
                    Map.entry("quarkus.datasource.db-kind", "sqlite"),
                    Map.entry("quarkus.datasource.jdbc.url", "jdbc:sqlite:" + dbPath.replace('\\', '/')),
                    Map.entry("quarkus.hibernate-orm.dialect", "org.hibernate.community.dialect.SQLiteDialect"),
                    Map.entry("backup.dir", target.resolve("orm-regression-backups").toString()),
                    Map.entry("backup.settings.file", target.resolve("orm-regression-backup.properties").toString()),
                    Map.entry("quarkus.flyway.active", "false"),
                    Map.entry("quarkus.http.test-port", "0"),
                    Map.entry("quarkus.hibernate-orm.statistics", "true"),
                    Map.entry("quarkus.scheduler.enabled", "false"));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot prepare isolated ORM regression database", exception);
        }
    }

    private static void createLegacyBaseSchema(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
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
            statement.execute("CREATE TABLE skill_type (id INTEGER PRIMARY KEY AUTOINCREMENT, description TEXT)");
            statement.execute("INSERT INTO skill_type (id,description) VALUES (1,'required'),(2,'optional')");
            // Fixture from a legacy release: the incorrect FK points to the backup table.
            statement.execute("CREATE TABLE shifts_backup (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE shift_skills (id INTEGER PRIMARY KEY AUTOINCREMENT, shift_id INTEGER NOT NULL, "
                    + "skill_id INTEGER NOT NULL, skill_type_id INTEGER NOT NULL, "
                    + "FOREIGN KEY (shift_id) REFERENCES shifts_backup(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE employee_skills (id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL, "
                    + "skill_id INTEGER NOT NULL)");
            statement.execute("CREATE TABLE employee_dates (id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL, "
                    + "date_start TEXT NOT NULL, date_end TEXT NOT NULL, date_type_id INTEGER NOT NULL)");
            statement.execute("INSERT INTO skills (id,name,skill_order,active) VALUES (101,'Legacy FK skill',101,1)");
            statement.execute("INSERT INTO shifts (id,location_id,start_time,end_time,employee_id,pinned) "
                    + "VALUES (201,999999,'1999-01-01 08:00:00','1999-01-01 09:00:00',NULL,0)");
            statement.execute("INSERT INTO shifts_backup (id) VALUES (201),(202),(203),(204)");
            statement.execute("INSERT INTO shift_skills (id,shift_id,skill_id,skill_type_id) VALUES "
                    + "(301,201,101,1),(302,201,101,1)," // valid duplicate
                    + "(303,202,101,1),"                 // nonexistent current shift
                    + "(304,203,999999,1),"              // nonexistent skill
                    + "(305,204,101,999999)");            // nonexistent type
        }
    }
}
