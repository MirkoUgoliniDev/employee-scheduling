package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * @brief Unit tests for the translation of a staffing requirement into solver positions.
 *
 * @details The team composition is the core rule of the customer-centric model: "1 physician plus
 *          2 medical assistants" must become exactly three positions with the right roles, because
 *          the solver can only assign one employee per planning entity. A regression here would
 *          either under-staff a visit or silently plan a physician twice.
 */
class AssignmentMaterializerTest {

    @Test
    void expandsEachRequirementIntoOnePositionPerHead() {
        List<AssignmentMaterializer.Position> positions = AssignmentMaterializer.materialize(List.of(
                new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_PHYSICIAN, null, 1),
                new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT, null, 2)));

        assertEquals(3, positions.size());
        assertEquals(1, positions.stream()
                .filter(p -> AssignmentMaterializer.ROLE_PHYSICIAN.equals(p.role())).count());
        assertEquals(2, positions.stream()
                .filter(p -> AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT.equals(p.role())).count());
    }

    @Test
    void keepsTheQualificationOfEachRequirement() {
        List<AssignmentMaterializer.Position> positions = AssignmentMaterializer.materialize(List.of(
                new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_PHYSICIAN, 7, 1),
                new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT, 9, 1)));

        assertEquals(Set.of("PHYSICIAN|7", "MEDICAL_ASSISTANT|9"),
                positions.stream().map(AssignmentMaterializer.Position::churnKey).collect(Collectors.toSet()));
    }

    @Test
    void ignoresRequirementsWithoutRoleOrHeadcount() {
        List<AssignmentMaterializer.Position> positions = AssignmentMaterializer.materialize(List.of(
                new AssignmentMaterializer.Requirement("  ", null, 2),
                new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_PHYSICIAN, null, 0),
                new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT, null, 1)));

        assertEquals(1, positions.size());
        assertEquals(AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT, positions.get(0).role());
    }

    @Test
    void treatsNullRequirementsAsNoPositions() {
        assertTrue(AssignmentMaterializer.materialize(null).isEmpty());
    }

    @Test
    void computesTheEndOfTheVisitFromTheConfigurableDuration() {
        assertEquals(LocalDateTime.of(2026, 7, 9, 8, 0),
                AssignmentMaterializer.startAt("2026-07-09", "08:00"));
        assertEquals(LocalDateTime.of(2026, 7, 9, 9, 15),
                AssignmentMaterializer.endAt("2026-07-09", "08:00", 75));
        assertEquals("2026-07-09 09:15:00",
                AssignmentMaterializer.toDbDateTime(
                        AssignmentMaterializer.endAt("2026-07-09", "08:00", 75)));
    }
}
