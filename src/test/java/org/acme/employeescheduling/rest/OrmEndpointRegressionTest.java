package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import org.acme.employeescheduling.dto.SpecialistAffinity;
import org.acme.employeescheduling.persistence.AffinityEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateEntity;
import org.acme.employeescheduling.persistence.SpecialistEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.acme.employeescheduling.persistence.GeneralSettingsEntity;
import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@TestProfile(OrmRegressionTestProfile.class)
class OrmEndpointRegressionTest {

    @Inject DemoDataRepository repository;
    @Inject AffinityResource affinityResource;
    @Inject EntityManagerFactory entityManagerFactory;
    @ConfigProperty(name = "demo.db.name") String databasePath;

    @Test
    void legacyDatabaseBootstrapsBeforeFirstOrmRequests() {
        given().queryParam("structureId", 1).when().get("/solver-settings").then().statusCode(200);
        given().queryParam("structureId", 1).when().get("/general-settings").then().statusCode(200);
        given().queryParam("structureId", 1).when().get("/demo-data/pdf-template").then().statusCode(200);
        given().queryParam("structureId", 1).when().get("/demo-data/email-template").then().statusCode(200);
        given().when().get("/email/settings").then().statusCode(200);
        given().when().get("/languages").then().statusCode(200);
    }

    @Test
    void legacyShiftSkillsForeignKeyIsRebuiltAndInvalidRowsAreDiscarded() throws Exception {
        boolean pointsToShifts = false;
        boolean pointsToBackup = false;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("PRAGMA foreign_key_list('shift_skills')")) {
                while (result.next()) {
                    if (!"shift_id".equalsIgnoreCase(result.getString("from"))) continue;
                    pointsToShifts |= "shifts".equalsIgnoreCase(result.getString("table"));
                    pointsToBackup |= "shifts_backup".equalsIgnoreCase(result.getString("table"));
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(pointsToShifts);
            org.junit.jupiter.api.Assertions.assertFalse(pointsToBackup);
            try (ResultSet result = statement.executeQuery(
                    "SELECT id,shift_id,skill_id,skill_type_id FROM shift_skills ORDER BY id")) {
                org.junit.jupiter.api.Assertions.assertTrue(result.next());
                org.junit.jupiter.api.Assertions.assertEquals(301, result.getInt("id"));
                org.junit.jupiter.api.Assertions.assertEquals(201, result.getInt("shift_id"));
                org.junit.jupiter.api.Assertions.assertEquals(101, result.getInt("skill_id"));
                org.junit.jupiter.api.Assertions.assertEquals(1, result.getInt("skill_type_id"));
                org.junit.jupiter.api.Assertions.assertFalse(result.next());
            }
            statement.execute("PRAGMA foreign_keys = ON");
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO shift_skills(shift_id,skill_id,skill_type_id) VALUES(999999,101,1)"));
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO shift_skills(shift_id,skill_id,skill_type_id) VALUES(201,101,1)"));
        }
    }

    @Test
    void scheduleUsageUsesOneScalarQueryWithoutLoadingEntities() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = new StructureEntity();
            structure.name = "Usage projection"; structure.persistAndFlush();
            LocationEntity location = new LocationEntity();
            location.name = "Usage location"; location.structureId = structure.id; location.active = true;
            location.persistAndFlush();
            EmployeeEntity first = new EmployeeEntity();
            first.code = "USAGE-A"; first.firstName = "Usage"; first.lastName = "A";
            first.structureId = structure.id; first.active = true; first.persistAndFlush();
            EmployeeEntity second = new EmployeeEntity();
            second.code = "USAGE-B"; second.firstName = "Usage"; second.lastName = "B";
            second.structureId = structure.id; second.active = true; second.persistAndFlush();
            for (int index = 0; index < 4; index++) {
                ShiftEntity shift = new ShiftEntity(); shift.locationId = location.id;
                shift.employeeId = index < 2 ? first.id : second.id;
                shift.startTime = "2031-01-0" + (index + 1) + " 08:00:00";
                shift.endTime = "2031-01-0" + (index + 1) + " 16:00:00";
                shift.persist();
            }
            return new int[] { structure.id, location.id, first.id, second.id };
        });

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        org.junit.jupiter.api.Assertions.assertTrue(statistics.isStatisticsEnabled());
        statistics.clear();
        Map<String, List<Integer>> usage = QuarkusTransaction.requiringNew()
                .call(() -> repository.getScheduleUsage(ids[0]));

        org.junit.jupiter.api.Assertions.assertEquals(Set.of(ids[1]), new HashSet<>(usage.get("locationIds")));
        org.junit.jupiter.api.Assertions.assertEquals(Set.of(ids[2], ids[3]), new HashSet<>(usage.get("employeeIds")));
        org.junit.jupiter.api.Assertions.assertEquals(1, statistics.getQueryExecutionCount());
        org.junit.jupiter.api.Assertions.assertEquals(0, statistics.getEntityLoadCount());
    }

    @Test
    void operatorAffinitiesUseOneProjectionRegardlessOfRowCount() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity ownStructure = new StructureEntity();
            ownStructure.name = "Affinity projection owner"; ownStructure.persistAndFlush();
            StructureEntity foreignStructure = new StructureEntity();
            foreignStructure.name = "Affinity projection foreign"; foreignStructure.persistAndFlush();
            EmployeeEntity employee = new EmployeeEntity();
            employee.code = "AFF-PROJECTION"; employee.firstName = "Affinity"; employee.lastName = "Projection";
            employee.structureId = ownStructure.id; employee.active = true; employee.persistAndFlush();
            for (int index = 0; index < 12; index++) {
                SpecialistEntity specialist = new SpecialistEntity();
                specialist.code = "AFF-S-" + index; specialist.firstName = "Specialist";
                specialist.lastName = Integer.toString(index); specialist.structureId = ownStructure.id;
                specialist.active = true; specialist.persistAndFlush();
                AffinityEntity affinity = new AffinityEntity();
                affinity.operatorId = employee.id; affinity.specialistId = specialist.id;
                affinity.type = SpecialistAffinity.TYPE_AVOID; affinity.persist();
            }
            SpecialistEntity foreignSpecialist = new SpecialistEntity();
            foreignSpecialist.code = "AFF-FOREIGN"; foreignSpecialist.firstName = "Foreign";
            foreignSpecialist.lastName = "Specialist"; foreignSpecialist.structureId = foreignStructure.id;
            foreignSpecialist.active = true; foreignSpecialist.persistAndFlush();
            AffinityEntity poisoned = new AffinityEntity();
            poisoned.operatorId = employee.id; poisoned.specialistId = foreignSpecialist.id;
            poisoned.type = SpecialistAffinity.TYPE_INCOMPATIBLE; poisoned.persist();
            return new int[] { ownStructure.id, employee.id };
        });

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        jakarta.ws.rs.core.Response response = QuarkusTransaction.requiringNew()
                .call(() -> affinityResource.getByOperator(ids[1], ids[0]));
        @SuppressWarnings("unchecked")
        List<SpecialistAffinity> affinities = (List<SpecialistAffinity>) response.getEntity();

        org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(12, affinities.size());
        org.junit.jupiter.api.Assertions.assertEquals(1, statistics.getQueryExecutionCount());
        org.junit.jupiter.api.Assertions.assertEquals(0, statistics.getEntityLoadCount());
    }

    @Test
    void requiredSchemaValidationRejectsPartialOrmSchema() throws Exception {
        Path database = Path.of("target", "orm-incomplete-schema-test.db").toAbsolutePath();
        Files.deleteIfExists(database);
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE structures(id INTEGER PRIMARY KEY,name TEXT,address TEXT,phone TEXT)");
            }
            DemoDataRepository isolated = new DemoDataRepository();
            Field dbName = DemoDataRepository.class.getDeclaredField("dbName");
            dbName.setAccessible(true);
            dbName.set(isolated, database.toString());
            Method validate = DemoDataRepository.class.getDeclaredMethod("validateRequiredSchema");
            validate.setAccessible(true);
            InvocationTargetException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    InvocationTargetException.class, () -> validate.invoke(isolated));
            org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failure.getCause());
            org.junit.jupiter.api.Assertions.assertTrue(failure.getCause().getMessage().contains("Cannot validate"));
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void concurrentFirstConfigurationWritesAreAtomic() throws Exception {
        int structureId = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = new StructureEntity();
            structure.name = "Concurrent settings";
            structure.persistAndFlush();
            return structure.id;
        });
        Map<String, Object> payload = Map.of(
                "shift_window_mode", "week",
                "auto_populate_from_template", true);
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                statuses.add(executor.submit(() -> {
                    start.await();
                    return given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                            .body(payload).when().put("/general-settings").statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> status : statuses) org.junit.jupiter.api.Assertions.assertEquals(200, status.get());
        } finally {
            executor.shutdownNow();
        }
        given().queryParam("structureId", structureId).when().get("/general-settings").then()
                .statusCode(200).body("shift_window_mode", equalTo("week"))
                .body("auto_populate_from_template", equalTo(true));
    }

    @Test
    void settingsCannotCreateRowsForUnknownStructures() {
        given().contentType(ContentType.JSON).queryParam("structureId", 7001)
                .body(Map.of("shift_window_mode", "week", "auto_populate_from_template", true))
                .when().put("/general-settings").then().statusCode(404);
        given().queryParam("structureId", 7001).when().get("/general-settings").then().statusCode(404);
        given().contentType(ContentType.JSON).queryParam("structureId", 7001)
                .queryParam("weekStart", "2028-01-03T00:00:00").body(Map.of("description", "ghost"))
                .when().post("/demo-data/saved-template").then().statusCode(404);
    }

    @Test
    void configuredEmptyStructureCanBeDeletedWithoutOrphans() {
        int structureId = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = new StructureEntity();
            structure.name = "Disposable structure";
            structure.persistAndFlush();
            return structure.id;
        });
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("shift_window_mode", "month", "auto_populate_from_template", false))
                .when().put("/general-settings").then().statusCode(200);
        given().when().delete("/structures/{id}", structureId).then().statusCode(200);
        long settings = QuarkusTransaction.requiringNew().call(() -> GeneralSettingsEntity.count("structureId", structureId));
        org.junit.jupiter.api.Assertions.assertEquals(0, settings);
    }

    @Test
    void invalidLocalizationReferencesDoNotReplaceExistingRows() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            LabelEntity label = new LabelEntity();
            label.labelKey = "regression.localization"; label.description = "Regression";
            label.persistAndFlush();
            LanguageEntity language = LanguageEntity.findAll().firstResult();
            LocalizzazioneEntity localization = new LocalizzazioneEntity();
            localization.entityType = "labels"; localization.entityId = label.id;
            localization.fieldName = "value"; localization.languageId = language.id;
            localization.value = "original"; localization.persistAndFlush();
            return new int[] { label.id, localization.id };
        });
        given().contentType(ContentType.JSON)
                .body(List.of(Map.of("fieldName", "value", "languageId", 999999, "value", "bad")))
                .when().put("/localizzazioni/labels/{id}", ids[0]).then().statusCode(400);
        String value = QuarkusTransaction.requiringNew().call(() ->
                ((LocalizzazioneEntity) LocalizzazioneEntity.findById(ids[1])).value);
        org.junit.jupiter.api.Assertions.assertEquals("original", value);
    }

    @Test
    void locationLocalizationsRequireOwnershipAndDynamicNamesAreStructureScoped() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity ownStructure = new StructureEntity();
            ownStructure.name = "Localization owner"; ownStructure.persistAndFlush();
            StructureEntity foreignStructure = new StructureEntity();
            foreignStructure.name = "Localization foreign"; foreignStructure.persistAndFlush();
            LocationEntity ownLocation = new LocationEntity();
            ownLocation.name = "Localization own location"; ownLocation.structureId = ownStructure.id;
            ownLocation.active = true; ownLocation.persistAndFlush();
            LocationEntity foreignLocation = new LocationEntity();
            foreignLocation.name = "Localization foreign location"; foreignLocation.structureId = foreignStructure.id;
            foreignLocation.active = true; foreignLocation.persistAndFlush();
            LanguageEntity language = LanguageEntity.findAll().firstResult();
            LocalizzazioneEntity localization = new LocalizzazioneEntity();
            localization.entityType = "locations"; localization.entityId = ownLocation.id;
            localization.fieldName = "name"; localization.languageId = language.id;
            localization.value = "original"; localization.persistAndFlush();
            return new int[] { ownStructure.id, foreignStructure.id, ownLocation.id,
                    foreignLocation.id, language.id, localization.id };
        });
        List<Map<String, Object>> replacement = List.of(Map.of(
                "fieldName", "name", "languageId", ids[4], "value", "replaced"));

        given().when().get("/localizzazioni/locations/{id}", ids[2]).then().statusCode(400);
        given().queryParam("structureId", ids[1]).when()
                .get("/localizzazioni/locations/{id}", ids[2]).then().statusCode(404);
        given().contentType(ContentType.JSON).queryParam("structureId", ids[1]).body(replacement).when()
                .put("/localizzazioni/locations/{id}", ids[2]).then().statusCode(404);
        String unchanged = QuarkusTransaction.requiringNew().call(() ->
                ((LocalizzazioneEntity) LocalizzazioneEntity.findById(ids[5])).value);
        org.junit.jupiter.api.Assertions.assertEquals("original", unchanged);

        given().queryParam("structureId", ids[0]).when()
                .get("/localizzazioni/locations/{id}", ids[2]).then().statusCode(200);
        given().contentType(ContentType.JSON).queryParam("structureId", ids[0]).body(replacement).when()
                .put("/localizzazioni/locations/{id}", ids[2]).then().statusCode(200);

        List<String> keys = given().queryParam("structureId", ids[0]).when()
                .get("/labels/dynamic-names").then().statusCode(200)
                .extract().jsonPath().getList("key", String.class);
        org.junit.jupiter.api.Assertions.assertTrue(keys.contains("location." + ids[2]));
        org.junit.jupiter.api.Assertions.assertFalse(keys.contains("location." + ids[3]));
        given().queryParam("structureId", 999999).when()
                .get("/labels/dynamic-names").then().statusCode(404);
    }

    @Test
    void invalidLanguageActivationDoesNotDisableCurrentLanguage() {
        String activeCode = given().when().get("/languages").then().statusCode(200)
                .extract().jsonPath().getString("find { it.active }.code");
        given().when().put("/languages/999999/activate").then().statusCode(404);
        given().when().get("/languages").then().statusCode(200)
                .body("find { it.active }.code", equalTo(activeCode));
    }

    @Test
    void generateRejectsIncompleteInvalidOrReversedWindows() {
        given().queryParam("structureId", 1).queryParam("start", "not-a-date")
                .when().get("/demo-data/generate").then().statusCode(400);
        given().queryParam("structureId", 1).queryParam("start", "not-a-date")
                .queryParam("end", "2028-01-02T00:00:00")
                .when().get("/demo-data/generate").then().statusCode(400);
        given().queryParam("structureId", 1).queryParam("start", "2028-01-02T00:00:00")
                .queryParam("end", "2028-01-01T00:00:00")
                .when().get("/demo-data/generate").then().statusCode(400);
        given().queryParam("structureId", 1).queryParam("start", "2028-01-01T00:00:00")
                .queryParam("end", "2028-01-02T00:00:00")
                .when().get("/demo-data/generate").then().statusCode(200);
    }

    @Test
    void crossStructureWritesAreRejectedWithoutDeletingTargetShifts() {
        int[] ids = new int[3];
        QuarkusTransaction.requiringNew().run(() -> {
            LocationEntity location = new LocationEntity();
            location.name = "Regression location"; location.structureId = 2; location.active = true;
            location.persistAndFlush();
            EmployeeEntity foreignEmployee = new EmployeeEntity();
            foreignEmployee.code = "REG-FOREIGN"; foreignEmployee.firstName = "Foreign";
            foreignEmployee.lastName = "Employee"; foreignEmployee.structureId = 1; foreignEmployee.active = true;
            foreignEmployee.persistAndFlush();
            ShiftEntity shift = new ShiftEntity(); shift.locationId = location.id;
            shift.startTime = "2028-01-03 08:00:00"; shift.endTime = "2028-01-03 16:00:00";
            shift.persistAndFlush();
            ids[0] = location.id; ids[1] = foreignEmployee.id; ids[2] = shift.id;
        });

        given().contentType(ContentType.JSON).queryParam("structureId", 2)
                .queryParam("start", "2028-01-01 00:00:00").queryParam("end", "2028-02-01 00:00:00")
                .body(List.of(Map.of("shift_id", ids[2], "employee_id", ids[1])))
                .when().post("/demo-data/save-assignments").then().statusCode(400);

        given().contentType(ContentType.JSON).queryParam("structureId", 2)
                .queryParam("start", "2028-01-01 00:00:00").queryParam("end", "2028-02-01 00:00:00")
                .when().post("/demo-data/saved-template/999999/apply").then().statusCode(404);

        Integer employeeId = QuarkusTransaction.requiringNew().call(() ->
                ((ShiftEntity) ShiftEntity.findById(ids[2])).employeeId);
        org.junit.jupiter.api.Assertions.assertNull(employeeId);
        long remaining = QuarkusTransaction.requiringNew().call(() -> ShiftEntity.count("id", ids[2]));
        org.junit.jupiter.api.Assertions.assertEquals(1, remaining);
    }

    @Test
    void orphanCrossStructureAndPoisonedTemplateWritesAreRejected() {
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            LocationEntity ownLocation = new LocationEntity();
            ownLocation.name = "Own location"; ownLocation.structureId = 1; ownLocation.active = true;
            ownLocation.persistAndFlush();
            LocationEntity foreignLocation = new LocationEntity();
            foreignLocation.name = "Foreign location"; foreignLocation.structureId = 2; foreignLocation.active = true;
            foreignLocation.persistAndFlush();
            EmployeeEntity foreignEmployee = new EmployeeEntity();
            foreignEmployee.code = "TENANT-B"; foreignEmployee.firstName = "Tenant";
            foreignEmployee.lastName = "B"; foreignEmployee.structureId = 2; foreignEmployee.active = true;
            foreignEmployee.persistAndFlush();
            EmployeeEntity ownEmployee = new EmployeeEntity();
            ownEmployee.code = "TENANT-A"; ownEmployee.firstName = "Tenant";
            ownEmployee.lastName = "A"; ownEmployee.structureId = 1; ownEmployee.active = true;
            ownEmployee.persistAndFlush();
            SpecialistEntity foreignSpecialist = new SpecialistEntity();
            foreignSpecialist.code = "SPEC-B"; foreignSpecialist.firstName = "Spec";
            foreignSpecialist.lastName = "B"; foreignSpecialist.structureId = 2; foreignSpecialist.active = true;
            foreignSpecialist.persistAndFlush();
            ShiftTemplateEntity template = new ShiftTemplateEntity();
            template.structureId = 1; template.dayOfWeek = 0;
            template.startTime = "08:00:00"; template.endTime = "16:00:00";
            template.locationId = ownLocation.id; template.persistAndFlush();
            ShiftEntity protectedShift = new ShiftEntity();
            protectedShift.locationId = ownLocation.id;
            protectedShift.startTime = "2032-01-05 08:00:00";
            protectedShift.endTime = "2032-01-05 16:00:00";
            protectedShift.persistAndFlush();
            return new int[] { ownLocation.id, foreignLocation.id, foreignEmployee.id, ownEmployee.id,
                    foreignSpecialist.id, template.id, protectedShift.id };
        });

        given().contentType(ContentType.JSON).queryParam("structureId", 1)
                .body(Map.of("location_id", 999999, "start", "2032-01-05T08:00:00",
                        "end", "2032-01-05T16:00:00", "requiredSkill", List.of(), "optionalSkill", List.of()))
                .when().post("/demo-data/addshift").then().statusCode(400);

        given().queryParam("structureId", 1).when()
                .delete("/demo-data/employees/{id}", ids[2]).then().statusCode(404);
        org.junit.jupiter.api.Assertions.assertEquals(1,
                QuarkusTransaction.requiringNew().call(() -> EmployeeEntity.count("id", ids[2])));

        given().contentType(ContentType.JSON).queryParam("structureId", 1)
                .body(List.of(Map.of("operatorId", ids[3], "specialistId", ids[4], "type", 2)))
                .when().put("/affinities/operator/{id}", ids[3]).then().statusCode(400);

        given().contentType(ContentType.JSON).queryParam("structureId", 1)
                .body(Map.of("day_of_week", 0, "start_time", "08:00:00", "end_time", "16:00:00",
                        "location_id", ids[1], "requiredSkills", List.of(), "optionalSkills", List.of()))
                .when().put("/demo-data/shift-template/{id}", ids[5]).then().statusCode(400);

        QuarkusTransaction.requiringNew().run(() ->
                ((ShiftTemplateEntity) ShiftTemplateEntity.findById(ids[5])).locationId = ids[1]);
        given().queryParam("structureId", 1).queryParam("start", "2032-01-05T00:00:00")
                .queryParam("end", "2032-01-12T00:00:00")
                .when().post("/demo-data/apply-template").then().statusCode(409);
        org.junit.jupiter.api.Assertions.assertEquals(1,
                QuarkusTransaction.requiringNew().call(() -> ShiftEntity.count("id", ids[6])));

        given().queryParam("structureId", 1).queryParam("start", "0001-01-01T00:00:00")
                .queryParam("end", "9999-12-31T00:00:00")
                .when().post("/demo-data/apply-template").then().statusCode(400);
    }
}
