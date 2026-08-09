package org.acme.employeescheduling.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * @brief Proves that authentication is actually enforced.
 *
 * @details Other HTTP tests declare an identity with {@code @TestSecurity}, so they would pass
 *          even if protection were disabled: they prove the domain works, not that it is
 *          protected. This class does the opposite — calls <b>without</b> an identity — and is
 *          the only one that would detect a regression reopening the application.
 */
@QuarkusTest
class AuthenticationEnforcementTest {

    @Test
    void anonymousCannotReadOperationalData() {
        given().when().get("/demo-data/getlocations").then().statusCode(401);
    }

    @Test
    void anonymousCannotWrite() {
        given().contentType("application/json").body("{\"name\":\"x\"}")
                .when().post("/demo-data/addlocation").then().statusCode(401);
    }

    @Test
    void anonymousCannotReachAdministration() {
        given().when().get("/backup/list").then().statusCode(401);
        given().when().get("/structures").then().statusCode(401);
    }

    /** Without this, the login page could not be translated before authentication. */
    @Test
    void translationsStayPublic() {
        given().when().get("/translations").then().statusCode(200);
    }

    /**
     * Must return 200 even anonymously: the client queries it before knowing whether a session
     * exists, and with a 401 it could not distinguish "unauthenticated" from "server unreachable".
     */
    @Test
    void identityEndpointIsReadableWhileAnonymous() {
        given().when().get("/auth/me").then()
                .statusCode(200)
                .body("authenticated", is(false));
    }

    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaWorksOnShiftsButNotOnAdministration() {
        given().when().get("/demo-data/getlocations").then().statusCode(200);
        // 403 rather than 401: she is authenticated, but simply lacks permission.
        given().when().get("/email/settings").then().statusCode(403);
        // Backups have protection IN ADDITION to the role (BACKUP_ADMIN_TOKEN), and its filter
        // runs first: hence 401 rather than 403. Access remains denied either way.
        given().when().get("/backup/list").then().statusCode(401);
    }

    /**
     * A head nurse must be able to READ the structure list: otherwise the frontend has no selected
     * structure and every page remains empty without a message. Changes remain restricted to
     * administrators.
     */
    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaReadsStructuresButCannotChangeThem() {
        given().when().get("/structures").then().statusCode(200);
        given().contentType("application/json").body("{\"name\":\"x\"}")
                .when().post("/structures").then().statusCode(403);
        given().contentType("application/json").body("{\"name\":\"x\"}")
                .when().put("/structures/1").then().statusCode(403);
        given().when().delete("/structures/1").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaCanStillSendShiftEmails() {
        // Sending is operational work even though its containing class is restricted to ADMIN:
        // it must pass the role check and stop at body validation.
        given().contentType("application/json").body("{}")
                .when().post("/email/send-shifts").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminReachesAdministration() {
        given().when().get("/structures").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void identityEndpointReportsWhoIsLoggedIn() {
        given().when().get("/auth/me").then()
                .statusCode(200)
                .body("authenticated", is(true))
                .body("username", equalTo("admin"));
    }
}
