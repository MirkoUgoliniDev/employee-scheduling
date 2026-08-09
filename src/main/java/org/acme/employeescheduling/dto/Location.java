package org.acme.employeescheduling.dto;




import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;


/**
 * @brief Represents a physical location where shifts can be assigned.
 *
 * @details This data transfer object models a location within the scheduling system.
 *          Each location has an identifier, a display order, a name, a usage flag, and
 *          lists of required and optional skills. Locations are used as planning entities
 *          and are associated with shifts.
 *
 * @author acme
 * @version 1.0
 */


public class Location {

    /** @brief Unique identifier for the location. */
    @JsonProperty("id")
    private int id;

    /** @brief Short code identifying the location (e.g., LOC001). */
    @JsonProperty("code")
    private String code;

    /** @brief Display order used for sorting or presentation purposes. */
    @JsonProperty("order")
    private int order;

    /** @brief Human-readable name of the location. */
    @JsonProperty("name")
    private String name;

    /** @brief List of skills that are required at this location. */
    @JsonProperty("requiredSkills")
    private List<Skill> requiredSkills;

    /** @brief List of skills that are optional (preferred but not mandatory) at this location. */
    @JsonProperty("optionalSkills")
    private List<Skill> optionalSkills;

    /** @brief Flag indicating whether this location is currently in use. */
    @JsonProperty("used")
	private Boolean used;

    /** @brief If false, the location is disabled: excluded from Shift Management and the solver. */
    @JsonProperty("active")
    private boolean active = true;

    /** @brief ID of the specialist (doctor) assigned to the location, or null if none. */
    @JsonProperty("specialistId")
    private Integer specialistId;

    /** @brief Returns the assigned specialist's ID, or null. */
    public Integer getSpecialistId() { return specialistId; }

    /** @brief Sets the assigned specialist (null means none). */
    public void setSpecialistId(Integer specialistId) { this.specialistId = specialistId; }

    /**
     * @brief Default no-argument constructor.
     *
     * @details Required by Jackson for JSON deserialization.
     */
    public Location() {
    }

    /** @brief Returns true if the location is active. */
    public boolean isActive() { return active; }

    /** @brief Sets the active/disabled state. */
    public void setActive(boolean active) { this.active = active; }

    /**
     * @brief Constructs a Location with basic properties.
     *
     * @param id    The unique identifier.
     * @param order The display order.
     * @param name  The location name.
     */
    public Location(int id, String code, int order, String name) {
        this.id = id;
        this.code = code;
        this.order = order;
        this.name = name;
    }

    /**
     * @brief Constructs a Location with skills.
     *
     * @param id             The unique identifier.
     * @param order          The display order.
     * @param name           The location name.
     * @param requiredSkills The list of required skills.
     * @param optionalSkills The list of optional skills.
     */
    public Location(int id, String code, int order, String name, List<Skill> requiredSkills, List<Skill> optionalSkills) {
        this.id = id;
        this.code = code;
        this.order = order;
        this.name = name;
        this.requiredSkills = requiredSkills;
        this.optionalSkills = optionalSkills;
    }

    /**
     * @brief Constructs a fully specified Location including the usage flag.
     *
     * @param id             The unique identifier.
     * @param order          The display order.
     * @param name           The location name.
     * @param used           Whether the location is in use.
     * @param requiredSkills The list of required skills.
     * @param optionalSkills The list of optional skills.
     */
    public Location(int id, String code, int order, String name, Boolean used, List<Skill> requiredSkills, List<Skill> optionalSkills) {
        this.id = id;
        this.code = code;
        this.order = order;
        this.name = name;
        this.used = used;
        this.requiredSkills = requiredSkills;
        this.optionalSkills = optionalSkills;
    }

    /**
     * @brief Gets the location code.
     *
     * @return The location code.
     */
    public String getCode() {
        return code;
    }

    /**
     * @brief Sets the location code.
     *
     * @param code The location code to set.
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * @brief Gets the location ID.
     *
     * @return The location ID.
     */
    public int getId() {
        return id;
    }

    /**
     * @brief Sets the location ID.
     *
     * @param id The location ID to set.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @brief Sets the display order, accepting both String and Number types for flexible JSON deserialization.
     *
     * @param order The order value, which can be a String or Number.
     * @throws IllegalArgumentException if the provided order is not a String or Number.
     */
    @JsonSetter("order")
    public void setOrder(Object order) {
        if (order instanceof String) {
            this.order = Integer.parseInt((String) order);
        } else if (order instanceof Number) {
            this.order = ((Number) order).intValue();
        } else {
            throw new IllegalArgumentException("Invalid type for order: " + order.getClass().getName());
        }
    }

    /**
     * @brief Gets the display order.
     *
     * @return The order value.
     */
    public int getOrder() {
        return order;
    }

    /**
     * @brief Gets the location name.
     *
     * @return The location name.
     */
    public String getName() {
        return name;
    }

    /**
     * @brief Sets the location name.
     *
     * @param name The location name to set.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @brief Gets the usage flag.
     *
     * @return true if the location is in use, false otherwise, or null if not set.
     */
    public Boolean getUsed() {
        return used;
    }

    /**
     * @brief Sets the usage flag.
     *
     * @param used Whether the location is in use.
     */
    public void setUsed(Boolean used) {
        this.used = used;
    }

    /**
     * @brief Gets the list of required skills for this location.
     *
     * @details @JsonIgnore: as in Shift, the getter's implicit name duplicated the field key
     *          ("requiredSkill" + "requiredSkills") in the JSON.
     *
     * @return The list of required skills.
     */
    @JsonIgnore
    public List<Skill> getRequiredSkill() {
        return requiredSkills;
    }

    /**
     * @brief Sets the list of required skills for this location.
     *
     * @param requiredSkills The list of required skills to set.
     */
    @JsonSetter("requiredSkill")
    public void setRequiredSkill(List<Skill> requiredSkills) {
        this.requiredSkills = requiredSkills;
    }

    /**
     * @brief Gets the list of optional skills for this location.
     *
     * @return The list of optional skills.
     */
    @JsonIgnore
    public List<Skill> getOptionalSkill() {
        return optionalSkills;
    }

    /**
     * @brief Sets the list of optional skills for this location.
     *
     * @param optionalSkill The list of optional skills to set.
     */
    @JsonSetter("optionalSkill")
    public void setOptionalSkill(List<Skill> optionalSkill) {
        this.optionalSkills = optionalSkill;
    }

    /**
     * @brief Returns a string representation of this location.
     *
     * @details Includes id, order, and name.
     *
     * @return A human-readable string describing the location.
     */
    @Override
    public String toString() {
        return "Location{" +
                "id=" + id +
                ", code='" + code + '\'' +
                ", order=" + order +
                ", name='" + name + '\'' +
                '}';
    }
}
