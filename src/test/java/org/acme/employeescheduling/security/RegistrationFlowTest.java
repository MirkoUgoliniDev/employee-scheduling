package org.acme.employeescheduling.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.acme.employeescheduling.persistence.AppUserEntity;
import org.acme.employeescheduling.rest.OtpStore;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;

/**
 * @brief Self-registration via OTP: first user (active ADMIN) and subsequent users
 *        (CAPOSALA pending approval).
 *
 * @details The REST endpoints are public ({@code @PermitAll}). The OTP store is seeded with a
 *          known hash to verify the complete chain without depending on the mailer mock.
 *
 *          <p>The profile forces {@code app.registration.mode=server}, just as its sibling
 *          {@link RegistrationStandaloneFlowTest} forces {@code standalone}. Otherwise,
 *          {@code auto} derives the mode from the engine: tests run on SQLite, therefore in
 *          standalone mode, and every OTP call returned {@code 400 OTP_NOT_REQUIRED}.</p>
 */
@TestProfile(RegistrationFlowTest.ServerProfile.class)
@QuarkusTest
class RegistrationFlowTest {

    public static class ServerProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("app.registration.mode", "server");
        }
    }

    @Inject
    OtpStore store;

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @brief Ensures the table is not empty (with zero users, the next one becomes ADMIN). */
    private void ensureUsersExist() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUserEntity.count() == 0)
                AppUserEntity.create("placeholder-admin", "password123",
                        AppUserEntity.ROLE_ADMIN, "Placeholder").persist();
        });
    }

    @Test
    void requestOtpRejectsMalformedEmail() {
        given().contentType(ContentType.JSON).body("{\"email\":\"not-an-email\"}")
                .when().post("/auth/register/otp").then()
                .statusCode(400)
                .body("error", equalTo("EMAIL_INVALID"));
    }

    @Test
    void requestOtpRejectsAlreadyRegisteredEmail() {
        ensureUsersExist();
        QuarkusTransaction.requiringNew().run(() -> {
            AppUserEntity user = AppUserEntity.create("otp-duplicate", "password123",
                    AppUserEntity.ROLE_CAPOSALA, "Dup Email");
            user.email = "already-otp@example.com";
            user.persist();
        });
        try {
            given().contentType(ContentType.JSON).body("{\"email\":\"already-otp@example.com\"}")
                    .when().post("/auth/register/otp").then()
                    .statusCode(409)
                    .body("error", equalTo("EMAIL_ALREADY_REGISTERED"));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> AppUserEntity.delete("username", "otp-duplicate"));
        }
    }

    @Test
    void requestOtpSucceedsForFreshEmail() {
        ensureUsersExist();
        given().contentType(ContentType.JSON).body("{\"email\":\"fresh-otp@example.com\"}")
                .when().post("/auth/register/otp").then()
                .statusCode(200)
                .body("sent", equalTo(true));
    }

    @Test
    void verifyRejectsWrongOtp() {
        ensureUsersExist();
        String email = "wrong-otp@example.com";
        store.put(email, sha256("111111"));
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"otp\":\"999999\"}")
                .when().post("/auth/register/verify").then()
                .statusCode(400)
                .body("error", equalTo("OTP_INVALID"));
        store.invalidate(email);
    }

    @Test
    void fullFlowCreatesPendingCaposala() {
        ensureUsersExist();
        // Preventive cleanup: a failed run may have left the user in the test DB.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUserEntity.delete("username", "nuova_capo");
            AppUserEntity.delete("email", "complete-otp@example.com");
        });

        String email = "complete-otp@example.com";
        store.put(email, sha256("424242"));

        String token = given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"otp\":\"424242\"}")
                .when().post("/auth/register/verify").then()
                .statusCode(200)
                .extract().path("token");
        assertNotNull(token);

        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\",\"username\":\"nuova_capo\",\"password\":\"password123\"}")
                .when().post("/auth/register/complete").then()
                .statusCode(201)
                .body("created", equalTo(true))
                .body("pendingApproval", equalTo(true))
                .body("admin", equalTo(false));

        AppUserEntity created = AppUserEntity.findByUsername("nuova_capo");
        assertNotNull(created);
        assertEquals(AppUserEntity.ROLE_CAPOSALA, created.role);
        assertFalse(created.active, "il CAPOSALA nasce in attesa di approvazione");
        assertEquals(email, created.email);

        // Cleanup: the created user must not contaminate subsequent authentication tests.
        QuarkusTransaction.requiringNew().run(() -> AppUserEntity.delete("username", "nuova_capo"));
    }

    @Test
    void firstUserRegistrationCreatesActiveAdmin() {
        // Empty the table: the next user must be created as an active ADMIN.
        QuarkusTransaction.requiringNew().run(() -> AppUserEntity.deleteAll());
        try {
            given().when().get("/auth/register/status").then()
                    .statusCode(200)
                    .body("firstUser", equalTo(true));

            String email = "first-admin@example.com";
            store.put(email, sha256("135790"));

            String token = given().contentType(ContentType.JSON)
                    .body("{\"email\":\"" + email + "\",\"otp\":\"135790\"}")
                    .when().post("/auth/register/verify").then()
                    .statusCode(200)
                    .extract().path("token");

            given().contentType(ContentType.JSON)
                    .body("{\"token\":\"" + token + "\",\"username\":\"primo_admin\",\"password\":\"password123\"}")
                    .when().post("/auth/register/complete").then()
                    .statusCode(201)
                    .body("created", equalTo(true))
                    .body("pendingApproval", equalTo(false))
                    .body("admin", equalTo(true));

            AppUserEntity created = AppUserEntity.findByUsername("primo_admin");
            assertNotNull(created);
            assertEquals(AppUserEntity.ROLE_ADMIN, created.role);
            assertTrue(created.active, "il primo utente nasce attivo (non c'è nessuno che approvi)");
        } finally {
            // Restore a safe state for other tests (nonempty table).
            QuarkusTransaction.requiringNew().run(() -> {
                if (AppUserEntity.count() == 0)
                    AppUserEntity.create("restored-admin", "password123",
                            AppUserEntity.ROLE_ADMIN, "Restored").persist();
            });
        }
    }

    @Test
    void completeRejectsUnknownToken() {
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"000000-abcdefghijkl\",\"username\":\"x\",\"password\":\"password123\"}")
                .when().post("/auth/register/complete").then()
                .statusCode(400)
                .body("error", equalTo("OTP_INVALID"));
    }

    @Test
    void completeRejectsDuplicateUsername() {
        ensureUsersExist();
        // Create a user with the same username we will attempt to register.
        QuarkusTransaction.requiringNew().run(() ->
                AppUserEntity.create("dupnome", "password123", AppUserEntity.ROLE_CAPOSALA, "Dup").persist());
        try {
            String email = "dup-otp@example.com";
            store.put(email, sha256("777777"));
            String token = given().contentType(ContentType.JSON)
                    .body("{\"email\":\"" + email + "\",\"otp\":\"777777\"}")
                    .when().post("/auth/register/verify").then()
                    .statusCode(200)
                    .extract().path("token");

            given().contentType(ContentType.JSON)
                    .body("{\"token\":\"" + token + "\",\"username\":\"dupnome\",\"password\":\"password123\"}")
                    .when().post("/auth/register/complete").then()
                    .statusCode(409)
                    .body("error", equalTo("USER_DUPLICATE"));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> AppUserEntity.delete("username", "dupnome"));
            store.invalidate("dup-otp@example.com");
        }
    }
}
