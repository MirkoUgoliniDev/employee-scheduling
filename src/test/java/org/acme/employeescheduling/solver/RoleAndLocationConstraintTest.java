package org.acme.employeescheduling.solver;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.dto.Employee;
import org.junit.jupiter.api.Test;

/**
 * @brief Verifies the two hard constraints of the customer-centric model: role and location.
 *
 * @details Both protect the same guarantee — a position is either staffed by someone allowed to
 *          fill it, or it stays visibly unfilled. Without them the solver can put a medical
 *          assistant into a physician slot and still report a complete plan.
 *
 *          <p>Penalties are expressed in minutes (the shift is six hours), matching the other
 *          state constraints of this provider.
 */
class RoleAndLocationConstraintTest {

    private static final LocalDateTime SHIFT_START = LocalDateTime.of(2026, 7, 9, 8, 0);
    private static final LocalDateTime SHIFT_END = LocalDateTime.of(2026, 7, 9, 14, 0);
    private static final int LOCATION = 3;

    private final ConstraintVerifier<EmployeeSchedulingConstraintProvider, EmployeeSchedule> constraintVerifier =
            ConstraintVerifier.build(new EmployeeSchedulingConstraintProvider(), EmployeeSchedule.class, Shift.class);

    private static Employee employee(String role, Set<Integer> eligibleLocations) {
        Employee employee = new Employee(7, "EMP007", "Sabrina", "Muster",
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        employee.setRole(role);
        employee.setEligibleLocationIds(eligibleLocations);
        return employee;
    }

    private static Shift shift(String requiredRole, int locationId) {
        Shift shift = new Shift(1, SHIFT_START, SHIFT_END, locationId, "MVZ Nord",
                new ArrayList<>(), new ArrayList<>(), null);
        shift.setRequiredRole(requiredRole);
        return shift;
    }

    @Test
    void penalizesAMedicalAssistantInAPhysicianPosition() {
        Employee assistant = employee("MEDICAL_ASSISTANT", Set.of());
        Shift position = shift("PHYSICIAN", LOCATION);
        position.setEmployee(assistant);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::requiredRole)
                .given(assistant, position)
                .penalizesBy(360);
    }

    @Test
    void acceptsAPhysicianInAPhysicianPosition() {
        Employee physician = employee("PHYSICIAN", Set.of());
        Shift position = shift("PHYSICIAN", LOCATION);
        position.setEmployee(physician);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::requiredRole)
                .given(physician, position)
                .penalizesBy(0);
    }

    @Test
    void ignoresStandaloneShiftsWithoutARole() {
        Employee assistant = employee("MEDICAL_ASSISTANT", Set.of());
        Shift standalone = shift("", LOCATION);
        standalone.setEmployee(assistant);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::requiredRole)
                .given(assistant, standalone)
                .penalizesBy(0);
    }

    @Test
    void penalizesWorkAtAnIneligibleLocation() {
        Employee physician = employee("PHYSICIAN", Set.of(1, 2));
        Shift position = shift("PHYSICIAN", LOCATION);
        position.setEmployee(physician);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::locationEligibility)
                .given(physician, position)
                .penalizesBy(360);
    }

    @Test
    void acceptsWorkAtAnEligibleLocation() {
        Employee physician = employee("PHYSICIAN", Set.of(LOCATION));
        Shift position = shift("PHYSICIAN", LOCATION);
        position.setEmployee(physician);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::locationEligibility)
                .given(physician, position)
                .penalizesBy(0);
    }

    @Test
    void treatsAnEmptyEligibilitySetAsUnrestricted() {
        Employee physician = employee("PHYSICIAN", Set.of());
        Shift position = shift("PHYSICIAN", LOCATION);
        position.setEmployee(physician);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::locationEligibility)
                .given(physician, position)
                .penalizesBy(0);
    }

    @Test
    void doesNotPenalizeAnUnfilledPosition() {
        Shift position = shift("PHYSICIAN", LOCATION);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::locationEligibility)
                .given(position)
                .penalizesBy(0);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::requiredRole)
                .given(position)
                .penalizesBy(0);
    }
}
