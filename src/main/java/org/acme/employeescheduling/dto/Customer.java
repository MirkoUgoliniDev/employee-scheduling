package org.acme.employeescheduling.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief A customer (patient, care home, practice) that requires staff.
 *
 * @details The customer is the reason an assignment exists: before it, the planner could only
 *          say "location X needs two people on Monday". A customer carries the defaults that
 *          make planning fast (usual start time, usual duration, usual qualification) and the
 *          restrictions that a scheduler must not forget (for example "no male staff",
 *          "only after 09:00"). {@code locationIds} is derived from
 *          {@code locations.customer_id}: a location belongs to at most one customer, so the
 *          relation is stored once, on the location.
 */
public class Customer {

    @JsonProperty("id")
    private int id;

    @JsonProperty("structureId")
    private int structureId;

    @JsonProperty("code")
    private String code;

    @JsonProperty("name")
    private String name;

    @JsonProperty("address")
    private String address = "";

    @JsonProperty("phone")
    private String phone = "";

    /** If false, the customer is disabled: no new assignments should be planned for it. */
    @JsonProperty("active")
    private boolean active = true;

    /** Default start of a visit, "HH:mm". */
    @JsonProperty("defaultStartTime")
    private String defaultStartTime = "08:00";

    /** Default visit length in minutes (a variable duration, not a fixed shift length). */
    @JsonProperty("defaultDurationMinutes")
    private int defaultDurationMinutes = 480;

    @JsonProperty("defaultSkillId")
    private Integer defaultSkillId;

    @JsonProperty("defaultSkillName")
    private String defaultSkillName = "";

    /** Free-text preferred recurrence ("every Monday and Thursday", descriptive only). */
    @JsonProperty("recurrencePattern")
    private String recurrencePattern = "";

    @JsonProperty("restrictions")
    private String restrictions = "";

    @JsonProperty("locationIds")
    private List<Integer> locationIds = new ArrayList<>();

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address == null ? "" : address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone == null ? "" : phone;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getDefaultStartTime() {
        return defaultStartTime;
    }

    public void setDefaultStartTime(String defaultStartTime) {
        this.defaultStartTime = defaultStartTime == null || defaultStartTime.isBlank() ? "08:00" : defaultStartTime;
    }

    public int getDefaultDurationMinutes() {
        return defaultDurationMinutes;
    }

    public void setDefaultDurationMinutes(int defaultDurationMinutes) {
        this.defaultDurationMinutes = defaultDurationMinutes;
    }

    public Integer getDefaultSkillId() {
        return defaultSkillId;
    }

    public void setDefaultSkillId(Integer defaultSkillId) {
        this.defaultSkillId = defaultSkillId;
    }

    public String getDefaultSkillName() {
        return defaultSkillName;
    }

    public void setDefaultSkillName(String defaultSkillName) {
        this.defaultSkillName = defaultSkillName == null ? "" : defaultSkillName;
    }

    public String getRecurrencePattern() {
        return recurrencePattern;
    }

    public void setRecurrencePattern(String recurrencePattern) {
        this.recurrencePattern = recurrencePattern == null ? "" : recurrencePattern;
    }

    public String getRestrictions() {
        return restrictions;
    }

    public void setRestrictions(String restrictions) {
        this.restrictions = restrictions == null ? "" : restrictions;
    }

    public List<Integer> getLocationIds() {
        return locationIds;
    }

    public void setLocationIds(List<Integer> locationIds) {
        this.locationIds = locationIds == null ? new ArrayList<>() : locationIds;
    }
}
