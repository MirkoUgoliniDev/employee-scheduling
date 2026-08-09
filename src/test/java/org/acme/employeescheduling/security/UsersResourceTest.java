package org.acme.employeescheduling.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.acme.employeescheduling.persistence.AppUserEntity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * @brief Verifies that user management is protected and works correctly.
 */
@QuarkusTest
class UsersResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_USER = "admin-users";
    private static final String CAPOSALA_USER = "caposala-users";

    @BeforeAll
    static void seedUsers() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUserEntity.deleteAll();
            AppUserEntity.create(ADMIN_USER, "admin-pw-123", AppUserEntity.ROLE_ADMIN, "Admin Users").persist();
            AppUserEntity.create(CAPOSALA_USER, "caposala-pw-123", AppUserEntity.ROLE_CAPOSALA, "Caposala Users").persist();
        });
    }

    private ObjectNode newUserPayload(String username, String role) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("username", username);
        node.put("role", role);
        node.put("rawPassword", "password123");
        node.put("displayName", "Test User");
        return node;
    }

    @Test
    void anonymousCannotListUsers() {
        given().when().get("/users").then().statusCode(401);
    }

    @Test
    void anonymousCannotCreateUser() {
        given().contentType(ContentType.JSON).body(newUserPayload("newuser", "CAPOSALA"))
                .when().post("/users").then().statusCode(401);
    }

    @Test
    void anonymousCannotUpdateUser() {
        given().contentType(ContentType.JSON).body(newUserPayload("x", "ADMIN"))
                .when().put("/users/1").then().statusCode(401);
    }

    @Test
    void anonymousCannotDeleteUser() {
        given().when().delete("/users/1").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "capo", roles = "CAPOSALA")
    void caposalaCannotAccessUsers() {
        given().when().get("/users").then().statusCode(403);
        given().contentType(ContentType.JSON).body(newUserPayload("newuser", "CAPOSALA"))
                .when().post("/users").then().statusCode(403);
        given().when().delete("/users/1").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminCanListUsers() {
        given().when().get("/users").then()
                .statusCode(200)
                .body("size()", any(Integer.class));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminCanCreateUser() {
        given().contentType(ContentType.JSON)
                .body(newUserPayload("new-test-user", "CAPOSALA"))
                .when().post("/users").then()
                .statusCode(201)
                .body("username", equalTo("new-test-user"))
                .body("role", equalTo("CAPOSALA"))
                .body("active", equalTo(true));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminCannotCreateDuplicateUser() {
        seedUsers();
        given().contentType(ContentType.JSON)
                .body(newUserPayload(ADMIN_USER, "ADMIN"))
                .when().post("/users").then()
                .statusCode(409)
                .body("error", equalTo("USER_DUPLICATE"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminCannotCreateUserWithoutPassword() {
        ObjectNode node = newUserPayload("nopw-user", "CAPOSALA");
        node.putNull("rawPassword");
        given().contentType(ContentType.JSON)
                .body(node)
                .when().post("/users").then()
                .statusCode(400)
                .body("error", equalTo("USER_PASSWORD_REQUIRED"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminCannotCreateUserWithInvalidRole() {
        given().contentType(ContentType.JSON)
                .body(newUserPayload("badrole-user", "SUPERUSER"))
                .when().post("/users").then()
                .statusCode(400)
                .body("error", equalTo("USER_ROLE_INVALID"));
    }

  @Test
  @TestSecurity(user = "admin", roles = "ADMIN")
  void adminCanUpdateUserRole() {
    AppUserEntity user = QuarkusTransaction.requiringNew().call(() -> {
      AppUserEntity u = AppUserEntity.create("update-test", "pw123", AppUserEntity.ROLE_CAPOSALA, "Updater");
      u.persistAndFlush();
      return u;
    });

    ObjectNode update = MAPPER.createObjectNode();
    update.put("username", "update-test");
    update.put("role", "ADMIN");
    update.put("displayName", "Updater");
    update.put("active", true);

    given().contentType(ContentType.JSON)
            .body(update)
            .when().put("/users/{id}", user.id).then()
            .statusCode(200)
            .body("role", equalTo("ADMIN"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "ADMIN")
  void adminCanDeactivateUser() {
    AppUserEntity user = QuarkusTransaction.requiringNew().call(() -> {
      AppUserEntity u = AppUserEntity.create("deactivate-test", "pw123", AppUserEntity.ROLE_CAPOSALA, "To Deactivate");
      u.persistAndFlush();
      return u;
    });

    given().when().delete("/users/{id}", user.id).then()
            .statusCode(200)
            .body("deactivated", equalTo(true));

    QuarkusTransaction.requiringNew().run(() -> {
      AppUserEntity refreshed = AppUserEntity.findById(user.id);
      org.junit.jupiter.api.Assertions.assertFalse(refreshed.active);
    });
  }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void adminCannotDeactivateNonExistentUser() {
        given().when().delete("/users/999999").then()
                .statusCode(404)
                .body("error", equalTo("USER_NOT_FOUND"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void passwordHashIsNeverExposedInList() {
        given().when().get("/users").then()
                .statusCode(200)
                 .body(not(containsString("passwordHash")));
    }
}
