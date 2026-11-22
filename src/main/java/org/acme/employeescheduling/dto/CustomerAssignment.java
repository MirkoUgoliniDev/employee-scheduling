package org.acme.employeescheduling.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief The central planning object: one customer visit with a configurable duration and team.
 *
 * @details An assignment says who is visited, where, on which day, from when and for how long,
 *          and which team composition is required. It is <b>not</b> a shift: it owns a list of
 *          {@link AssignmentPosition}s (one shift row each), so a team of three is one assignment
 *          with three positions rather than three unrelated shifts.
 *
 *          <p>{@code status} is one of {@code FIXED}, {@code FLEXIBLE}, {@code RECURRING},
 *          {@code RESCHEDULED}, {@code CANCELLED}, {@code COMPLETED}. Only {@code FIXED},
 *          {@code FLEXIBLE} and {@code RESCHEDULED} assignments are planned; cancelled and
 *          completed ones keep their history but produce no open positions.
 *
 *          <p>The staffing counters ({@code requiredHeadcount}, {@code filledPositions},
 *          {@code unfilledPositions}) are computed on read, so the UI shows a partial team
 *          instead of hiding it.
 */
public class CustomerAssignment {

    @JsonProperty("id")
    private int id;

    @JsonProperty("structureId")
    private int structureId;

    @JsonProperty("customerId")
    private int customerId;

    @JsonProperty("customerName")
    private String customerName = "";

    @JsonProperty("locationId")
    private int locationId;

    @JsonProperty("locationName")
    private String locationName = "";

    @JsonProperty("title")
    private String title = "";

    /** Medical service performed (for example "Wundversorgung"), free text. */
    @JsonProperty("service")
    private String service = "";

    /** Assignment day, "yyyy-MM-dd". */
    @JsonProperty("assignmentDate")
    private String assignmentDate;

    /** Start of the visit, "HH:mm". */
    @JsonProperty("startTime")
    private String startTime = "08:00";

    /** Visit length in minutes; changing it regenerates the positions. */
    @JsonProperty("durationMinutes")
    private int durationMinutes = 480;

    /** Computed end time, "HH:mm". */
    @JsonProperty("endTime")
    private String endTime = "";

    /** 1 = high, 2 = normal, 3 = low. */
    @JsonProperty("priority")
    private int priority = 3;

    @JsonProperty("status")
    private String status = "FLEXIBLE";

    /** When true the start time is locked: the solver may choose the team, never the slot. */
    @JsonProperty("fixedTime")
    private boolean fixedTime;

    @JsonProperty("notes")
    private String notes = "";

    /** Reference to the recurrence series that generated this occurrence, if any. */
    @JsonProperty("seriesId")
    private Integer seriesId;

    @JsonProperty("requirements")
    private List<AssignmentRequirement> requirements = new ArrayList<>();

    @JsonProperty("positions")
    private List<AssignmentPosition> positions = new ArrayList<>();

    @JsonProperty("requiredHeadcount")
    private int requiredHeadcount;

    @JsonProperty("filledPositions")
    private int filledPositions;

    @JsonProperty("unfilledPositions")
    private int unfilledPositions;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getStructureId() {
        return structureId;
    }

    public void setStructureId(int structureId) {
        this.structureId = structureId;
    }

    public int getCustomerId() {
        return customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName == null ? "" : customerName;
    }

    public int getLocationId() {
        return locationId;
    }

    public void setLocationId(int locationId) {
        this.locationId = locationId;
    }

    public String getLocationName() {
        return locationName;
    }

    public void setLocationName(String locationName) {
        this.locationName = locationName == null ? "" : locationName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service == null ? "" : service;
    }

    public String getAssignmentDate() {
        return assignmentDate;
    }

    public void setAssignmentDate(String assignmentDate) {
        this.assignmentDate = assignmentDate;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime == null || startTime.isBlank() ? "08:00" : startTime;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime == null ? "" : endTime;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isFixedTime() {
        return fixedTime;
    }

    public void setFixedTime(boolean fixedTime) {
        this.fixedTime = fixedTime;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    public Integer getSeriesId() {
        return seriesId;
    }

    public void setSeriesId(Integer seriesId) {
        this.seriesId = seriesId;
    }

    public List<AssignmentRequirement> getRequirements() {
        return requirements;
    }

    public void setRequirements(List<AssignmentRequirement> requirements) {
        this.requirements = requirements == null ? new ArrayList<>() : requirements;
    }

    public List<AssignmentPosition> getPositions() {
        return positions;
    }

    public void setPositions(List<AssignmentPosition> positions) {
        this.positions = positions == null ? new ArrayList<>() : positions;
    }

    public int getRequiredHeadcount() {
        return requiredHeadcount;
    }

    public void setRequiredHeadcount(int requiredHeadcount) {
        this.requiredHeadcount = requiredHeadcount;
    }

    public int getFilledPositions() {
        return filledPositions;
    }

    public void setFilledPositions(int filledPositions) {
        this.filledPositions = filledPositions;
    }

    public int getUnfilledPositions() {
        return unfilledPositions;
    }

    public void setUnfilledPositions(int unfilledPositions) {
        this.unfilledPositions = unfilledPositions;
    }
}
