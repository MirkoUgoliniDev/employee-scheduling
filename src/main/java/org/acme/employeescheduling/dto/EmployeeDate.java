package org.acme.employeescheduling.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Represents a date interval associated with an employee.
 *
 * @details This data transfer object models a date range for an employee, categorized by
 *          a date type (e.g., unavailable, undesired, or desired). It is used to express
 *          employee availability and scheduling preferences. Fields are mapped from JSON
 *          using Jackson annotations.
 *
 * @author acme
 * @version 1.0
 */
public class EmployeeDate {

    /** @brief Unique identifier for this employee date entry. */
    @JsonProperty("id")
    private int id;

    /**
     * @brief Identifier of the employee this date interval belongs to.
     * @details The field is named employeeId (like the accessors), so Jackson combines the field
     *          and accessors into a single property: with the old employee_id name, the JSON
     *          contained BOTH keys ("employee_id" and "employeeId").
     *          The external name remains "employee_id" through @JsonProperty.
     */
    @JsonProperty("employee_id")
    private int employeeId;

    /** @brief Start date and time of the interval. */
    @JsonProperty("dateStart")
    private LocalDateTime dateStart;

    /** @brief End date and time of the interval. */
    @JsonProperty("dateEnd")
    private LocalDateTime dateEnd;

    /** @brief Type identifier indicating the nature of the date (e.g., unavailable, undesired, desired). */
    @JsonProperty("dateTypeId")
    private int dateTypeId;

    /**
     * @brief Default no-argument constructor.
     *
     * @details Required by Jackson for JSON deserialization.
     */
    public EmployeeDate() {}

    /**
     * @brief Gets the unique identifier of this employee date entry.
     *
     * @return The entry ID.
     */
    public int getId() {
        return id;
    }

    /**
     * @brief Sets the unique identifier of this employee date entry.
     *
     * @param id The entry ID to set.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @brief Gets the employee identifier.
     *
     * @return The employee ID.
     */
    public int getEmployeeId() {
        return employeeId;
    }

    /**
     * @brief Sets the employee identifier.
     *
     * @param employeeId The employee ID to set.
     */
    public void setEmployeeId(int employeeId) {
        this.employeeId = employeeId;
    }

    /**
     * @brief Gets the start date and time of the interval.
     *
     * @return The start date-time.
     */
    public LocalDateTime getDateStart() {
        return dateStart;
    }

    /**
     * @brief Sets the start date and time of the interval.
     *
     * @param dateStart The start date-time to set.
     */
    public void setDateStart(LocalDateTime dateStart) {
        this.dateStart = dateStart;
    }

    /**
     * @brief Gets the end date and time of the interval.
     *
     * @return The end date-time.
     */
    public LocalDateTime getDateEnd() {
        return dateEnd;
    }

    /**
     * @brief Sets the end date and time of the interval.
     *
     * @param dateEnd The end date-time to set.
     */
    public void setDateEnd(LocalDateTime dateEnd) {
        this.dateEnd = dateEnd;
    }

    /**
     * @brief Gets the date type identifier.
     *
     * @return The date type ID.
     */
    public int getDateTypeId() {
        return dateTypeId;
    }

    /**
     * @brief Sets the date type identifier.
     *
     * @param dateTypeId The date type ID to set.
     */
    public void setDateTypeId(int dateTypeId) {
        this.dateTypeId = dateTypeId;
    }

    /**
     * @brief Returns a string representation of this employee date entry.
     *
     * @details Useful for debugging; includes all fields.
     *
     * @return A human-readable string describing the employee date.
     */
    @Override
    public String toString() {
        return "EmployeeDate{" +
                "id=" + id +
                ", employeeId=" + employeeId +
                ", dateStart=" + dateStart +
                ", dateEnd=" + dateEnd +
                ", dateTypeId=" + dateTypeId +
                '}';
    }
}
