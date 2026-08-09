package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SpecialistEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * @brief Empty required fields are rejected with a machine-readable code on both engines.
 *
 * @details The schema declares NOT NULL, but NOT NULL does not reject an empty string: without
 *          these checks, an operator without a name entered the database and appeared as an
 *          anonymous row in grids, reports, and email recipients. The tests verify two distinct
 *          properties: the request is rejected and it has <em>not</em> left a partial row behind.
 *
 *          Whitespace-only input is the case that escapes the UI: the HTML {@code required}
 *          attribute considers a single space valid, so the defense must also exist behind
 *          the API.
 */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class RequiredFieldValidationTest {

    // ─── Operators ────────────────────────────────────────────────────────────

    @Test
    void employeeWithBlankFirstNameIsRejected() {
        int structureId = createStructure("required-employee-first");
        String code = unique("RFN");
        postEmployee(structureId, code, "   ", "Rossi").then()
                .statusCode(400).body("error", equalTo("EMPLOYEE_FIRST_NAME_REQUIRED"));
        assertEquals(0L, employeeCountByCode(code), "l'operatore rifiutato non deve essere stato creato");
    }

    @Test
    void employeeWithBlankLastNameIsRejected() {
        int structureId = createStructure("required-employee-last");
        String code = unique("RLN");
        postEmployee(structureId, code, "Mario", "\t ").then()
                .statusCode(400).body("error", equalTo("EMPLOYEE_LAST_NAME_REQUIRED"));
        assertEquals(0L, employeeCountByCode(code));
    }

    @Test
    void employeeWithBlankCodeIsRejected() {
        int structureId = createStructure("required-employee-code");
        postEmployee(structureId, " ", "Mario", "Rossi").then()
                .statusCode(400).body("error", equalTo("EMPLOYEE_CODE_REQUIRED"));
    }

    @Test
    void employeeWithValidFieldsIsStillAccepted() {
        int structureId = createStructure("required-employee-ok");
        String code = unique("ROK");
        postEmployee(structureId, code, "Mario", "Rossi").then().statusCode(201);
        assertEquals(1L, employeeCountByCode(code));
    }

    @Test
    void employeeUpdateWithBlankFieldsIsRejectedAndLeavesTheRecordIntact() {
        int structureId = createStructure("required-employee-update");
        String code = unique("RUP");
        postEmployee(structureId, code, "Mario", "Rossi").then().statusCode(201);
        int employeeId = QuarkusTransaction.requiringNew().call(() ->
                EmployeeEntity.<EmployeeEntity>find("code", code).firstResult().id);

        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(employeeBody(employeeId, code, "", "Rossi"))
                .when().put("/demo-data/updateemployee/{id}", employeeId).then()
                .statusCode(400).body("error", equalTo("EMPLOYEE_FIRST_NAME_REQUIRED"));

        given().queryParam("structureId", structureId).when()
                .get("/demo-data/getemployee/{id}", employeeId).then()
                .statusCode(200).body("firstName", equalTo("Mario"));
    }

    @Test
    void duplicateEmployeeCodeReportsAMachineCode() {
        int structureId = createStructure("required-employee-duplicate");
        String code = unique("RDUP");
        postEmployee(structureId, code, "Mario", "Rossi").then().statusCode(201);
        postEmployee(structureId, code, "Luigi", "Bianchi").then()
                .statusCode(409).body("error", equalTo("EMPLOYEE_CODE_IN_USE"));
    }

    @Test
    void invalidEmployeeEmailReportsAMachineCode() {
        int structureId = createStructure("required-employee-email");
        Map<String, Object> body = employeeBody(0, unique("RML"), "Mario", "Rossi");
        body.put("email", "non-una-email");
        given().contentType(ContentType.JSON).queryParam("structureId", structureId).body(body)
                .when().post("/demo-data/addemployee").then()
                .statusCode(400).body("error", equalTo("EMPLOYEE_EMAIL_INVALID"));
    }

    // ─── Specialists ──────────────────────────────────────────────────────────

    @Test
    void specialistWithBlankFirstNameIsRejected() {
        int structureId = createStructure("required-specialist-first");
        String code = unique("RSF");
        postSpecialist(structureId, code, " ", "Bianchi").then()
                .statusCode(400).body("error", equalTo("SPECIALIST_FIRST_NAME_REQUIRED"));
        assertEquals(0L, specialistCountByCode(code));
    }

    @Test
    void specialistWithBlankCodeIsRejected() {
        int structureId = createStructure("required-specialist-code");
        postSpecialist(structureId, "  ", "Anna", "Bianchi").then()
                .statusCode(400).body("error", equalTo("SPECIALIST_CODE_REQUIRED"));
    }

    @Test
    void specialistWithValidFieldsIsStillAccepted() {
        int structureId = createStructure("required-specialist-ok");
        String code = unique("RSOK");
        postSpecialist(structureId, code, "Anna", "Bianchi").then().statusCode(201);
        assertEquals(1L, specialistCountByCode(code));
    }

    // ─── Locations ────────────────────────────────────────────────────────────

    @Test
    void locationWithBlankNameIsRejected() {
        int structureId = createStructure("required-location-name");
        String code = unique("RLC");
        postLocation(structureId, code, "   ", 3).then()
                .statusCode(400).body("error", equalTo("LOCATION_NAME_REQUIRED"));
        assertEquals(0L, locationCountByCode(code));
    }

    @Test
    void locationWithoutOrderIsRejectedWithADistinctCode() {
        int structureId = createStructure("required-location-order");
        postLocation(structureId, unique("RLO"), "Ambulatorio", 0).then()
                .statusCode(400).body("error", equalTo("LOCATION_ORDER_REQUIRED"));
    }

    @Test
    void locationWithValidFieldsIsStillAccepted() {
        int structureId = createStructure("required-location-ok");
        String code = unique("RLOK");
        postLocation(structureId, code, "Ambulatorio", 5).then().statusCode(201);
        assertEquals(1L, locationCountByCode(code));
    }

    @Test
    void duplicateLocationCodeReportsAMachineCode() {
        int structureId = createStructure("required-location-duplicate");
        String code = unique("RLDUP");
        postLocation(structureId, code, "Ambulatorio", 5).then().statusCode(201);
        postLocation(structureId, code, "Ambulatorio 2", 6).then()
                .statusCode(409).body("error", equalTo("LOCATION_CODE_IN_USE"));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private static io.restassured.response.Response postEmployee(
            int structureId, String code, String firstName, String lastName) {
        return given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(employeeBody(0, code, firstName, lastName))
                .when().post("/demo-data/addemployee");
    }

    private static java.util.HashMap<String, Object> employeeBody(
            int id, String code, String firstName, String lastName) {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("id", id);
        body.put("code", code);
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("email", "");
        body.put("active", true);
        body.put("skills", List.of());
        body.put("desiredDates", List.of());
        body.put("undesiredDates", List.of());
        body.put("unavailableDates", List.of());
        return body;
    }

    private static io.restassured.response.Response postSpecialist(
            int structureId, String code, String firstName, String lastName) {
        return given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("id", 0, "code", code, "firstName", firstName, "lastName", lastName,
                        "email", "", "active", true))
                .when().post("/specialists");
    }

    private static io.restassured.response.Response postLocation(
            int structureId, String code, String name, int order) {
        return given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("id", 0, "code", code, "name", name, "order", order, "active", true,
                        "requiredSkill", List.of(), "optionalSkill", List.of()))
                .when().post("/demo-data/addlocation");
    }

    private static int createStructure(String prefix) {
        return QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = new StructureEntity();
            structure.name = unique(prefix);
            structure.persistAndFlush();
            return structure.id;
        });
    }

    private static long employeeCountByCode(String code) {
        return QuarkusTransaction.requiringNew().call(() -> EmployeeEntity.count("code", code));
    }

    private static long specialistCountByCode(String code) {
        return QuarkusTransaction.requiringNew().call(() -> SpecialistEntity.count("code", code));
    }

    private static long locationCountByCode(String code) {
        return QuarkusTransaction.requiringNew().call(() -> LocationEntity.count("code", code));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
