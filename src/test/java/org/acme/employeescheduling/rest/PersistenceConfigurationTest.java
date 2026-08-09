package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class PersistenceConfigurationTest {

    @Test
    void ormAndLegacyUseOneCanonicalDatabaseProperty() throws Exception {
        Properties properties = loadApplicationProperties();
        assertEquals("jdbc:sqlite:${demo.db.name}", properties.getProperty("quarkus.datasource.jdbc.url"));
    }

    @Test
    void sqliteConnectionPragmasUseDriverPropertiesInsteadOfUnsupportedMultiStatementSql() throws Exception {
        Properties properties = loadApplicationProperties();
        assertEquals("5000", properties.getProperty(
                "quarkus.datasource.jdbc.additional-jdbc-properties.busy_timeout"));
        assertEquals("true", properties.getProperty(
                "quarkus.datasource.jdbc.additional-jdbc-properties.foreign_keys"));
        assertFalse(properties.containsKey("quarkus.datasource.jdbc.new-connection-sql"));
    }

    @Test
    void newAndLegacySqliteProfilesCannotAccidentallyMixSchemaManagers() throws Exception {
        Properties fresh = loadProperties("/application-sqlite.properties");
        assertEquals("false", fresh.getProperty("app.sqlite.legacy-bootstrap"));
        assertEquals("true", fresh.getProperty("quarkus.flyway.active"));
        assertEquals("false", fresh.getProperty("quarkus.flyway.baseline-on-migrate"));

        Properties legacy = loadProperties("/application-legacy-sqlite.properties");
        assertEquals("true", legacy.getProperty("app.sqlite.legacy-bootstrap"));
        assertEquals("false", legacy.getProperty("quarkus.flyway.active"));
        assertEquals("false", legacy.getProperty("quarkus.flyway.migrate-at-start"));
    }

    private static Properties loadApplicationProperties() throws Exception {
        return loadProperties("/application.properties");
    }

    private static Properties loadProperties(String resource) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = PersistenceConfigurationTest.class.getResourceAsStream(resource)) {
            properties.load(input);
        }
        return properties;
    }
}
