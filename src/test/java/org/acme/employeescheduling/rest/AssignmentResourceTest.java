package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.acme.employeescheduling.persistence.CustomerAssignmentEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * @brief End-to-end test of the customer-centric vertical slice.
 *
 * @details Drives the same HTTP contract the German UI uses: customer → location → staffing
 *          requirement → assignment with a configurable duration → materialised positions →
 *          duration change with minimal churn → unfilled/conflict views → deletion. The solver
 *          itself is exercised separately; here the contract is what matters, because every
 *          assertion below fails if the customer layer stops producing solver-facing positions.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class AssignmentResourceTest {

    private static final String DATE = "2026-07-09";
    private static final String UNIQUE = UUID.randomUUID().toString().substring(0, 8);

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private int createStructure() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("id", 0, "name", "MVZ Test " + UUID.randomUUID(),
                        "address", "Teststrasse 1", "phone", "+49 000"))
                .when().post("/structures").then().statusCode(201)
                .extract().jsonPath().getInt("id");
    }

    private int createLocation(int structureId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            LocationEntity location = new LocationEntity();
            location.name = "MVZ Nord " + UUID.randomUUID();
            location.structureId = structureId;
            location.active = true;
            location.persist();
            return location.id;
        });
    }

    private int createCustomer(int structureId, List<Integer> locationIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", 0);
        body.put("code", "K-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("name", "Pflegeheim Sonnental " + UNIQUE);
        body.put("address", "Sonnenweg 4");
        body.put("phone", "+49 30 000");
        body.put("active", true);
        body.put("defaultStartTime", "08:00");
        body.put("defaultDurationMinutes", 120);
        body.put("locationIds", locationIds);
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/customers?structureId={s}", structureId).then().statusCode(201)
                .extract().jsonPath().getInt("id");
    }

    private int createEmployee(int structureId, String role) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmployeeEntity employee = new EmployeeEntity();
            employee.code = "E-" + UUID.randomUUID().toString().substring(0, 12);
            employee.firstName = "Test";
            employee.lastName = "Operator";
            employee.structureId = structureId;
            employee.role = role;
            employee.persist();
            return employee.id;
        });
    }

    private static Map<String, Object> requirement(String role, int headcount) {
        Map<String, Object> line = new HashMap<>();
        line.put("role", role);
        line.put("headcount", headcount);
        return line;
    }

    private static Map<String, Object> assignmentBody(int customerId, int locationId,
            int durationMinutes, int physicianCount, int assistantCount, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", 0);
        body.put("customerId", customerId);
        body.put("locationId", locationId);
        body.put("title", "Wundversorgung");
        body.put("service", "Wundversorgung");
        body.put("assignmentDate", DATE);
        body.put("startTime", "08:00");
        body.put("durationMinutes", durationMinutes);
        body.put("priority", 1);
        body.put("status", status);
        body.put("fixedTime", false);
        body.put("requirements", List.of(
                requirement("PHYSICIAN", physicianCount),
                requirement("MEDICAL_ASSISTANT", assistantCount)));
        return body;
    }

    private JsonPath createAssignment(int structureId, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/assignments?structureId={s}", structureId).then().statusCode(201)
                .extract().jsonPath();
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void customerKeepsItsLocations() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));

        given().when().get("/customers?structureId={s}", structureId).then().statusCode(200)
                .body("find { it.id == " + customerId + " }.locationIds", hasItem(locationId));

        Integer storedCustomerId = QuarkusTransaction.requiringNew().call(
                () -> LocationEntity.<LocationEntity>findById(locationId).customerId);
        assertEquals(customerId, storedCustomerId, "locations.customer_id must carry the association");
    }

    @Test
    void assignmentMaterialisesOnePositionPerRequiredTeamMember() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));

        JsonPath assignment = createAssignment(structureId,
                assignmentBody(customerId, locationId, 120, 1, 2, "FLEXIBLE"));

        assertEquals(3, assignment.getList("positions").size(), "1 physician + 2 MFAs = 3 positions");
        assertEquals(3, assignment.getInt("requiredHeadcount"));
        assertEquals(0, assignment.getInt("filledPositions"));
        assertEquals(3, assignment.getInt("unfilledPositions"));
        assertEquals(List.of(""), assignment.getList("positions.employeeName").stream()
                .distinct().toList());
        assertEquals("2026-07-09 10:00:00", assignment.getString("positions[0].end"),
                "the configurable duration defines the end of the position");
        assertEquals(1, assignment.getList("positions.role").stream()
                .filter("PHYSICIAN"::equals).count());
    }

    @Test
    void durationChangeKeepsTheTeamAndThePositionIds() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        JsonPath created = createAssignment(structureId,
                assignmentBody(customerId, locationId, 120, 1, 2, "FLEXIBLE"));
        int assignmentId = created.getInt("id");
        List<Integer> before = created.getList("positions.id");

        // Pretend the solver already staffed the first position: a duration change must not
        // throw that employee away, otherwise every edit would cause a full re-plan.
        int employeeId = createEmployee(structureId, "PHYSICIAN");
        QuarkusTransaction.requiringNew().run(() -> {
            ShiftEntity shift = ShiftEntity.findById(before.get(0));
            shift.employeeId = employeeId;
        });

        Map<String, Object> body = assignmentBody(customerId, locationId, 180, 1, 2, "FLEXIBLE");
        body.put("id", assignmentId);
        JsonPath updated = given().contentType(ContentType.JSON).body(body)
                .when().put("/assignments/{id}?structureId={s}", assignmentId, structureId)
                .then().statusCode(200).extract().jsonPath();

        assertEquals(before, updated.getList("positions.id"), "position ids must survive the edit");
        assertEquals(3, updated.getList("positions").size());
        assertEquals(1, updated.getInt("filledPositions"), "the assigned employee must survive");
        assertEquals("2026-07-09 11:00:00", updated.getString("positions[0].end"),
                "every position follows the new duration");
    }

    @Test
    void reducedTeamDeletesOnlyTheSurplusPositions() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        JsonPath created = createAssignment(structureId,
                assignmentBody(customerId, locationId, 120, 1, 2, "FLEXIBLE"));
        int assignmentId = created.getInt("id");
        List<Integer> before = created.getList("positions.id");

        Map<String, Object> body = assignmentBody(customerId, locationId, 120, 1, 1, "FLEXIBLE");
        body.put("id", assignmentId);
        JsonPath updated = given().contentType(ContentType.JSON).body(body)
                .when().put("/assignments/{id}?structureId={s}", assignmentId, structureId)
                .then().statusCode(200).extract().jsonPath();

        assertEquals(2, updated.getList("positions").size());
        assertTrue(before.containsAll(updated.getList("positions.id")),
                "the remaining positions keep their ids");
    }

    @Test
    void cancelledAssignmentKeepsNoOpenPositions() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        JsonPath created = createAssignment(structureId,
                assignmentBody(customerId, locationId, 120, 1, 1, "FLEXIBLE"));
        int assignmentId = created.getInt("id");

        Map<String, Object> body = assignmentBody(customerId, locationId, 120, 1, 1, "CANCELLED");
        body.put("id", assignmentId);
        given().contentType(ContentType.JSON).body(body)
                .when().put("/assignments/{id}?structureId={s}", assignmentId, structureId)
                .then().statusCode(200)
                .body("positions.size()", is(0));

        Long openShifts = QuarkusTransaction.requiringNew().call(
                () -> ShiftEntity.count("assignmentId = ?1", assignmentId));
        assertEquals(0L, openShifts, "a cancelled visit must not hold staff");
    }

    @Test
    void unfilledPositionsAreListedExplicitly() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        createAssignment(structureId, assignmentBody(customerId, locationId, 120, 1, 2, "FLEXIBLE"));

        given().when().get("/assignments/unfilled?structureId={s}&from={f}&to={t}",
                        structureId, DATE, DATE)
                .then().statusCode(200)
                .body("size()", is(3))
                .body("[0].employeeId", equalTo(null));
    }

    @Test
    void pinnedPositionInvalidatedByAnAbsenceIsReportedAsAConflict() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        JsonPath created = createAssignment(structureId,
                assignmentBody(customerId, locationId, 120, 1, 1, "FLEXIBLE"));
        int assignmentId = created.getInt("id");
        int shiftId = (Integer) created.getList("positions.id").get(0);
        int physicianId = createEmployee(structureId, "PHYSICIAN");

        QuarkusTransaction.requiringNew().run(() -> {
            ShiftEntity shift = ShiftEntity.findById(shiftId);
            shift.employeeId = physicianId;
            shift.pinned = true;
            EmployeeDateEntity absence = new EmployeeDateEntity();
            absence.employeeId = physicianId;
            absence.dateTypeId = EmployeeDateEntity.TYPE_UNAVAILABLE;
            absence.dateStart = DATE + " 00:00:00";
            absence.dateEnd = DATE + " 23:59:59";
            absence.persist();
        });

        JsonPath conflicts = given().when()
                .get("/assignments/conflicts?structureId={s}&from={f}&to={t}", structureId, DATE, DATE)
                .then().statusCode(200).extract().jsonPath();

        List<Map<String, Object>> rows = conflicts.getList("");
        Map<String, Object> absence = rows.stream()
                .filter(row -> Integer.valueOf(shiftId).equals(row.get("shiftId")))
                .findFirst().orElse(null);
        assertNotNull(absence, "a locked position overlapping an absence must be reported");
        assertEquals("ABSENCE", absence.get("conflictType"));
        assertEquals(physicianId, absence.get("employeeId"));
        assertNotEquals(null, absence.get("assignmentId"));
    }

    @Test
    void deletesTheAssignmentWithItsPositions() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        int assignmentId = createAssignment(structureId,
                assignmentBody(customerId, locationId, 120, 1, 1, "FLEXIBLE")).getInt("id");

        given().when().delete("/assignments/{id}?structureId={s}", assignmentId, structureId)
                .then().statusCode(204);

        Long remaining = QuarkusTransaction.requiringNew().call(
                () -> ShiftEntity.count("assignmentId = ?1", assignmentId));
        assertEquals(0L, remaining);
        assertEquals(null, QuarkusTransaction.requiringNew().call(
                () -> CustomerAssignmentEntity.<CustomerAssignmentEntity>findById(assignmentId)));
    }

    @Test
    void rejectsAnAssignmentWithoutStaffingRequirements() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));

        Map<String, Object> body = assignmentBody(customerId, locationId, 120, 1, 1, "FLEXIBLE");
        body.put("requirements", new ArrayList<>());
        given().contentType(ContentType.JSON).body(body)
                .when().post("/assignments?structureId={s}", structureId).then().statusCode(400)
                .body("error", equalTo("ASSIGNMENT_REQUIREMENT_REQUIRED"));
    }

    @Test
    void rejectsAnUnknownRole() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));

        Map<String, Object> body = assignmentBody(customerId, locationId, 120, 1, 1, "FLEXIBLE");
        body.put("requirements", List.of(requirement("NURSE", 1)));
        given().contentType(ContentType.JSON).body(body)
                .when().post("/assignments?structureId={s}", structureId).then().statusCode(400)
                .body("error", equalTo("ASSIGNMENT_ROLE_INVALID"));
    }
}
