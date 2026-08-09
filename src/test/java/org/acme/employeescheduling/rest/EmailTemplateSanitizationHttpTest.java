package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.acme.employeescheduling.dto.EmailTemplate;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/** Verifies that direct REST clients cannot bypass the server-side HTML policy. */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class EmailTemplateSanitizationHttpTest {

    @Test
    void putPersistsOnlySanitizedHtml() {
        EmailTemplate original = given().queryParam("structureId", 1)
                .when().get("/demo-data/email-template")
                .then().statusCode(200).extract().as(EmailTemplate.class);

        try {
            given().contentType(ContentType.JSON).queryParam("structureId", 1)
                    .body(Map.of(
                            "subject", "Sanitizer boundary test",
                            "body", "<p onclick=\"alert(1)\"><strong>safe</strong>"
                                    + "<a href=\"javascript:alert(2)\">bad</a>"
                                    + "<script>alert(3)</script></p>"))
                    .when().put("/demo-data/email-template")
                    .then().statusCode(200)
                    .body("body", containsString("<strong>safe</strong>"))
                    .body("body", not(containsString("onclick")))
                    .body("body", not(containsString("javascript:")))
                    .body("body", not(containsString("<script")));
        } finally {
            given().contentType(ContentType.JSON).queryParam("structureId", 1)
                    .body(Map.of(
                            "subject", original.getSubject() == null ? "" : original.getSubject(),
                            "body", original.getBody() == null ? "" : original.getBody()))
                    .when().put("/demo-data/email-template")
                    .then().statusCode(200);
        }
    }
}
