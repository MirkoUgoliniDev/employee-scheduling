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
    void anonymousCannotShutDownTheApplication() {
        given().contentType("application/json").body("{}")
                .when().post("/system-info/exit").then().statusCode(401);
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

    /**
     * Email and PDF template appearance are configuration, not operations: a head nurse
     * must be able to VIEW them (they appear in the same pages she uses) but not to
     * change the texts sent to every operator or the look of every report. Same rule as
     * {@code HomeUiSettingsResource}: read for both roles, write for ADMIN only.
     */
    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaCannotChangeEmailAndPdfTemplates() {
        // Reads stay open to both roles; whether structure 1 exists is irrelevant here.
        given().when().get("/demo-data/email-template?structureId=1")
                .then().statusCode(org.hamcrest.Matchers.not(equalTo(403)));
        given().when().get("/demo-data/pdf-template?structureId=1")
                .then().statusCode(org.hamcrest.Matchers.not(equalTo(403)));
        given().contentType("application/json")
                .body("{\"subject\":\"x\",\"body\":\"y\"}")
                .when().put("/demo-data/email-template?structureId=1").then().statusCode(403);
        given().contentType("application/json")
                .body("{\"headerText\":\"x\",\"footerText\":\"y\",\"logoDataUrl\":\"\",\"primaryColor\":\"#000000\"}")
                .when().put("/demo-data/pdf-template?structureId=1").then().statusCode(403);
        given().when().delete("/demo-data/pdf-template?structureId=1").then().statusCode(403);
    }

    /**
     * The skill catalogue is administrative: head nurses assign skills to employees and
     * locations, they do not create, rename or delete them. That was enforced only in the
     * interface, where ConfigPage redirects non-admins — so a head nurse with curl could
     * rewrite the whole structure's catalogue, and deleting a skill cascades over its
     * associations with locations, employees, shifts and templates.
     */
    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaCannotChangeTheSkillCatalogue() {
        // No identifier here needs to exist. Authorization is decided before the method body
        // runs, so the call never reaches a lookup: that is what makes the assertion about the
        // role and not about the contents of the test database.
        given().contentType("application/json").body("[{\"id\":999,\"name\":\"x\"}]")
                .when().post("/demo-data/save_skills").then().statusCode(403);
        given().when().delete("/demo-data/skills/999").then().statusCode(403);
        // The translations of those same skills have been ADMIN-only from the start; assert it
        // here too, so the two halves of the catalogue cannot drift apart again.
        given().contentType("application/json").body("[]")
                .when().put("/localizzazioni/skills/999").then().statusCode(403);
    }

    /**
     * Pins the meaning of the 403 above. Without this, the previous test would still pass if
     * those endpoints became unreachable for everyone: a 403 would then prove nothing about
     * roles. An administrator must get past the role check and stop at input validation.
     */
    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminReachesTheSkillCatalogue() {
        given().contentType("application/json").body("[{\"id\":999,\"name\":\"x\"}]")
                .when().post("/demo-data/save_skills").then()
                .statusCode(org.hamcrest.Matchers.not(equalTo(403)));
        given().when().delete("/demo-data/skills/999").then()
                .statusCode(org.hamcrest.Matchers.not(equalTo(403)));
    }

    /** The operational half of skills must keep working for a head nurse. */
    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaStillReadsSkills() {
        // structureId is deliberately omitted: it defaults to 0 and the endpoint answers 200
        // with an empty catalogue, so the assertion does not depend on any structure existing.
        given().when().get("/demo-data/get_skills").then().statusCode(200);
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
