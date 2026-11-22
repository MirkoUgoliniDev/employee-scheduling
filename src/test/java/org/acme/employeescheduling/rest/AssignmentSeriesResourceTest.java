package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.acme.employeescheduling.persistence.LocationEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * @brief End-to-end test of recurring customer assignments.
 *
 * @details Covers the behaviour a planner depends on for a month horizon: one template produces
 *          one assignment per occurrence with its full team, generating twice adds nothing,
 *          extending the window only adds the new weeks, a cancelled occurrence releases its
 *          staff and is not resurrected by the next generation, a moved occurrence leaves the
 *          series alone, and deleting the series does not silently empty already planned days.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class AssignmentSeriesResourceTest {

    // 2026-07-06 is a Monday; July 2026 Mondays are 6, 13, 20, 27.
    private static final String FIRST_MONDAY = "2026-07-06";
    private static final String SECOND_MONDAY = "2026-07-13";
    private static final String THIRD_MONDAY = "2026-07-20";
    private static final String AUGUST_MONDAY = "2026-08-03";

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private int createStructure() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("id", 0, "name", "MVZ Serie " + UUID.randomUUID(),
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
        body.put("name", "Pflegeheim Sonnental " + UUID.randomUUID());
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

    private static Map<String, Object> requirement(String role, int headcount) {
        Map<String, Object> line = new HashMap<>();
        line.put("role", role);
        line.put("headcount", headcount);
        return line;
    }

    private static Map<String, Object> seriesBody(int customerId, int locationId) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("locationId", locationId);
        body.put("title", "Wundversorgung");
        body.put("service", "Wundversorgung");
        body.put("startTime", "08:00");
        body.put("durationMinutes", 120);
        body.put("priority", 2);
        body.put("fixedTime", true);
        body.put("patternType", "WEEKLY");
        body.put("weekdays", "1");
        body.put("nth", 0);
        body.put("effectiveFrom", FIRST_MONDAY);
        body.put("effectiveTo", "2026-12-31");
        body.put("active", true);
        body.put("requirements", List.of(
                requirement("PHYSICIAN", 1),
                requirement("MEDICAL_ASSISTANT", 2)));
        return body;
    }

    private int createSeries(int structureId, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/assignments/series?structureId={s}", structureId).then().statusCode(201)
                .extract().jsonPath().getInt("id");
    }

    private JsonPath generate(int seriesId, String from, String to) {
        return given().when()
                .post("/assignments/series/{id}/generate?from={f}&to={t}", seriesId, from, to)
                .then().statusCode(200).extract().jsonPath();
    }

    private JsonPath assignments(int structureId, String from, String to) {
        return given().when()
                .get("/assignments?structureId={s}&from={f}&to={t}", structureId, from, to)
                .then().statusCode(200).extract().jsonPath();
    }

    private static Map<String, Object> byDate(JsonPath assignments, String date) {
        List<Map<String, Object>> hits = assignments.getList("findAll { it.assignmentDate == '" + date + "' }");
        assertEquals(1, hits.size(), "expected exactly one assignment on " + date);
        return hits.get(0);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void oneTemplateProducesOneAssignmentPerOccurrenceWithItsWholeTeam() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));

        JsonPath created = given().contentType(ContentType.JSON)
                .body(seriesBody(customerId, locationId))
                .when().post("/assignments/series?structureId={s}", structureId)
                .then().statusCode(201).extract().jsonPath();
        int seriesId = created.getInt("id");
        assertEquals(0, created.getInt("occurrenceCount"), "creating the template plans nothing yet");
        assertEquals(2, created.getList("requirements").size());

        JsonPath generated = generate(seriesId, "2026-07-06", "2026-07-26");
        assertEquals(3, generated.getInt("generated"), "three Mondays in the period");
        assertEquals(9, generated.getInt("positions"), "1 physician + 2 MFAs per occurrence");

        JsonPath plan = assignments(structureId, "2026-07-06", "2026-07-26");
        List<Map<String, Object>> occurrences = plan.getList("findAll { it.seriesId == " + seriesId + " }");
        assertEquals(3, occurrences.size());
        for (Map<String, Object> occurrence : occurrences) {
            assertEquals(3, ((List<?>) occurrence.get("positions")).size());
            assertEquals("FIXED", occurrence.get("status"), "a fixed series pins the time");
        }
    }

    @Test
    void generatingAgainAddsNothingAndExtendingAddsOnlyTheNewWeeks() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        int seriesId = createSeries(structureId, seriesBody(customerId, locationId));

        assertEquals(3, generate(seriesId, "2026-07-06", "2026-07-26").getInt("generated"));

        JsonPath again = generate(seriesId, "2026-07-06", "2026-07-26");
        assertEquals(0, again.getInt("generated"), "idempotent: a second run must not duplicate");
        assertEquals(3, again.getInt("skipped"));
        assertEquals(0, again.getInt("positions"));

        // Moving the horizon forward adds the two Mondays that were outside the first window.
        JsonPath extended = generate(seriesId, "2026-07-06", "2026-08-09");
        assertEquals(2, extended.getInt("generated"), "only 27 July and 3 August are new");
        assertEquals(6, extended.getInt("positions"));
        assertNotNull(byDate(assignments(structureId, "2026-08-01", "2026-08-31"), AUGUST_MONDAY));
    }

    @Test
    void cancellingOneOccurrenceReleasesStaffAndSurvivesRegeneration() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        int seriesId = createSeries(structureId, seriesBody(customerId, locationId));
        generate(seriesId, "2026-07-06", "2026-07-26");

        given().when().post("/assignments/series/{id}/exceptions/cancel?date={d}", seriesId, SECOND_MONDAY)
                .then().statusCode(200).body("cancelled", org.hamcrest.Matchers.is(true));

        Map<String, Object> cancelled = byDate(assignments(structureId, "2026-07-01", "2026-07-31"), SECOND_MONDAY);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(0, ((List<?>) cancelled.get("positions")).size(),
                "a cancelled visit must not hold staff");

        // The exception is what stops the cancelled date from coming back.
        assertEquals(0, generate(seriesId, "2026-07-06", "2026-07-26").getInt("generated"));
        given().when().get("/assignments/series/{id}/exceptions", seriesId)
                .then().statusCode(200).body("size()", org.hamcrest.Matchers.is(1))
                .body("[0].kind", org.hamcrest.Matchers.is("CANCELLED"))
                .body("[0].occurrenceDate", org.hamcrest.Matchers.is(SECOND_MONDAY));

        // The other two Mondays are untouched: cancelling one date never rewrites the series.
        assertTrue(byDate(assignments(structureId, "2026-07-01", "2026-07-31"), FIRST_MONDAY)
                .get("status").equals("FIXED"));
        assertTrue(byDate(assignments(structureId, "2026-07-01", "2026-07-31"), THIRD_MONDAY)
                .get("status").equals("FIXED"));
    }

    @Test
    void anExceptionCanBeUndoneAndTheVisitComesBack() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        int seriesId = createSeries(structureId, seriesBody(customerId, locationId));
        generate(seriesId, "2026-07-06", "2026-07-26");
        given().when().post("/assignments/series/{id}/exceptions/cancel?date={d}", seriesId, SECOND_MONDAY)
                .then().statusCode(200);

        given().when().delete("/assignments/series/{id}/exceptions/{d}", seriesId, SECOND_MONDAY)
                .then().statusCode(200).body("generated", org.hamcrest.Matchers.is(1));

        Map<String, Object> restored = byDate(assignments(structureId, "2026-07-01", "2026-07-31"), SECOND_MONDAY);
        assertEquals(3, ((List<?>) restored.get("positions")).size());
        assertEquals("FIXED", restored.get("status"));
    }

    @Test
    void movingOneOccurrenceChangesOnlyThatDate() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        int seriesId = createSeries(structureId, seriesBody(customerId, locationId));
        generate(seriesId, "2026-07-06", "2026-07-26");

        given().when().post(
                "/assignments/series/{id}/exceptions/move?date={d}&newDate=2026-07-14&newTime=09:30",
                seriesId, SECOND_MONDAY)
                .then().statusCode(200).body("moved", org.hamcrest.Matchers.is(true));

        JsonPath plan = assignments(structureId, "2026-07-01", "2026-07-31");
        Map<String, Object> moved = byDate(plan, "2026-07-14");
        assertEquals(3, ((List<?>) moved.get("positions")).size(),
                "the team follows the occurrence to its new date");
        Map<?, ?> firstPosition = (Map<?, ?>) ((List<?>) moved.get("positions")).get(0);
        assertEquals("2026-07-14 09:30:00", firstPosition.get("start"),
                "the positions move with the occurrence and keep their start time");
        assertEquals("2026-07-14 11:30:00", firstPosition.get("end"),
                "and keep their configurable duration");
        assertEquals(0, plan.getList("findAll { it.assignmentDate == '" + SECOND_MONDAY + "' }").size());
    }

    @Test
    void deletingASeriesKeepsTheAlreadyPlannedVisits() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));
        int seriesId = createSeries(structureId, seriesBody(customerId, locationId));
        generate(seriesId, "2026-07-06", "2026-07-26");

        given().when().delete("/assignments/series/{id}", seriesId).then().statusCode(204);

        given().when().get("/assignments/series/{id}", seriesId).then().statusCode(404);
        JsonPath plan = assignments(structureId, "2026-07-06", "2026-07-26");
        List<Map<String, Object>> remaining = plan.getList("findAll { it.assignmentDate.startsWith('2026-07-') }");
        assertEquals(3, remaining.size(), "the plan must not be emptied by deleting the template");
        assertNull(remaining.get(0).get("seriesId"), "the visits become standalone assignments");
    }

    @Test
    void invalidSeriesAreRejectedWithAContractCode() {
        int structureId = createStructure();
        int locationId = createLocation(structureId);
        int customerId = createCustomer(structureId, List.of(locationId));

        Map<String, Object> badWeekdays = seriesBody(customerId, locationId);
        badWeekdays.put("weekdays", "9");
        given().contentType(ContentType.JSON).body(badWeekdays)
                .when().post("/assignments/series?structureId={s}", structureId)
                .then().statusCode(400).body("error", org.hamcrest.Matchers.is("SERIES_WEEKDAYS_INVALID"));

        Map<String, Object> badNth = seriesBody(customerId, locationId);
        badNth.put("patternType", "MONTHLY_NTH_WEEKDAY");
        badNth.put("nth", 0);
        given().contentType(ContentType.JSON).body(badNth)
                .when().post("/assignments/series?structureId={s}", structureId)
                .then().statusCode(400).body("error", org.hamcrest.Matchers.is("SERIES_NTH_INVALID"));

        Map<String, Object> noTeam = seriesBody(customerId, locationId);
        noTeam.put("requirements", List.of());
        given().contentType(ContentType.JSON).body(noTeam)
                .when().post("/assignments/series?structureId={s}", structureId)
                .then().statusCode(400)
                .body("error", org.hamcrest.Matchers.is("ASSIGNMENT_REQUIREMENT_REQUIRED"));

        given().when().get("/assignments/series/{id}", 999_999).then().statusCode(404);
    }
}
