package org.acme.employeescheduling.dto;


import com.fasterxml.jackson.annotation.JsonProperty;


/**
 * @brief Represents a skill that can be required or optional for shifts and locations.
 *
 * @details This data transfer object models a skill within the employee scheduling system.
 *          Skills are associated with employees (as competencies) and with shifts/locations
 *          (as requirements). Each skill has an identifier, a name, a display order, and
 *          a flag indicating whether it is currently in use.
 *
 * @author acme
 * @version 1.0
 */
public class Skill {

    /** @brief Unique identifier for the skill. */
	@JsonProperty("id")
    private int id;

    /** @brief Human-readable name of the skill. */
	@JsonProperty("name")
    private String name;

    /** @brief Display order used for sorting or presentation purposes. */
	@JsonProperty("order")
    private int order;

    /** @brief Flag indicating whether this skill is currently in use. */
	@JsonProperty("used")
	private Boolean used;

	@JsonProperty("active")
	private Boolean active = true;

	@JsonProperty("translationLanguageId")
	private Integer translationLanguageId;

	@JsonProperty("translationValue")
	private String translationValue;

    /**
     * @brief Default no-argument constructor.
     *
     * @details Required by Jackson for JSON deserialization.
     */
	public Skill() {}

    /**
     * @brief Constructs a fully specified Skill.
     *
     * @param id    The unique identifier.
     * @param name  The skill name.
     * @param order The display order.
     * @param used  Whether the skill is in use.
     */
    public Skill(int id, String name, int order, Boolean used ) {
        this(id, name, order, used, true);
    }

    public Skill(int id, String name, int order, Boolean used, Boolean active) {
        this.id = id;
        this.name = name;
        this.order = order;
        this.used = used;
        this.active = active;
    }

    /**
     * @brief Gets the skill ID.
     *
     * @return The skill ID.
     */
    public int getId() {
        return id;
    }

    /**
     * @brief Sets the skill ID.
     *
     * @param id The skill ID to set.
     */
    public void setId(int id) {
        this.id = id;
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
     * @brief Sets the display order.
     *
     * @param order The order value to set.
     */
    public void setOrder(int order) {
        this.order = order;
    }

    /**
     * @brief Gets the skill name.
     *
     * @return The skill name.
     */
    public String getName() {
        return name;
    }

    /**
     * @brief Sets the skill name.
     *
     * @param name The skill name to set.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @brief Gets the usage flag as a Boolean object.
     *
     * @return true if the skill is in use, false otherwise, or null if not set.
     */
    public Boolean getUsed() {
        return used;
    }

    /**
     * @brief Sets the usage flag.
     *
     * @param used Whether the skill is in use.
     */
    public void setUsed(Boolean used) {
        this.used = used;
    }

    /**
     * @brief Checks whether this skill is in use.
     *
     * @return true if the skill is in use, false otherwise.
     */
    public boolean isUsed() {
        return Boolean.TRUE.equals(used);
    }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public boolean isActive() { return !Boolean.FALSE.equals(active); }

    public Integer getTranslationLanguageId() { return translationLanguageId; }
    public void setTranslationLanguageId(Integer translationLanguageId) { this.translationLanguageId = translationLanguageId; }
    public String getTranslationValue() { return translationValue; }
    public void setTranslationValue(String translationValue) { this.translationValue = translationValue; }

    /**
     * @brief Two Skill objects are equal if they have the same ID.
     * @details Required because shifts and employees receive different INSTANCES of the same
     *          skill: without ID-based equality, contains/containsAll compare by identity and
     *          always fail (a historical bug in the requiredSkill constraint).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Skill)) return false;
        return id == ((Skill) o).id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    /**
     * @brief Returns a string representation of this skill.
     *
     * @details Includes id, name, order, and used flag.
     *
     * @return A human-readable string describing the skill.
     */
    @Override
    public String toString() {
        return "Skill{" +
               "id=" + id +
               ", name='" + name + '\'' +
               ", order=" + order +
               ", used=" + used +
               '}';
    }


}

