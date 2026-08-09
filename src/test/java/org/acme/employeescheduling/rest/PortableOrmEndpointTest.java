package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.GeneralSettingsEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.LocationSkillEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.persistence.SpecialistEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Endpoint contract executed unchanged against real SQLite and PostgreSQL.
 * Fixtures use generated business keys so the PostgreSQL test database can be
 * reused without hiding uniqueness errors behind a schema reset.
 */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class PortableOrmEndpointTest {

    @Test
    void structureCrudIsPortable() {
        String name = unique("Portable structure");
        int structureId = given().contentType(ContentType.JSON)
                .body(Map.of("id", 0, "name", name, "address", "Via Roma 1", "phone", "+39 010 000"))
                .when().post("/structures").then().statusCode(201)
                .extract().jsonPath().getInt("id");

        given().when().get("/structures").then().statusCode(200)
                .body("find { it.id == " + structureId + " }.name", equalTo(name));
        String updatedName = unique("Portable structure updated");
        given().contentType(ContentType.JSON)
                .body(Map.of("id", structureId, "name", updatedName,
                        "address", "Via Milano 2", "phone", "+39 020 000"))
                .when().put("/structures/{id}", structureId).then().statusCode(200);
        given().when().get("/structures").then().statusCode(200)
                .body("find { it.id == " + structureId + " }.name", equalTo(updatedName));

        given().when().delete("/structures/{id}", structureId).then().statusCode(200);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> StructureEntity.count("id", structureId)));
    }

    @Test
    void employeeDateCrudIsPortable() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = persistStructure("portable-date-owner");
            EmployeeEntity employee = new EmployeeEntity();
            employee.code = unique("PDATE"); employee.firstName = "Date"; employee.lastName = "Owner";
            employee.structureId = structure.id; employee.active = true; employee.persistAndFlush();
            return new int[] { structure.id, employee.id };
        });
        int dateId = given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .body(Map.of("id", 0, "employee_id", ids[1],
                        "dateStart", "2037-03-01T08:00:00", "dateEnd", "2037-03-01T12:00:00",
                        "dateTypeId", EmployeeDateEntity.TYPE_DESIRED))
                .when().post("/demo-data/add_employee_dates/{employeeId}", ids[1]).then().statusCode(201)
                .extract().jsonPath().getInt("id");

        given().queryParam("structureId", ids[0]).when().get("/demo-data/employee-dates-summary").then()
                .statusCode(200)
                .body("find { it.employee_id == " + ids[1] + " }.desired", equalTo(1))
                .body("find { it.employee_id == " + ids[1] + " }.total", equalTo(1));

        given().queryParam("structureId", ids[0]).when()
                .get("/demo-data/editemployeedate/{id}", dateId).then().statusCode(200)
                .body("[0].employee_id", equalTo(ids[1]))
                .body("[0].dateTypeId", equalTo(EmployeeDateEntity.TYPE_DESIRED));
        given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .body(Map.of("id", dateId, "employee_id", ids[1],
                        "dateStart", "2037-03-02T18:00:00", "dateEnd", "2037-03-03T06:00:00",
                        "dateTypeId", EmployeeDateEntity.TYPE_UNAVAILABLE))
                .when().put("/demo-data/update_employee_dates/{id}", dateId).then().statusCode(200);
        given().queryParam("structureId", ids[0]).when()
                .get("/demo-data/getemployeedates/{employeeId}/", ids[1]).then().statusCode(200)
                .body("find { it.id == " + dateId + " }.dateTypeId",
                        equalTo(EmployeeDateEntity.TYPE_UNAVAILABLE));

        given().queryParam("structureId", ids[0]).when()
                .delete("/demo-data/delete_date/{id}", dateId).then().statusCode(200);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> EmployeeDateEntity.count("id", dateId)));
    }

    @Test
    void localizedAndDynamicLabelsUseSharedOrmQueries() {
        int structureId = createStructure("portable-label-owner");
        int skillId = createSkill("Portable dynamic label skill", structureId);
        int locationId = QuarkusTransaction.requiringNew().call(() -> {
            LocationEntity location = new LocationEntity();
            location.code = unique("PLABEL"); location.name = unique("Portable dynamic location");
            location.structureId = structureId; location.active = true; location.persistAndFlush();
            return location.id;
        });
        String key = unique("portable.label");
        int labelId = given().contentType(ContentType.JSON)
                .body(Map.of("id", 0, "key", key, "description", "Portable label"))
                .when().post("/labels").then().statusCode(201).extract().jsonPath().getInt("id");
        QuarkusTransaction.requiringNew().run(() -> {
            LanguageEntity language = LanguageEntity.find("active", true).firstResult();
            LocalizzazioneEntity translation = new LocalizzazioneEntity();
            translation.entityType = "labels"; translation.entityId = labelId; translation.fieldName = "value";
            translation.languageId = language.id; translation.value = "Etichetta portabile"; translation.persistAndFlush();
        });

        given().when().get("/labels/localized").then().statusCode(200)
                .body("find { it.id == " + labelId + " }.translatedValue", equalTo("Etichetta portabile"));
        given().queryParam("structureId", structureId).when().get("/labels/dynamic-names").then().statusCode(200)
                .body("find { it.key == 'skill." + skillId + "' }.entityId", equalTo(skillId))
                .body("find { it.key == 'location." + locationId + "' }.entityId", equalTo(locationId));

        given().when().delete("/labels/{id}", labelId).then().statusCode(200);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> LabelEntity.count("id", labelId)));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> LocalizzazioneEntity.count(
                "entityType = ?1 and entityId = ?2", "labels", labelId)));
        deleteSkill(skillId, structureId);
    }

    @Test
    void addLabelWithTranslationsPersistsFieldNameForLocalizedLookup() {
        int languageId = QuarkusTransaction.requiringNew().call(
                () -> LanguageEntity.<LanguageEntity>find("active", true).firstResult().id);
        String key = unique("portable.label.translated");
        // POST /labels with populated translations: regression for LabelResource#saveLabelTranslations,
        // which must set fieldName="value" on the row or insertion fails (ORM/DB-level NOT NULL).
        int labelId = given().contentType(ContentType.JSON)
                .body(Map.of("id", 0, "key", key, "description", "Portable translated label",
                        "translations", Map.of(String.valueOf(languageId), "Valore tradotto")))
                .when().post("/labels").then().statusCode(201).extract().jsonPath().getInt("id");

        given().when().get("/labels/localized").then().statusCode(200)
                .body("find { it.id == " + labelId + " }.translatedValue", equalTo("Valore tradotto"));

        given().when().delete("/labels/{id}", labelId).then().statusCode(200);
    }

    @Test
    void updateLabelWithoutTranslationsClearsExistingOnes() {
        int languageId = QuarkusTransaction.requiringNew().call(
                () -> LanguageEntity.<LanguageEntity>find("active", true).firstResult().id);
        String key = unique("portable.label.clear");
        int labelId = given().contentType(ContentType.JSON)
                .body(Map.of("id", 0, "key", key, "description", "Portable label to clear",
                        "translations", Map.of(String.valueOf(languageId), "Valore iniziale")))
                .when().post("/labels").then().statusCode(201).extract().jsonPath().getInt("id");
        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> LocalizzazioneEntity.count(
                "entityType = ?1 and entityId = ?2", "labels", labelId)));

        // PUT without the "translations" field: must clear existing translations
        // (replace semantics), rather than leaving them stale.
        given().contentType(ContentType.JSON)
                .body(Map.of("id", labelId, "key", key, "description", "Portable label to clear"))
                .when().put("/labels/{id}", labelId).then().statusCode(200);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> LocalizzazioneEntity.count(
                "entityType = ?1 and entityId = ?2", "labels", labelId)));

        given().when().delete("/labels/{id}", labelId).then().statusCode(200);
    }

    @Test
    void daytimeShiftCrudAndSnapshotArePortable() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = persistStructure("portable-shift-owner");
            LocationEntity location = new LocationEntity();
            location.code = unique("PSHIFT"); location.name = "Portable shift location";
            location.structureId = structure.id; location.active = true; location.persistAndFlush();
            return new int[] { structure.id, location.id };
        });
        // Skills are created inside the shift's structure, which is created before them.
        int requiredSkillId = createSkill("Portable shift required skill", ids[0]);
        int optionalSkillId = createSkill("Portable shift optional skill", ids[0]);
        int shiftId = given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .body(Map.of("id", 0, "location_id", ids[1],
                        "start", "2037-04-01T08:00:00", "end", "2037-04-01T16:00:00",
                        "requiredSkills", List.of(Map.of("id", requiredSkillId)), "optionalSkills", List.of()))
                .when().post("/demo-data/addshift").then().statusCode(201)
                .extract().jsonPath().getInt("id");
        assertEquals(1L, shiftSkillCount(shiftId, requiredSkillId, 1));

        given().queryParam("structureId", ids[0]).when().get("/demo-data/editshift/{id}", shiftId).then()
                .statusCode(200).body("shift.start", equalTo("2037-04-01T08:00:00"))
                .body("shift.end", equalTo("2037-04-01T16:00:00"))
                .body("shift.requiredSkills.find { it.id == " + requiredSkillId + " }.used", equalTo(true));
        given().queryParam("structureId", ids[0])
                .queryParam("start", "2037-04-01T00:00:00").queryParam("end", "2037-04-02T00:00:00")
                .when().get("/demo-data/generate").then().statusCode(200)
                .body("shifts.find { it.id == " + shiftId + " }.start", equalTo("2037-04-01T08:00:00"))
                .body("shifts.find { it.id == " + shiftId + " }.end", equalTo("2037-04-01T16:00:00"));

        given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .body(Map.of("id", shiftId, "location_id", ids[1],
                        "start", "2037-04-01T09:00:00", "end", "2037-04-01T17:00:00",
                        "requiredSkills", List.of(), "optionalSkills", List.of(Map.of("id", optionalSkillId))))
                .when().put("/demo-data/updateshift/{id}", shiftId).then().statusCode(200);
        assertEquals(0L, shiftSkillCount(shiftId, requiredSkillId, 1));
        assertEquals(1L, shiftSkillCount(shiftId, optionalSkillId, 2));

        given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .body(Map.of("id", 0, "location_id", ids[1],
                        "start", "2037-04-01T22:00:00", "end", "2037-04-02T06:00:00",
                        "requiredSkills", List.of(), "optionalSkills", List.of()))
                .when().post("/demo-data/addshift").then().statusCode(400);

        given().queryParam("structureId", ids[0]).when()
                .delete("/demo-data/delete_shift/{id}", shiftId).then().statusCode(204);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> ShiftEntity.count("id", shiftId)));
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> ShiftSkillEntity.count("shiftId", shiftId)));
        deleteSkill(requiredSkillId, ids[0]);
        deleteSkill(optionalSkillId, ids[0]);
    }

    @Test
    void skillCrudIsPortable() {
        int structureId = createStructure("portable-skill-owner");
        String originalName = unique("Portable skill");
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(List.of(Map.of("id", 0, "name", originalName, "order", 710, "used", true, "active", true)))
                .when().post("/demo-data/save_skills").then().statusCode(200);
        int skillId = skillIdByName(originalName);
        assertTrue(skillId > 0);

        String updatedName = unique("Portable skill updated");
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(List.of(Map.of("id", skillId, "name", updatedName, "order", 711,
                        "used", true, "active", false)))
                .when().post("/demo-data/save_skills").then().statusCode(200);
        given().queryParam("structureId", structureId).when().get("/demo-data/get_skills").then().statusCode(200)
                .body("find { it.id == " + skillId + " }.name", equalTo(updatedName))
                .body("find { it.id == " + skillId + " }.active", equalTo(false));

        given().queryParam("structureId", structureId)
                .when().delete("/demo-data/skills/{id}", skillId).then().statusCode(204);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> SkillEntity.count("id", skillId)));
    }

    @Test
    void specialistCrudIsPortableAndStructureScoped() {
        int ownerId = createStructure("portable-specialist-owner");
        int foreignId = createStructure("portable-specialist-foreign");
        String code = unique("PSP");
        given().contentType(ContentType.JSON).queryParam("structureId", ownerId)
                .body(Map.of("id", 0, "code", code, "firstName", "mARIO", "lastName", "rOSSI",
                        "email", "specialist@example.test", "active", true))
                .when().post("/specialists").then().statusCode(201);
        int specialistId = specialistIdByCode(code);

        given().queryParam("structureId", ownerId).when().get("/specialists/{id}", specialistId).then()
                .statusCode(200).body("code", equalTo(code));
        given().queryParam("structureId", foreignId).when()
                .get("/specialists/{id}", specialistId).then().statusCode(404);

        given().contentType(ContentType.JSON).queryParam("structureId", ownerId)
                .body(Map.of("id", specialistId, "code", code, "firstName", "Maria", "lastName", "Verdi",
                        "email", "updated-specialist@example.test", "active", false))
                .when().put("/specialists/{id}", specialistId).then().statusCode(200);
        given().queryParam("structureId", ownerId).when().get("/specialists/{id}", specialistId).then()
                .statusCode(200).body("firstName", equalTo("Maria")).body("active", equalTo(false));

        given().queryParam("structureId", ownerId).when()
                .delete("/specialists/{id}", specialistId).then().statusCode(204);
        given().queryParam("structureId", ownerId).when()
                .get("/specialists/{id}", specialistId).then().statusCode(404);
    }

    @Test
    void specialistAffinitiesUseTheSharedOrmPath() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = persistStructure("portable-affinity-owner");
            EmployeeEntity employee = new EmployeeEntity();
            employee.code = unique("PAFF"); employee.firstName = "Affinity"; employee.lastName = "Owner";
            employee.structureId = structure.id; employee.active = true; employee.persistAndFlush();
            SpecialistEntity first = new SpecialistEntity();
            first.code = unique("PAFF-S1"); first.firstName = "First"; first.lastName = "Specialist";
            first.structureId = structure.id; first.active = true; first.persistAndFlush();
            SpecialistEntity second = new SpecialistEntity();
            second.code = unique("PAFF-S2"); second.firstName = "Second"; second.lastName = "Specialist";
            second.structureId = structure.id; second.active = true; second.persistAndFlush();
            return new int[] { structure.id, employee.id, first.id, second.id };
        });

        given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .body(List.of(
                        Map.of("operatorId", ids[1], "specialistId", ids[2], "type", 2),
                        Map.of("operatorId", ids[1], "specialistId", ids[3], "type", 3)))
                .when().put("/affinities/operator/{id}", ids[1]).then().statusCode(200);
        given().queryParam("structureId", ids[0]).when()
                .get("/affinities/operator/{id}", ids[1]).then().statusCode(200)
                .body("size()", equalTo(2))
                .body("[0].specialistId", equalTo(ids[2]))
                .body("[1].specialistId", equalTo(ids[3]));
        given().queryParam("structureId", ids[0]).when().get("/affinities").then().statusCode(200)
                .body("findAll { it.operatorId == " + ids[1] + " }.size()", equalTo(2));

        given().contentType(ContentType.JSON).queryParam("structureId", ids[0]).body(List.of())
                .when().put("/affinities/operator/{id}", ids[1]).then().statusCode(200);
        given().queryParam("structureId", ids[0]).when()
                .get("/affinities/operator/{id}", ids[1]).then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void locationCrudPersistsAndReplacesSkillRelations() {
        int structureId = createStructure("portable-location-owner");
        int requiredSkillId = createSkill("Portable required skill", structureId);
        int optionalSkillId = createSkill("Portable optional skill", structureId);
        String code = unique("PLOC");

        int locationId = given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("id", 0, "code", code, "order", 12, "name", "Portable location",
                        "active", true,
                        "requiredSkill", List.of(Map.of("id", requiredSkillId)),
                        "optionalSkill", List.of()))
                .when().post("/demo-data/addlocation").then().statusCode(201)
                .extract().jsonPath().getInt("id");
        assertEquals(1L, locationSkillCount(locationId, requiredSkillId, LocationSkillEntity.TYPE_REQUIRED));

        given().queryParam("structureId", structureId).when()
                .get("/demo-data/getlocation/{id}", locationId).then().statusCode(200)
                .body("code", equalTo(code)).body("name", equalTo("Portable location"));
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("id", locationId, "code", code, "order", 13, "name", "Portable location updated",
                        "active", false,
                        "requiredSkill", List.of(),
                        "optionalSkill", List.of(Map.of("id", optionalSkillId))))
                .when().put("/demo-data/updatelocation/{id}", locationId).then().statusCode(200);
        assertEquals(0L, locationSkillCount(locationId, requiredSkillId, LocationSkillEntity.TYPE_REQUIRED));
        assertEquals(1L, locationSkillCount(locationId, optionalSkillId, LocationSkillEntity.TYPE_OPTIONAL));

        given().queryParam("structureId", structureId).when()
                .delete("/demo-data/deletelocation/{id}", locationId).then().statusCode(204);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> LocationEntity.count("id", locationId)));
        assertEquals(0L, QuarkusTransaction.requiringNew()
                .call(() -> LocationSkillEntity.count("locationId", locationId)));
        deleteSkill(requiredSkillId, structureId);
        deleteSkill(optionalSkillId, structureId);
    }

    @Test
    void employeeCrudPersistsAndCleansSkillRelations() {
        int structureId = createStructure("portable-employee-owner");
        int skillId = createSkill("Portable employee skill", structureId);
        String code = unique("PEMP");
        Map<String, Object> create = Map.of(
                "id", 0, "code", code, "firstName", "aNNA", "lastName", "bIANCHI",
                "email", "employee@example.test", "active", true,
                "skills", List.of(Map.of("id", skillId)),
                "desiredDates", List.of(), "undesiredDates", List.of(), "unavailableDates", List.of());
        given().contentType(ContentType.JSON).queryParam("structureId", structureId).body(create)
                .when().post("/demo-data/addemployee").then().statusCode(201);
        int employeeId = employeeIdByCode(code);
        assertEquals(1L, employeeSkillCount(employeeId, skillId));

        given().queryParam("structureId", structureId).when()
                .get("/demo-data/getemployee/{id}", employeeId).then().statusCode(200)
                .body("firstName", equalTo("Anna")).body("lastName", equalTo("Bianchi"));
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("id", employeeId, "code", code, "firstName", "Anna", "lastName", "Verdi",
                        "email", "updated-employee@example.test", "active", true, "skills", List.of()))
                .when().put("/demo-data/updateemployee/{id}", employeeId).then().statusCode(200);
        assertEquals(0L, employeeSkillCount(employeeId, skillId));
        given().queryParam("structureId", structureId).when()
                .get("/demo-data/getemployee/{id}", employeeId).then().statusCode(200)
                .body("lastName", equalTo("Verdi"));

        given().queryParam("structureId", structureId).when()
                .delete("/demo-data/employees/{id}", employeeId).then().statusCode(204);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> EmployeeEntity.count("id", employeeId)));
        assertEquals(0L, QuarkusTransaction.requiringNew()
                .call(() -> EmployeeSkillEntity.count("employeeId", employeeId)));
        deleteSkill(skillId, structureId);
    }

    @Test
    void settingsCrudAndStructureCleanupArePortable() {
        int structureId = createStructure("portable-settings");

        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("shift_window_mode", "week", "auto_populate_from_template", true))
                .when().put("/general-settings").then().statusCode(200);
        given().queryParam("structureId", structureId).when().get("/general-settings").then()
                .statusCode(200)
                .body("shift_window_mode", equalTo("week"))
                .body("auto_populate_from_template", equalTo(true));

        given().when().delete("/structures/{id}", structureId).then().statusCode(200);
        assertEquals(0L, QuarkusTransaction.requiringNew()
                .call(() -> GeneralSettingsEntity.count("structureId", structureId)));

        given().contentType(ContentType.JSON).queryParam("structureId", Integer.MAX_VALUE)
                .body(Map.of("shift_window_mode", "month", "auto_populate_from_template", false))
                .when().put("/general-settings").then().statusCode(404);
    }

    @Test
    void crossStructureAssignmentIsRejectedWithoutChangingTheShift() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity owner = persistStructure("portable-owner");
            StructureEntity foreign = persistStructure("portable-foreign");
            LocationEntity location = new LocationEntity();
            location.name = "Portable location"; location.structureId = owner.id; location.active = true;
            location.persistAndFlush();
            EmployeeEntity employee = new EmployeeEntity();
            employee.code = unique("PORTABLE-EMP"); employee.firstName = "Portable"; employee.lastName = "Foreign";
            employee.structureId = foreign.id; employee.active = true; employee.persistAndFlush();
            ShiftEntity shift = new ShiftEntity();
            shift.locationId = location.id; shift.startTime = "2035-01-03 08:00:00";
            shift.endTime = "2035-01-03 16:00:00"; shift.persistAndFlush();
            return new int[] { owner.id, employee.id, shift.id };
        });

        given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .queryParam("start", "2035-01-01 00:00:00").queryParam("end", "2035-02-01 00:00:00")
                .body(List.of(Map.of("shift_id", ids[2], "employee_id", ids[1])))
                .when().post("/demo-data/save-assignments").then().statusCode(400);

        Integer employeeId = QuarkusTransaction.requiringNew()
                .call(() -> ((ShiftEntity) ShiftEntity.findById(ids[2])).employeeId);
        assertNull(employeeId);
        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> ShiftEntity.count("id", ids[2])));
    }

    /**
     * @brief A shift modified after solving causes the ENTIRE save to be rejected.
     *
     * @details The solver reads shifts at startup and the user saves minutes later: if someone
     *          moves a shift in the meantime, the operator used to be assigned to a shift other
     *          than the one evaluated, without any error. The client sends back the revision it
     *          saw; this test sends a stale one and verifies both the 409 and that no row changed —
     *          rejection must be total, not partial.
     */
    @Test
    void assignmentsAreRejectedWhenAShiftChangedAfterTheSolve() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity owner = persistStructure("portable-stale");
            LocationEntity location = new LocationEntity();
            location.name = "Stale location"; location.structureId = owner.id; location.active = true;
            location.persistAndFlush();
            EmployeeEntity employee = new EmployeeEntity();
            employee.code = unique("PORTABLE-STALE"); employee.firstName = "Portable"; employee.lastName = "Stale";
            employee.structureId = owner.id; employee.active = true; employee.persistAndFlush();
            ShiftEntity fresh = new ShiftEntity();
            fresh.locationId = location.id; fresh.startTime = "2035-03-05 08:00:00";
            fresh.endTime = "2035-03-05 16:00:00"; fresh.persistAndFlush();
            ShiftEntity stale = new ShiftEntity();
            stale.locationId = location.id; stale.startTime = "2035-03-06 08:00:00";
            stale.endTime = "2035-03-06 16:00:00"; stale.persistAndFlush();
            return new int[] { owner.id, employee.id, fresh.id, stale.id, fresh.version };
        });

        // Correct revision for the first shift, stale for the second: this is the real case of
        // a shift modified by another user while the solve modal was open.
        given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .queryParam("start", "2035-03-01 00:00:00").queryParam("end", "2035-04-01 00:00:00")
                .body(List.of(
                        Map.of("shift_id", ids[2], "employee_id", ids[1], "version", ids[4]),
                        Map.of("shift_id", ids[3], "employee_id", ids[1], "version", ids[4] + 99)))
                .when().post("/demo-data/save-assignments").then()
                .statusCode(409)
                .body("error", org.hamcrest.Matchers.equalTo("SHIFTS_CHANGED"));

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(((ShiftEntity) ShiftEntity.findById(ids[2])).employeeId,
                    "Rifiuto parziale: il turno con revisione valida e' stato scritto comunque");
            assertNull(((ShiftEntity) ShiftEntity.findById(ids[3])).employeeId);
        });

        // With the correct revision, the same save succeeds: the check does not block the normal case.
        given().contentType(ContentType.JSON).queryParam("structureId", ids[0])
                .queryParam("start", "2035-03-01 00:00:00").queryParam("end", "2035-04-01 00:00:00")
                .body(List.of(Map.of("shift_id", ids[2], "employee_id", ids[1], "version", ids[4])))
                .when().post("/demo-data/save-assignments").then().statusCode(200);

        assertEquals(ids[1], QuarkusTransaction.requiringNew()
                .call(() -> ((ShiftEntity) ShiftEntity.findById(ids[2])).employeeId));
    }

    @Test
    void locationTranslationsRespectStructureOwnership() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity owner = persistStructure("portable-i18n-owner");
            StructureEntity foreign = persistStructure("portable-i18n-foreign");
            LocationEntity location = new LocationEntity();
            location.name = "Portable translated location"; location.structureId = owner.id; location.active = true;
            location.persistAndFlush();
            LanguageEntity language = LanguageEntity.findAll().firstResult();
            LocalizzazioneEntity translation = new LocalizzazioneEntity();
            translation.entityType = "locations"; translation.entityId = location.id;
            translation.fieldName = "name"; translation.languageId = language.id;
            translation.value = "original"; translation.persistAndFlush();
            return new int[] { owner.id, foreign.id, location.id, language.id };
        });
        List<Map<String, Object>> replacement = List.of(Map.of(
                "fieldName", "name", "languageId", ids[3], "value", "replaced"));

        given().queryParam("structureId", ids[1]).when()
                .get("/localizzazioni/locations/{id}", ids[2]).then().statusCode(404);
        given().contentType(ContentType.JSON).queryParam("structureId", ids[1]).body(replacement).when()
                .put("/localizzazioni/locations/{id}", ids[2]).then().statusCode(404);
        assertEquals("original", translationValue(ids[2], ids[3]));

        given().contentType(ContentType.JSON).queryParam("structureId", ids[0]).body(replacement).when()
                .put("/localizzazioni/locations/{id}", ids[2]).then().statusCode(200);
        assertEquals("replaced", translationValue(ids[2], ids[3]));
    }

    @Test
    void concurrentFirstSettingsWritesRemainAtomic() throws Exception {
        int structureId = createStructure("portable-concurrent-settings");
        Map<String, Object> payload = Map.of(
                "shift_window_mode", "week", "auto_populate_from_template", true);
        int requestCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                statuses.add(executor.submit(() -> {
                    start.await();
                    return given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                            .body(payload).when().put("/general-settings").statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> status : statuses) assertEquals(200, status.get());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, QuarkusTransaction.requiringNew()
                .call(() -> GeneralSettingsEntity.count("structureId", structureId)));
        given().queryParam("structureId", structureId).when().get("/general-settings").then()
                .statusCode(200).body("shift_window_mode", equalTo("week"));
    }

    private static int createStructure(String prefix) {
        return QuarkusTransaction.requiringNew().call(() -> persistStructure(prefix).id);
    }

    /** @details Skills belong to a structure: without structureId the backend rejects the request. */
    private static int createSkill(String prefix, int structureId) {
        String name = unique(prefix);
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(List.of(Map.of("id", 0, "name", name, "order", 700, "used", true, "active", true)))
                .when().post("/demo-data/save_skills").then().statusCode(200);
        return skillIdByName(name);
    }

    private static void deleteSkill(int skillId, int structureId) {
        given().queryParam("structureId", structureId)
                .when().delete("/demo-data/skills/{id}", skillId).then().statusCode(204);
    }

    private static int skillIdByName(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SkillEntity entity = SkillEntity.find("name", name).firstResult();
            return entity != null ? entity.id : 0;
        });
    }

    private static int specialistIdByCode(String code) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SpecialistEntity entity = SpecialistEntity.find("code", code).firstResult();
            return entity != null ? entity.id : 0;
        });
    }

    private static int employeeIdByCode(String code) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmployeeEntity entity = EmployeeEntity.find("code", code).firstResult();
            return entity != null ? entity.id : 0;
        });
    }

    private static long locationSkillCount(int locationId, int skillId, int type) {
        return QuarkusTransaction.requiringNew().call(() -> LocationSkillEntity.count(
                "locationId = ?1 and skillId = ?2 and skillTypeId = ?3", locationId, skillId, type));
    }

    private static long employeeSkillCount(int employeeId, int skillId) {
        return QuarkusTransaction.requiringNew().call(() -> EmployeeSkillEntity.count(
                "employeeId = ?1 and skillId = ?2", employeeId, skillId));
    }

    private static long shiftSkillCount(int shiftId, int skillId, int type) {
        return QuarkusTransaction.requiringNew().call(() -> ShiftSkillEntity.count(
                "shiftId = ?1 and skillId = ?2 and skillTypeId = ?3", shiftId, skillId, type));
    }

    private static StructureEntity persistStructure(String prefix) {
        StructureEntity structure = new StructureEntity();
        structure.name = unique(prefix);
        structure.persistAndFlush();
        assertTrue(structure.id > 0);
        return structure;
    }

    private static String translationValue(int locationId, int languageId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            LocalizzazioneEntity translation = LocalizzazioneEntity.find(
                    "entityType = ?1 and entityId = ?2 and fieldName = ?3 and languageId = ?4",
                    "locations", locationId, "name", languageId).firstResult();
            return translation != null ? translation.value : null;
        });
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
