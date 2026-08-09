package org.acme.employeescheduling.domain;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoftbigdecimal.HardSoftBigDecimalScore;
import ai.timefold.solver.core.api.solver.SolverStatus;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.Location;
import org.acme.employeescheduling.dto.SolverSettings;


/**
 * @brief Represents the complete employee scheduling solution for the Timefold Solver.
 *
 * @details This class is the planning solution that aggregates all problem facts (employees,
 *          locations) and planning entities (shifts). It holds the computed score and the
 *          current solver status. The Timefold Solver populates this object with the
 *          optimal or near-optimal assignment of employees to shifts.
 *
 * @author acme
 * @version 1.0
 */
@PlanningSolution
public class EmployeeSchedule {

    /** @brief List of available employees, serving as problem facts and the value range for shift assignments. */
    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Employee> employees;

    /** @brief List of shifts to be scheduled, serving as planning entities. */
    @PlanningEntityCollectionProperty
    private List<Shift> shifts;

    /** @brief List of locations associated with the scheduling problem. */
    @ProblemFactCollectionProperty
    private List<Location> locations;

    @ProblemFactProperty
    private SolverSettings solverSettings = new SolverSettings();

    /** @brief The computed planning score representing hard and soft constraint satisfaction. */
    @PlanningScore
    private HardSoftBigDecimalScore score;

    /** @brief The current status of the solver (e.g., NOT_SOLVING, SOLVING_ACTIVE). */
    private SolverStatus solverStatus;

    /**
     * @brief Default no-argument constructor.
     *
     * @details Required by the Timefold Solver framework for instantiation.
     */
    public EmployeeSchedule() {}

    /**
     * @brief Constructs an EmployeeSchedule with employees and shifts.
     *
     * @param employees The list of available employees.
     * @param shifts    The list of shifts to be scheduled.
     */
    public EmployeeSchedule(List<Employee> employees, List<Shift> shifts) {
        this.employees = employees;
        this.shifts = shifts;
    }

    /**
     * @brief Constructs an EmployeeSchedule with a score and solver status.
     *
     * @details Useful for creating a response object that conveys the solver outcome
     *          without the full problem data.
     *
     * @param score        The planning score.
     * @param solverStatus The current solver status.
     */
    public EmployeeSchedule(HardSoftBigDecimalScore score, SolverStatus solverStatus) {
        this.score = score;
        this.solverStatus = solverStatus;
    }

    /**
     * @brief Gets the list of employees.
     *
     * @return The list of employees.
     */
    public List<Employee> getEmployees() {
        return employees;
    }

    /**
     * @brief Sets the list of employees.
     *
     * @param employees The list of employees to set.
     */
    public void setEmployees(List<Employee> employees) {
        this.employees = employees;
    }

    /**
     * @brief Gets the list of locations.
     *
     * @return The list of locations.
     */
    public List<Location> getLocations() {
        return locations;
    }

    /**
     * @brief Sets the list of locations.
     *
     * @param locations The list of locations to set.
     */
    public void setLocations(List<Location> locations) {
        this.locations = locations;
    }

    public SolverSettings getSolverSettings() { return solverSettings; }
    public void setSolverSettings(SolverSettings solverSettings) {
        this.solverSettings = solverSettings != null ? solverSettings : new SolverSettings();
    }

    /**
     * @brief Gets the list of shifts.
     *
     * @return The list of shifts.
     */
    public List<Shift> getShifts() {
        return shifts;
    }

    /**
     * @brief Sets the list of shifts.
     *
     * @param shifts The list of shifts to set.
     */
    public void setShifts(List<Shift> shifts) {
        this.shifts = shifts;
    }

    /**
     * @brief Gets the planning score.
     *
     * @return The hard-soft big decimal score.
     */
    public HardSoftBigDecimalScore getScore() {
        return score;
    }

    /**
     * @brief Sets the planning score.
     *
     * @param score The hard-soft big decimal score to set.
     */
    public void setScore(HardSoftBigDecimalScore score) {
        this.score = score;
    }

    /**
     * @brief Gets the current solver status.
     *
     * @return The solver status.
     */
    public SolverStatus getSolverStatus() {
        return solverStatus;
    }

    /**
     * @brief Sets the current solver status.
     *
     * @param solverStatus The solver status to set.
     */
    public void setSolverStatus(SolverStatus solverStatus) {
        this.solverStatus = solverStatus;
    }
}
