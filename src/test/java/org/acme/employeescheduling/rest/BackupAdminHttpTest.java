package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.test.junit.QuarkusTest;

/** Verifies that the administrative boundary fails closed even from localhost. */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-sqlite")
class BackupAdminHttpTest {

    @ConfigProperty(name = "backup.admin-token")
    String backupAdminToken;

    @Test
    void requiresTheExactConfiguredToken() {
        given().when().get("/backup/settings").then().statusCode(401)
                .body("error", equalTo("BACKUP_ADMIN_AUTH_REQUIRED"));
        given().header(BackupAdminFilter.TOKEN_HEADER, "wrong")
                .when().get("/backup/settings").then().statusCode(401);
        given().header(BackupAdminFilter.TOKEN_HEADER, backupAdminToken)
                .when().get("/backup/settings").then().statusCode(200)
                .header("Cache-Control", equalTo("no-store, private"))
                .header("Vary", equalTo(BackupAdminFilter.TOKEN_HEADER));
    }

    @Test
    void matrixParametersCannotBypassTheAdminFilter() {
        given().urlEncodingEnabled(false).when().get("/backup;x=y/settings").then().statusCode(401)
                .body("error", equalTo("BACKUP_ADMIN_AUTH_REQUIRED"));
        given().urlEncodingEnabled(false)
                .header(BackupAdminFilter.TOKEN_HEADER, backupAdminToken)
                .when().get("/backup;x=y/settings").then().statusCode(200)
                .header("Cache-Control", equalTo("no-store, private"));
    }

    @Test
    void rateLimitNeverLocksOutTheValidAdministratorToken() {
        String valid = backupAdminToken;
        // Clears any shared window left by another test.
        given().header(BackupAdminFilter.TOKEN_HEADER, valid)
                .when().get("/backup/settings").then().statusCode(200);
        for (int attempt = 0; attempt < 10; attempt++) {
            given().header(BackupAdminFilter.TOKEN_HEADER, "wrong-" + attempt)
                    .when().get("/backup/settings").then().statusCode(401);
        }
        given().header(BackupAdminFilter.TOKEN_HEADER, "wrong-over-limit")
                .when().get("/backup/settings").then().statusCode(429)
                .header("Retry-After", equalTo("60"));
        given().header(BackupAdminFilter.TOKEN_HEADER, valid)
                .when().get("/backup/settings").then().statusCode(200);
    }

}
