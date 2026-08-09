package org.acme.employeescheduling.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.acme.employeescheduling.persistence.AppUserEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * @brief Exercises the REAL login flow, from credentials to the session cookie.
 *
 * @details Other tests declare an identity with {@code @TestSecurity}, skipping the entire real
 *          chain: bcrypt hash verification, identity lookup in app_users, form authentication,
 *          cookie issuance, and role propagation. Without this class, we could ship a login flow
 *          that had never worked even once.
 */
@QuarkusTest
class RealLoginFlowTest {

    private static final String USERNAME = "login-flow-caposala";
    private static final String PASSWORD = "una-password-di-prova-robusta";

    @BeforeAll
    static void createUser() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUserEntity.findByUsername(USERNAME) == null)
                AppUserEntity.create(USERNAME, PASSWORD, AppUserEntity.ROLE_CAPOSALA, "Prova Accesso")
                        .persist();
        });
    }

    /** @brief Logs in and returns the issued session cookie. */
    private static String login(String user, String password) {
        Response response = given()
                .contentType(ContentType.URLENC)
                .formParam("j_username", user)
                .formParam("j_password", password)
                .redirects().follow(false)
                .when().post("/j_security_check");
        return response.getDetailedCookies().hasCookieWithName("employee_scheduling_session")
                ? response.getCookie("employee_scheduling_session") : null;
    }

    @Test
    void correctCredentialsProduceAUsableSession() {
        String cookie = login(USERNAME, PASSWORD);
        assertNotNull(cookie, "nessun cookie di sessione emesso: il login non ha funzionato");

        // The cookie must actually work: its bearer is recognized and has the proper role.
        given().cookie("employee_scheduling_session", cookie)
                .when().get("/auth/me").then()
                .statusCode(200)
                .body("authenticated", is(true))
                .body("username", equalTo(USERNAME))
                .body("admin", is(false));

        given().cookie("employee_scheduling_session", cookie)
                .when().get("/demo-data/getlocations").then().statusCode(200);
    }

    @Test
    void theRoleFromTheDatabaseIsEnforced() {
        String cookie = login(USERNAME, PASSWORD);
        assertNotNull(cookie);
        // The user is CAPOSALA in the table: administration must remain inaccessible.
        given().cookie("employee_scheduling_session", cookie)
                .when().get("/email/settings").then().statusCode(403);
    }

    @Test
    void aWrongPasswordDoesNotAuthenticate() {
        given().cookie("employee_scheduling_session", String.valueOf(login(USERNAME, "password-sbagliata")))
                .when().get("/auth/me").then()
                .statusCode(200)
                .body("authenticated", is(false));
    }

    @Test
    void anUnknownUserDoesNotAuthenticate() {
        given().cookie("employee_scheduling_session", String.valueOf(login("non-esiste", PASSWORD)))
                .when().get("/auth/me").then()
                .statusCode(200)
                .body("authenticated", is(false));
    }
}
