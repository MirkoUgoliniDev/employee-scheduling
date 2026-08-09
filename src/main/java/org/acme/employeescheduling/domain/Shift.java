package org.acme.employeescheduling.domain;


import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import org.acme.employeescheduling.dto.EmployeeDate;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.Skill;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;



/**
 * @brief Represents a work shift as a planning entity for the Timefold Solver.
 *
 * @details This class models a schedulable shift within the employee scheduling system.
 *          Each shift has a time range (start/end), a location, required and optional skills,
 *          and a planning variable representing the assigned employee. The Timefold Solver
 *          assigns employees to shifts based on defined constraints.
 *
 * @author acme
 * @version 1.0
 */
@PlanningEntity
public class Shift {

    /** @brief Date-time formatter using the pattern "yyyy-MM-dd HH:mm:ss". */
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** @brief Unique identifier for this shift, used by the Timefold Solver as the planning ID. */
    @PlanningId
    private Integer id;

    /** @brief Identifier of the location where this shift takes place. */
    private Integer location_id;

    /** @brief Human-readable description of the shift location. */
    private String location_desc;

    /**
     * @brief ID of the shift location's specialist (denormalized), or null.
     * @details Copied from locations.specialist_id when building the solver payload
     *          (generateDemoData): allows employee-specialist compatibility constraints
     *          to work with a simple forEach(Shift).
     */
    private Integer specialistId;

    /** @brief List of skills that are required for an employee to be assigned to this shift. */
    @JsonProperty("requiredSkills")
    private List<Skill> requiredSkills;

    /** @brief List of skills that are optional (preferred but not mandatory) for this shift. */
    @JsonProperty("optionalSkills")
    private List<Skill> optionalSkills;

    /** @brief Start date and time of the shift. */
    @JsonProperty("start")
    private LocalDateTime start;

    /** @brief End date and time of the shift. */
    @JsonProperty("end")
    private LocalDateTime end;

    /** @brief DB-stored employee ID (for persistence across sessions). */
    private Integer employeeId;

    /** @brief Timefold planning pin: when true, solver does NOT change the employee field. */
    @PlanningPin
    private boolean pinned;

    /**
     * @brief CONTEXT shift: outside the solved window, loaded only so boundary constraints
     *        (overlap, minimum rest, weekly hours, consecutive days) can see assignments
     *        already saved on adjacent days (SolverSettings.context_days). Always pinned,
     *        never persisted when assignments are saved, and excluded from other constraints.
     */
    private boolean context;

    /**
     * @brief Shift revision read from the database when solving starts.
     * @details It travels to the client and returns with the save request: if someone has
     *          modified the shift in the meantime, the revision no longer matches and the save
     *          is rejected instead of assigning an employee to a shift the solver never saw.
     *          This is not planning data: the solver ignores it.
     */
    private int version;

    /** @brief The employee assigned to this shift; serves as the planning variable for the solver. */
    @PlanningVariable(allowsUnassigned = true)
    private Employee employee;

    /**
     * @brief Default no-argument constructor.
     *
     * @details Required by the Timefold Solver and Jackson for deserialization.
     */
    public Shift() {}

    /**
     * @brief Constructs a Shift with core parameters and no assigned employee.
     *
     * @param start         The start date and time of the shift.
     * @param end           The end date and time of the shift.
     * @param location_id   The identifier of the shift location.
     * @param location_desc The description of the shift location.
     * @param requiredSkill The list of required skills for the shift.
     * @param optionalSkill The list of optional skills for the shift.
     */
    public Shift(LocalDateTime start, LocalDateTime end, int location_id, String location_desc, List<Skill> requiredSkill, List<Skill> optionalSkill) {
        this(start, end, location_id, location_desc, requiredSkill, optionalSkill, null);
    }

    /**
     * @brief Constructs a Shift with core parameters and an assigned employee, using a default ID of 0.
     *
     * @param start         The start date and time of the shift.
     * @param end           The end date and time of the shift.
     * @param location_id   The identifier of the shift location.
     * @param location_desc The description of the shift location.
     * @param requiredSkill The list of required skills for the shift.
     * @param optionalSkill The list of optional skills for the shift.
     * @param employee      The employee assigned to the shift, or null if unassigned.
     */
    public Shift(LocalDateTime start, LocalDateTime end, int location_id, String location_desc, List<Skill> requiredSkill, List<Skill> optionalSkill, Employee employee) {
        this(0, start, end, location_id, location_desc, requiredSkill, optionalSkill, employee);
    }

    /**
     * @brief Constructs a fully specified Shift with all fields.
     *
     * @param id             The unique identifier of the shift.
     * @param start          The start date and time of the shift.
     * @param end            The end date and time of the shift.
     * @param location_id    The identifier of the shift location.
     * @param location_desc  The description of the shift location.
     * @param requiredSkills The list of required skills for the shift.
     * @param optionalSkills The list of optional skills for the shift.
     * @param employee       The employee assigned to the shift, or null if unassigned.
     */
    public Shift(int id, LocalDateTime start, LocalDateTime end, int location_id, String location_desc, List<Skill> requiredSkills, List<Skill> optionalSkills, Employee employee) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.location_id = location_id;
        this.location_desc = location_desc;
        this.requiredSkills = requiredSkills;
        this.optionalSkills = optionalSkills;
        this.employee = employee;
    }

    /**
     * @brief Gets the unique identifier of this shift.
     *
     * @return The shift ID.
     */
    @JsonProperty
    public int getId() {
        // Null-safe: a client posting a shift without an ID would cause an NPE during
        // unboxing (equals/hashCode/serialization pass through here).
        return id != null ? id : 0;
    }

    /**
     * @brief Sets the unique identifier of this shift.
     *
     * @param id The shift ID to set.
     */
    @JsonProperty
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * @brief Gets the start date and time of the shift.
     *
     * @return The start date-time.
     */
    public LocalDateTime getStart() {
        return start;
    }

    /**
     * @brief Sets the start date and time of the shift.
     *
     * @param start The start date-time to set.
     */
    public void setStart(LocalDateTime start) {
        this.start = start;
    }

    /**
     * @brief Gets the end date and time of the shift.
     *
     * @return The end date-time.
     */
    public LocalDateTime getEnd() {
        return end;
    }

    /**
     * @brief Sets the end date and time of the shift.
     *
     * @param end The end date-time to set.
     */
    public void setEnd(LocalDateTime end) {
        this.end = end;
    }

    /**
     * @brief Gets the location identifier of the shift.
     *
     * @return The location ID.
     */
    public int getLocation_id() {
        return location_id;
    }

    /**
     * @brief Sets the location identifier of the shift.
     *
     * @param location The location ID to set.
     */
    public void setLocation_id(Integer location) {
        this.location_id = location;
    }

    /**
     * @brief Gets the description of the shift location.
     *
     * @return The location description.
     */
    public String getLocation_desc() {
        return location_desc;
    }

    /**
     * @brief Sets the description of the shift location.
     *
     * @param location_desc The location description to set.
     */
    public void setLocation_desc(String location_desc) {
        this.location_desc = location_desc;
    }

    /**
     * @brief Gets the list of required skills for this shift.
     *
     * @details @JsonIgnore: the getter's implicit name ("requiredSkill") differed from the
     *          field's @JsonProperty ("requiredSkills"), and Jackson serialized BOTH keys —
     *          duplicating the skill catalog in every shift in the payload. Only
     *          "requiredSkills" remains on output; on input the setter still accepts the
     *          singular form for backward compatibility.
     *
     * @return The list of required skills.
     */
    @JsonIgnore
    public List<Skill> getRequiredSkill() {
        return requiredSkills;
    }

    /**
     * @brief Sets the list of required skills for this shift.
     *
     * @param requiredSkills The list of required skills to set.
     */
    @JsonSetter
    public void setRequiredSkill(List<Skill> requiredSkills) {
        this.requiredSkills = requiredSkills;
    }

    /**
     * @brief Gets the list of optional skills for this shift.
     *
     * @return The list of optional skills.
     */
    @JsonIgnore
    public List<Skill> getOptionalSkill() {
        return optionalSkills;
    }

    /**
     * @brief Sets the list of optional skills for this shift.
     *
     * @param optionalSkills The list of optional skills to set.
     */
    public void setOptionalSkill(List<Skill> optionalSkills) {
        this.optionalSkills = optionalSkills;
    }

    /**
     * @brief Gets the employee assigned to this shift.
     *
     * @return The assigned employee, or null if no employee is assigned.
     */
    public Employee getEmployee() {
        return employee;
    }

    /**
     * @brief Sets the employee assigned to this shift.
     *
     * @param employee The employee to assign, or null to unassign.
     */
    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    /** @brief ID of the shift location's specialist, or null if the location has none. */
    public Integer getSpecialistId() {
        return specialistId;
    }

    /** @brief Sets the specialist denormalized from the shift location. */
    public void setSpecialistId(Integer specialistId) {
        this.specialistId = specialistId;
    }

    public Integer getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Integer employeeId) {
        this.employeeId = employeeId;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    /** @brief true if this is a context shift outside the window (see {@link #context}). */
    public boolean isContext() {
        return context;
    }

    public void setContext(boolean context) {
        this.context = context;
    }

    /**
     * @brief Checks whether this shift overlaps with a given employee date interval.
     *
     * @details The shift overlaps if it is NOT entirely before or entirely after the
     *          provided date interval. Returns false if any required field is null.
     *
     * @param date The employee date interval to check against.
     * @return true if the shift overlaps with the given date interval, false otherwise.
     */
    public boolean isOverlappingWithDate(EmployeeDate date) {
        if (getStart() == null || getEnd() == null || date == null
                || date.getDateStart() == null || date.getDateEnd() == null) {
            return false;
        }
        return !(getEnd().compareTo(date.getDateStart()) <= 0 || getStart().compareTo(date.getDateEnd()) >= 0);
    }

    /**
     * @brief Calculates the overlapping duration in minutes between this shift and a given date.
     *
     * @details Computes the overlap between the shift's time range and the full extent
     *          of the specified date (from midnight to end of day).
     *
     * @param date The date to compute overlap against.
     * @return The overlapping duration in minutes, or 0 if there is no overlap.
     */
    public int getOverlappingDurationInMinutes(LocalDate date) {
        LocalDateTime startDateTime = LocalDateTime.of(date, LocalTime.MIN);
        LocalDateTime endDateTime = LocalDateTime.of(date, LocalTime.MAX);
        return getOverlappingDurationInMinutes(startDateTime, endDateTime, getStart(), getEnd());
    }

    /**
     * @brief Calculates the overlapping duration in minutes between this shift and an employee date interval.
     *
     * @details Determines the intersection between the shift time range and the employee
     *          date interval, returning the duration in minutes. Returns 0 if any required
     *          field is null or if there is no overlap.
     *
     * @param dateInterval The employee date interval to compute overlap against.
     * @return The overlapping duration in minutes, or 0 if there is no overlap.
     */
    public int getOverlappingDurationInMinutes(EmployeeDate dateInterval) {
        if (getStart() == null || getEnd() == null || dateInterval == null
                || dateInterval.getDateStart() == null || dateInterval.getDateEnd() == null) {
            return 0;
        }
        LocalDateTime intervalStart = dateInterval.getDateStart();
        LocalDateTime intervalEnd = dateInterval.getDateEnd();
        LocalDateTime maxStart = getStart().isAfter(intervalStart) ? getStart() : intervalStart;
        LocalDateTime minEnd = getEnd().isBefore(intervalEnd) ? getEnd() : intervalEnd;
        long minutes = Duration.between(maxStart, minEnd).toMinutes();
        return minutes > 0 ? (int) minutes : 0;
    }

    /**
     * @brief Calculates the overlapping duration in minutes between two arbitrary time intervals.
     *
     * @details Computes the intersection of two date-time ranges by taking the maximum of
     *          the two start times and the minimum of the two end times, then converting
     *          the resulting duration to minutes.
     *
     * @param firstStartDateTime  The start of the first time interval.
     * @param firstEndDateTime    The end of the first time interval.
     * @param secondStartDateTime The start of the second time interval.
     * @param secondEndDateTime   The end of the second time interval.
     * @return The overlapping duration in minutes, or 0 if the intervals do not overlap.
     */
	public int getOverlappingDurationInMinutes(LocalDateTime firstStartDateTime, LocalDateTime firstEndDateTime,
		LocalDateTime secondStartDateTime, LocalDateTime secondEndDateTime) {
		LocalDateTime maxStartTime = firstStartDateTime.isAfter(secondStartDateTime) ? firstStartDateTime : secondStartDateTime;
		LocalDateTime minEndTime = firstEndDateTime.isBefore(secondEndDateTime) ? firstEndDateTime : secondEndDateTime;
		long minutes = Duration.between(maxStartTime, minEndTime).toMinutes();
		return minutes > 0 ? (int) minutes : 0;
	}

    /**
     * @brief Returns a string representation of this shift.
     *
     * @details Includes location ID, formatted start and end times, required skills,
     *          and optional skills.
     *
     * @return A human-readable string describing the shift.
     */
    @Override
    public String toString() {
        String startStr = start != null ? start.format(formatter) : "null";
        String endStr = end != null ? end.format(formatter) : "null";
        return location_id + " " + startStr + "-" + endStr + " (Required: " + requiredSkills + ", Optional: " + optionalSkills + ")";
    }

    /**
     * @brief Compares this shift with another object for equality based on the shift ID.
     *
     * @param o The object to compare with.
     * @return true if the other object is a Shift with the same ID, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Shift shift)) {
            return false;
        }
        return Objects.equals(getId(), shift.getId());
    }

    /**
     * @brief Computes the hash code for this shift based on its ID.
     *
     * @return The hash code value.
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
