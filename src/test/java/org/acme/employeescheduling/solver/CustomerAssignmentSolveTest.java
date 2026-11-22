package org.acme.employeescheduling.solver;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.SolverSettings;
import org.acme.employeescheduling.rest.AssignmentMaterializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @brief End-to-end check that a customer assignment becomes a valid, role-correct team.
 *
 * @details The constraint tests verify one rule at a time. This one answers the question the
 *          feature exists for: given the staffing requirement of a customer assignment, does the
 *          solver actually return a team in which every filled position carries the role the
 *          assignment asked for, and in which an understaffed requirement stays a visibly
 *          <b>unfilled position</b> rather than being covered by the wrong person.
 *
 *          <p>The positions come from {@link AssignmentMaterializer}, the same code the REST layer
 *          uses, so a change in how requirements expand into positions breaks this test too.
 */
class CustomerAssignmentSolveTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 7, 9, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 7, 9, 14, 0);
    private static final int LOCATION = 3;

    private static final List<AssignmentMaterializer.Requirement> REQUIREMENTS = List.of(
            new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_PHYSICIAN, null, 1),
            new AssignmentMaterializer.Requirement(AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT, null, 2));

    private static Employee employee(int id, String role) {
        Employee employee = new Employee(id, "EMP" + id, "Test", "Operator" + id,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        employee.setRole(role);
        employee.setEligibleLocationIds(Set.of(LOCATION));
        return employee;
    }

    private static EmployeeSchedule problem(List<Employee> employees) {
        List<Shift> positions = new ArrayList<>();
        int id = 1;
        for (AssignmentMaterializer.Position position : AssignmentMaterializer.materialize(REQUIREMENTS)) {
            Shift shift = new Shift(id++, START, END, LOCATION, "MVZ Nord",
                    new ArrayList<>(), new ArrayList<>(), null);
            shift.setAssignmentId(42);
            shift.setRequiredRole(position.role());
            positions.add(shift);
        }
        EmployeeSchedule schedule = new EmployeeSchedule(employees, positions);
        schedule.setLocations(new ArrayList<>());
        // Unfilled positions are a soft penalty, never a hard one: an understaffed requirement
        // must stay visible instead of making the whole plan infeasible.
        SolverSettings settings = new SolverSettings();
        settings.setAllowUnassigned(true);
        schedule.setSolverSettings(settings);
        return schedule;
    }

    private static EmployeeSchedule solve(EmployeeSchedule problem) {
        SolverConfig config = new SolverConfig()
                .withSolutionClass(EmployeeSchedule.class)
                .withEntityClasses(Shift.class)
                .withConstraintProviderClass(EmployeeSchedulingConstraintProvider.class)
                .withTerminationSpentLimit(Duration.ofSeconds(3));
        return SolverFactory.<EmployeeSchedule> create(config).buildSolver().solve(problem);
    }

    @Test
    void fillsEveryPositionWithTheRequiredRoleWhenStaffAreAvailable() {
        EmployeeSchedule solution = solve(problem(List.of(
                employee(1, AssignmentMaterializer.ROLE_PHYSICIAN),
                employee(2, AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT),
                employee(3, AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT))));

        List<Shift> positions = solution.getShifts();
        assertEquals(3, positions.size());
        assertTrue(solution.getScore().isFeasible(), "expected a feasible score, got " + solution.getScore());
        for (Shift position : positions) {
            assertNotNull(position.getEmployee(), "position " + position.getRequiredRole() + " stayed unfilled");
            assertEquals(position.getRequiredRole(), position.getEmployee().getRole());
            assertEquals(Integer.valueOf(42), position.getAssignmentId());
        }
    }

    @Test
    void keepsTheShortPositionUnfilledInsteadOfUsingTheWrongRole() {
        EmployeeSchedule solution = solve(problem(List.of(
                employee(1, AssignmentMaterializer.ROLE_PHYSICIAN),
                employee(2, AssignmentMaterializer.ROLE_MEDICAL_ASSISTANT))));

        List<Shift> positions = solution.getShifts();
        assertEquals(3, positions.size());
        assertTrue(solution.getScore().isFeasible(), "expected a feasible score, got " + solution.getScore());

        long unfilled = positions.stream().filter(position -> position.getEmployee() == null).count();
        assertEquals(1, unfilled, "the second MFA position must stay explicitly unfilled");
        for (Shift position : positions) {
            if (position.getEmployee() != null) {
                assertEquals(position.getRequiredRole(), position.getEmployee().getRole());
            }
        }
        // The physician position must still be covered: the open slot is the MFA one, not the
        // physician one, so understaffing never silently degrades the qualification of the team.
        assertNotNull(positions.stream()
                .filter(position -> AssignmentMaterializer.ROLE_PHYSICIAN.equals(position.getRequiredRole()))
                .findFirst().orElseThrow().getEmployee());
    }
}
