package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief One concrete team slot of an assignment, backed by exactly one shift row.
 *
 * @details A position with {@code employeeId == null} is an <b>unfilled position</b>: it is
 *          shown to the planner instead of disappearing behind a partially staffed assignment.
 *          The solver leaves such a shift unassigned ({@code allowsUnassigned}) and the
 *          "Unassigned shift" hard constraint reports the gap.
 */
public class AssignmentPosition {

    /** The underlying shifts.id — the identity the solver and the save path use. */
    @JsonProperty("id")
    private int id;

    @JsonProperty("role")
    private String role = "";

    @JsonProperty("skillId")
    private Integer skillId;

    @JsonProperty("skillName")
    private String skillName = "";

    @JsonProperty("employeeId")
    private Integer employeeId;

    @JsonProperty("employeeName")
    private String employeeName = "";

    @JsonProperty("start")
    private String start = "";

    @JsonProperty("end")
    private String end = "";

    @JsonProperty("pinned")
    private boolean pinned;

    @JsonProperty("version")
    private int version;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role == null ? "" : role;
    }

    public Integer getSkillId() {
        return skillId;
    }

    public void setSkillId(Integer skillId) {
        this.skillId = skillId;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName == null ? "" : skillName;
    }

    public Integer getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Integer employeeId) {
        this.employeeId = employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName == null ? "" : employeeName;
    }

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = start == null ? "" : start;
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = end == null ? "" : end;
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
}
