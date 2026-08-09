package org.acme.employeescheduling.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.acme.employeescheduling.persistence.AppUserEntity;
import org.junit.jupiter.api.Test;

/**
 * @brief Registration flow in STANDALONE mode (desktop SQLite, no email server).
 *
 * @details In standalone mode there is no OTP: the first user creates the ADMIN with only a
 *          username and password; subsequent users are created as CAPOSALA pending approval
 *          (no email notification). The profile forces {@code app.registration.mode=standalone}.
 */
@TestProfile(RegistrationStandaloneFlowTest.StandaloneProfile.class)
@QuarkusTest
class RegistrationStandaloneFlowTest {

    public static class StandaloneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.registration.mode", "standalone");
        }
    }

    private void clearUsers() {
        QuarkusTransaction.requiringNew().run(() -> AppUserEntity.deleteAll());
    }

    private void ensureUsersExist() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUserEntity.count() == 0)
                AppUserEntity.create("placeholder-admin", "password123",
                        AppUserEntity.ROLE_ADMIN, "Placeholder").persist();
        });
    }

    @Test
    void statusReportsStandaloneWithoutOtp() {
        given().when().get("/auth/register/status").then()
                .statusCode(200)
                .body("mode", equalTo("standalone"))
                .body("otpRequired", equalTo(false));
    }

    @Test
    void otpEndpointsAreRejectedInStandalone() {
        given().contentType(ContentType.JSON).body("{\"email\":\"x@example.com\"}")
                .when().post("/auth/register/otp").then()
                .statusCode(400)
                .body("error", equalTo("OTP_NOT_REQUIRED"));

        given().contentType(ContentType.JSON).body("{\"email\":\"x@example.com\",\"otp\":\"123456\"}")
                .when().post("/auth/register/verify").then()
                .statusCode(400)
                .body("error", equalTo("OTP_NOT_REQUIRED"));
    }

    @Test
    void firstUserBecomesActiveAdminWithoutOtp() {
        clearUsers();
        try {
            given().contentType(ContentType.JSON)
                    .body("{\"username\":\"admin_standalone\",\"password\":\"password123\"}")
                    .when().post("/auth/register/complete").then()
                    .statusCode(201)
                    .body("created", equalTo(true))
                    .body("pendingApproval", equalTo(false))
                    .body("admin", equalTo(true));

            AppUserEntity created = AppUserEntity.findByUsername("admin_standalone");
            assertNotNull(created);
            assertEquals(AppUserEntity.ROLE_ADMIN, created.role);
            assertTrue(created.active);
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                AppUserEntity.delete("username", "admin_standalone");
                if (AppUserEntity.count() == 0)
                    AppUserEntity.create("restored-admin", "password123",
                            AppUserEntity.ROLE_ADMIN, "Restored").persist();
            });
        }
    }

    @Test
    void subsequentUsersBecomePendingCaposala() {
        ensureUsersExist();
        QuarkusTransaction.requiringNew().run(() -> AppUserEntity.delete("username", "capo_standalone"));
        try {
            given().contentType(ContentType.JSON)
                    .body("{\"username\":\"capo_standalone\",\"password\":\"password123\"}")
                    .when().post("/auth/register/complete").then()
                    .statusCode(201)
                    .body("created", equalTo(true))
                    .body("pendingApproval", equalTo(true))
                    .body("admin", equalTo(false));

            AppUserEntity created = AppUserEntity.findByUsername("capo_standalone");
            assertNotNull(created);
            assertEquals(AppUserEntity.ROLE_CAPOSALA, created.role);
            assertEquals(false, created.active, "il CAPOSALA standalone nasce in attesa di approvazione");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> AppUserEntity.delete("username", "capo_standalone"));
        }
    }

    @Test
    void invalidProfileStillRejected() {
        given().contentType(ContentType.JSON)
                .body("{\"username\":\"x\",\"password\":\"password123\"}")
                .when().post("/auth/register/complete").then()
                .statusCode(400)
                .body("error", equalTo("BAD_REQUEST"));
    }
}
