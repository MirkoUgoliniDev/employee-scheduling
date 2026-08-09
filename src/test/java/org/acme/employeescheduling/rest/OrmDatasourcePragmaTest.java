package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@TestProfile(OrmDatasourcePragmaTestProfile.class)
class OrmDatasourcePragmaTest {

    @Inject
    AgroalDataSource dataSource;

    @Test
    void everyOrmConnectionEnablesForeignKeysAndBusyTimeout() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(1, pragma(connection, "foreign_keys"));
            assertEquals(5000, pragma(connection, "busy_timeout"));
        }
    }

    private static int pragma(Connection connection, String name) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            result.next();
            return result.getInt(1);
        }
    }
}
