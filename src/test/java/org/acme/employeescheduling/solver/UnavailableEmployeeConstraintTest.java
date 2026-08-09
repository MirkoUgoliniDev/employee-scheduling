package org.acme.employeescheduling.solver;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.EmployeeDate;
import org.junit.jupiter.api.Test;

/**
 * @brief Verifies that "Unavailable employee" triggers even when the shift contains an
 *        Employee instance distinct from the one in the value range.
 * @details This is the real scenario: the solver payload is serialized to JSON by
 *          /demo-data/generate and deserialized again by POST /schedules, so each preassigned
 *          shift receives a COPY of the employee. Without equals/hashCode on Employee, the
 *          identity-based join produces no rows and the hard constraint does not trigger.
 */
class UnavailableEmployeeConstraintTest {

    private static final LocalDateTime SHIFT_START = LocalDateTime.of(2026, 7, 9, 14, 0);
    private static final LocalDateTime SHIFT_END = LocalDateTime.of(2026, 7, 9, 20, 0);

    private final ConstraintVerifier<EmployeeSchedulingConstraintProvider, EmployeeSchedule> constraintVerifier =
            ConstraintVerifier.build(new EmployeeSchedulingConstraintProvider(), EmployeeSchedule.class, Shift.class);

    /** @brief Builds an employee who is unavailable exactly during the shift interval. */
    private static Employee unavailableSabrina() {
        EmployeeDate unavailable = new EmployeeDate();
        unavailable.setId(146);
        unavailable.setEmployeeId(7);
        unavailable.setDateTypeId(3);
        unavailable.setDateStart(SHIFT_START);
        unavailable.setDateEnd(SHIFT_END);

        List<EmployeeDate> unavailableDates = new ArrayList<>();
        unavailableDates.add(unavailable);
        return new Employee(7, "SAB", "Sabrina", "Sabrina",
                new ArrayList<>(), new ArrayList<>(), unavailableDates, new ArrayList<>());
    }

    @Test
    void penalizesWhenShiftCarriesADistinctEmployeeInstance() {
        Employee valueRangeInstance = unavailableSabrina();
        Employee deserializedCopy = unavailableSabrina(); // same ID, different object

        Shift shift = new Shift(1, SHIFT_START, SHIFT_END, 3, "CDC",
                new ArrayList<>(), new ArrayList<>(), deserializedCopy);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::unavailableEmployee)
                .given(valueRangeInstance, shift)
                .penalizesBy(360);
    }

    @Test
    void doesNotPenalizeWhenShiftIsOutsideTheUnavailableRange() {
        Employee sabrina = unavailableSabrina();
        Shift shift = new Shift(1, SHIFT_START.minusHours(8), SHIFT_START.minusHours(2), 3, "CDC",
                new ArrayList<>(), new ArrayList<>(), sabrina);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::unavailableEmployee)
                .given(sabrina, shift)
                .penalizesBy(0);
    }
}
