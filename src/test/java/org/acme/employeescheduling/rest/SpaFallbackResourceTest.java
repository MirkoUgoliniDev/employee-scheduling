package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;

/** Direct links and browser refreshes must enter the React SPA, not return 404. */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class SpaFallbackResourceTest {

    @Test
    void everyClientRouteServesTheFrontendEntryPoint() {
        for (String route : new String[] {
                "shifts", "employees", "specialists", "locations", "skills", "dates", "report",
                "structures", "labels", "config"
        }) {
            given().accept("text/html").when().get("/" + route).then()
                    .statusCode(200)
                    .contentType("text/html")
                    .header("Cache-Control", containsString("no-cache"))
                    .body(containsString("<div id=\"root\"></div>"));
        }
    }

    @Test
    void unknownHtmlNavigationStillReturnsNotFound() {
        given().accept("text/html").when().get("/not-an-application-route").then().statusCode(404);
    }

    @Test
    void protectedApiIsNeverMaskedAsTheFrontend() {
        given().accept("text/html").when().get("/backup/settings").then().statusCode(401);
    }

    @Test
    void anExplicitlyRejectedHtmlMediaRangeDoesNotTriggerTheSpa() {
        given().header("Accept", "text/html;q=0, application/json")
                .queryParam("structureId", 1)
                .when().get("/specialists").then()
                .statusCode(200)
                .contentType(startsWith("application/json"));
    }

    @Test
    void anApiSharingAClientPathStillReturnsJson() {
        given().accept("application/json").queryParam("structureId", 1)
                .when().get("/specialists").then()
                .statusCode(200)
                .contentType(startsWith("application/json"));

        given().accept("*/*").queryParam("structureId", 1)
                .when().get("/specialists").then()
                .statusCode(200)
                .contentType(startsWith("application/json"));
    }
}
