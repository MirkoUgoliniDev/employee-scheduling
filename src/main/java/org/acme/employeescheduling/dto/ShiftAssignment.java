package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Shift-to-employee assignment produced by the solver and persisted to the DB.
 *
 * @details Used by POST /demo-data/save-assignments to save the solution accepted by the user
 *          in bulk. A null `employeeId` means an unassigned shift.
 */
public class ShiftAssignment {

    /** @brief Shift ID. */
    @JsonProperty("shift_id")
    private int shiftId;

    /** @brief Assigned employee ID, or null if unassigned. */
    @JsonProperty("employee_id")
    private Integer employeeId;

    /**
     * @brief Shift revision seen by the solver, used to detect intervening changes.
     *
     * @details Intentionally nullable: a client that does not send it gets the previous behavior
     *          (no check) rather than an error. When present, it must match the database revision;
     *          otherwise the shift changed after solving and the save must be rejected.
     */
    @JsonProperty("version")
    private Integer version;

    public ShiftAssignment() {}

    public int getShiftId() { return shiftId; }
    public void setShiftId(int shiftId) { this.shiftId = shiftId; }

    public Integer getEmployeeId() { return employeeId; }
    public void setEmployeeId(Integer employeeId) { this.employeeId = employeeId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
