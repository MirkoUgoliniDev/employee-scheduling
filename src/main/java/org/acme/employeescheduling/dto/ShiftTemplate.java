package org.acme.employeescheduling.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief A shift template: recurring weekly pattern used to prepopulate windows.
 *
 * @details Unlike a Shift, it has no absolute date, but a day of the week (0=Monday … 6=Sunday)
 *          and a time of day (start/end as "HH:mm:ss"), plus the location and required/optional
 *          skills. It has no employee (the solver assigns one when the template is instantiated
 *          as real shifts).
 */
public class ShiftTemplate {

    /** @brief Unique shift-template identifier. */
    @JsonProperty("id")
    private int id;

    /** @brief Owning structure. */
    @JsonProperty("structure_id")
    private int structureId;

    /** @brief Owning saved-template header (0/null = legacy working pattern). */
    @JsonProperty("header_id")
    private Integer headerId;

    /** @brief Day of the week: 0=Monday … 6=Sunday. */
    @JsonProperty("day_of_week")
    private int dayOfWeek;

    /** @brief Start time of day, in "HH:mm:ss" format. */
    @JsonProperty("start_time")
    private String startTime;

    /** @brief End time of day, in "HH:mm:ss" format. */
    @JsonProperty("end_time")
    private String endTime;

    /** @brief Shift location. */
    @JsonProperty("location_id")
    private int locationId;

    /** @brief Location name (denormalized for display). */
    @JsonProperty("location_desc")
    private String locationDesc;

    /** @brief Required skills (with `used` flag). */
    @JsonProperty("requiredSkills")
    private List<Skill> requiredSkills = new ArrayList<>();

    /** @brief Optional skills (with `used` flag). */
    @JsonProperty("optionalSkills")
    private List<Skill> optionalSkills = new ArrayList<>();

    public ShiftTemplate() {}

    public ShiftTemplate(int id, int structureId, int dayOfWeek, String startTime, String endTime,
                         int locationId, String locationDesc,
                         List<Skill> requiredSkills, List<Skill> optionalSkills) {
        this.id = id;
        this.structureId = structureId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.locationId = locationId;
        this.locationDesc = locationDesc;
        this.requiredSkills = requiredSkills != null ? requiredSkills : new ArrayList<>();
        this.optionalSkills = optionalSkills != null ? optionalSkills : new ArrayList<>();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getStructureId() { return structureId; }
    public void setStructureId(int structureId) { this.structureId = structureId; }

    public Integer getHeaderId() { return headerId; }
    public void setHeaderId(Integer headerId) { this.headerId = headerId; }

    public int getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public int getLocationId() { return locationId; }
    public void setLocationId(int locationId) { this.locationId = locationId; }

    public String getLocationDesc() { return locationDesc; }
    public void setLocationDesc(String locationDesc) { this.locationDesc = locationDesc; }

    public List<Skill> getRequiredSkills() { return requiredSkills; }
    public void setRequiredSkills(List<Skill> requiredSkills) { this.requiredSkills = requiredSkills; }

    public List<Skill> getOptionalSkills() { return optionalSkills; }
    public void setOptionalSkills(List<Skill> optionalSkills) { this.optionalSkills = optionalSkills; }
}
