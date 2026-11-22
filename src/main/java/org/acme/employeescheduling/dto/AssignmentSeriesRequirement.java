package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief One team line of a series: role, optional qualification, headcount.
 *
 * @details Copied into every occurrence the series generates, so the team composition of a
 *          recurring visit is maintained in exactly one place.
 */
public class AssignmentSeriesRequirement {

    @JsonProperty("id")
    public int id;

    @JsonProperty("seriesId")
    public int seriesId;

    /** {@code PHYSICIAN} or {@code MEDICAL_ASSISTANT}. */
    @JsonProperty("role")
    public String role = "MEDICAL_ASSISTANT";

    @JsonProperty("skillId")
    public Integer skillId;

    @JsonProperty("skillName")
    public String skillName = "";

    @JsonProperty("headcount")
    public int headcount = 1;
}
