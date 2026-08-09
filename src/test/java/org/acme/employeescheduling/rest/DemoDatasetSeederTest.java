package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoDatasetSeederTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sampleDatasetIsCompleteAndIdempotent() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = temporaryDirectory.resolve("demo-seed.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            createSchema(connection);

            assertTrue(DemoDatasetSeeder.seed(connection, LocalDate.of(2030, 1, 7)));
            assertEquals(1, count(connection, "structures", "name='Poliambulatorio Demo'"));
            assertEquals(6, count(connection, "skills", "1=1"));
            assertEquals(3, count(connection, "specialists", "1=1"));
            assertEquals(4, count(connection, "locations", "1=1"));
            assertEquals(8, count(connection, "employees", "1=1"));
            assertEquals(16, count(connection, "employee_skills", "1=1"));
            assertEquals(40, count(connection, "shifts", "1=1"));
            assertEquals(40, count(connection, "shift_skills", "1=1"));
            assertEquals("cover1", value(connection, "SELECT cover_key FROM home_ui_settings WHERE id=1"));

            assertFalse(DemoDatasetSeeder.seed(connection, LocalDate.of(2030, 2, 4)));
            assertEquals(8, count(connection, "employees", "1=1"));
            assertEquals(40, count(connection, "shifts", "1=1"));
        }
    }

    private static void createSchema(Connection connection) throws Exception {
        String[] statements = {
                "CREATE TABLE structures (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,address TEXT,phone TEXT)",
                "CREATE TABLE skills (id INTEGER PRIMARY KEY AUTOINCREMENT,structure_id INTEGER,name TEXT,skill_order INTEGER,active INTEGER)",
                "CREATE TABLE specialists (id INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT UNIQUE,first_name TEXT,last_name TEXT,structure_id INTEGER,active INTEGER,email TEXT)",
                "CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT UNIQUE,name TEXT,l_order INTEGER,structure_id INTEGER,active INTEGER,specialist_id INTEGER)",
                "CREATE TABLE employees (id INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT UNIQUE,first_name TEXT,last_name TEXT,structure_id INTEGER,active INTEGER,email TEXT)",
                "CREATE TABLE employee_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,employee_id INTEGER,skill_id INTEGER,UNIQUE(employee_id,skill_id))",
                "CREATE TABLE operator_specialist_affinity (id INTEGER PRIMARY KEY AUTOINCREMENT,operator_id INTEGER,specialist_id INTEGER,type INTEGER,UNIQUE(operator_id,specialist_id))",
                "CREATE TABLE location_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,location_id INTEGER,skill_id INTEGER,skill_type_id INTEGER,UNIQUE(location_id,skill_id,skill_type_id))",
                "CREATE TABLE shifts (id INTEGER PRIMARY KEY AUTOINCREMENT,location_id INTEGER,start_time TEXT,end_time TEXT,employee_id INTEGER,pinned INTEGER,version INTEGER DEFAULT 0)",
                "CREATE TABLE shift_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,shift_id INTEGER,skill_id INTEGER,skill_type_id INTEGER,UNIQUE(shift_id,skill_id,skill_type_id))",
                "CREATE TABLE general_settings (id INTEGER PRIMARY KEY AUTOINCREMENT,structure_id INTEGER UNIQUE,shift_window_mode TEXT,auto_populate_from_template INTEGER)",
                "CREATE TABLE home_ui_settings (id INTEGER PRIMARY KEY,cover_key TEXT,cover_data_url TEXT,title_key TEXT,body_key TEXT,hint_key TEXT)",
                "INSERT INTO home_ui_settings VALUES (1,'','','home.title','home.body','home.hint')"
        };
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            for (String sql : statements) statement.execute(sql);
        }
    }

    private static long count(Connection connection, String table, String where) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String value(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
