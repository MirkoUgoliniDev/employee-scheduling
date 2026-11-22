package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief One staffing line of an assignment: a role, an optional qualification, a headcount.
 *
 * @details "1 anaesthetist + 2 medical assistants" is stored as two rows (headcount 1 and 2)
 *          instead of two separate concepts. Each headcount unit materialises into its own
 *          shift row ("position"), because the solver assigns exactly one employee per shift.
 */
public class AssignmentRequirement {

    @JsonProperty("id")
    private int id;

    @JsonProperty("assignmentId")
    private int assignmentId;

    /** {@code PHYSICIAN} or {@code MEDICAL_ASSISTANT}. */
    @JsonProperty("role")
    private String role = "MEDICAL_ASSISTANT";

    /** Optional qualification on top of the role (for example anaesthesiology). */
    @JsonProperty("skillId")
    private Integer skillId;

    @JsonProperty("skillName")
    private String skillName = "";

    /** How many positions of this role/qualification the assignment needs. */
    @JsonProperty("headcount")
    private int headcount = 1;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(int assignmentId) {
        this.assignmentId = assignmentId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    public int getHeadcount() {
        return headcount;
    }

    public void setHeadcount(int headcount) {
        this.headcount = headcount;
    }
}
