package org.acme.employeescheduling.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * @brief On a shared server, only an ADMIN may shut the application down.
 *
 * @details {@code POST /system-info/exit} carries a method-level {@code @RolesAllowed} that
 *          widens the class-level ADMIN restriction to CAPOSALA. That widening is wanted on the
 *          desktop package — closing the application there is closing the window in front of the
 *          operator — and is a denial of service on a server, where the same request disconnects
 *          everyone. {@code @RolesAllowed} cannot express that condition, so the narrowing lives
 *          in code and therefore needs a test: the previous state of this endpoint (unconditional
 *          widening, Javadoc claiming the opposite) survived precisely because nothing covered it.
 *
 *          <p>The profile forces {@code app.registration.mode=server} so the server branch is
 *          exercised while the suite keeps running on SQLite.</p>
 *
 *          <p><b>Why the allowed path is not tested here.</b> A successful call stops the JVM —
 *          it would take the test process down with it. Only the refusals are asserted; that a
 *          200 shuts the application down is covered by the endpoint being used in production.</p>
 */
@TestProfile(SystemExitAuthorizationTest.ServerModeProfile.class)
@QuarkusTest
class SystemExitAuthorizationTest {

    public static class ServerModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.registration.mode", "server");
        }
    }

    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaCannotShutDownAServer() {
        given().contentType("application/json").body("{}")
                .when().post("/system-info/exit").then()
                .statusCode(403)
                .body("error", equalTo("EXIT_REQUIRES_ADMIN"));
    }

    @Test
    void anonymousCannotShutDownAnything() {
        given().contentType("application/json").body("{}")
                .when().post("/system-info/exit").then()
                .statusCode(401);
    }

    /**
     * @details The frontend hides "Chiudi applicazione" using this flag. If it stopped being sent,
     *          the menu entry would vanish for CAPOSALA on desktops too — a silent regression in
     *          the opposite direction, and one nobody would report as a security problem.
     */
    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void sessionReportsThisIsNotAStandaloneInstallation() {
        given().when().get("/auth/me").then()
                .statusCode(200)
                .body("standalone", equalTo(false));
    }
}
