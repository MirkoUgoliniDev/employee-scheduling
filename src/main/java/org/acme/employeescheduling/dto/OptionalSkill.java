package org.acme.employeescheduling.dto;


/**
 * @brief Represents an optional skill that can be associated with shifts or locations.
 *
 * @details This data transfer object models a skill that is preferred but not mandatory
 *          for a shift or location assignment. Optional skills contribute to soft
 *          constraints in the scheduling optimization.
 *
 * @author acme
 * @version 1.0
 */
public class OptionalSkill {

    /** @brief Unique identifier for the optional skill. */
    private int id;

    /** @brief Human-readable name of the optional skill. */
    private String name;

    /**
     * @brief Constructs an OptionalSkill with the specified ID and name.
     *
     * @param id   The unique identifier.
     * @param name The skill name.
     */
    public OptionalSkill(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * @brief Gets the optional skill ID.
     *
     * @return The skill ID.
     */
    public int getId() {
        return id;
    }

    /**
     * @brief Sets the optional skill ID.
     *
     * @param id The skill ID to set.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @brief Gets the optional skill name.
     *
     * @return The skill name.
     */
    public String getName() {
        return name;
    }

    /**
     * @brief Sets the optional skill name.
     *
     * @param name The skill name to set.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @brief Returns a string representation of this optional skill.
     *
     * @details Includes the id and name fields.
     *
     * @return A human-readable string describing the optional skill.
     */
    @Override
    public String toString() {
        return "OptionalSkill{" +
                "id=" + id +
                ", name='" + name + '\'' +
                '}';
    }

}