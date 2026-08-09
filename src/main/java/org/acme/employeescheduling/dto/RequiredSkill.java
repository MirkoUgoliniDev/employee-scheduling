package org.acme.employeescheduling.dto;

/**
 * @brief Represents a required skill that must be possessed by an employee for a shift or location.
 *
 * @details This data transfer object models a skill that is mandatory for a shift or location
 *          assignment. Required skills contribute to hard constraints in the scheduling
 *          optimization -- an employee lacking a required skill cannot be assigned.
 *
 * @author acme
 * @version 1.0
 */
public class RequiredSkill {

    /** @brief Unique identifier for the required skill. */
    private int id;

    /** @brief Human-readable name of the required skill. */
    private String name;

    /**
     * @brief Constructs a RequiredSkill with the specified ID and name.
     *
     * @param id   The unique identifier.
     * @param name The skill name.
     */
    public RequiredSkill(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * @brief Gets the required skill ID.
     *
     * @return The skill ID.
     */
    public int getId() {
        return id;
    }

    /**
     * @brief Sets the required skill ID.
     *
     * @param id The skill ID to set.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @brief Gets the required skill name.
     *
     * @return The skill name.
     */
    public String getName() {
        return name;
    }

    /**
     * @brief Sets the required skill name.
     *
     * @param name The skill name to set.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @brief Returns a string representation of this required skill.
     *
     * @details Includes the id and name fields.
     *
     * @return A human-readable string describing the required skill.
     */
    @Override
    public String toString() {
        return "RequiredSkill{" +
                "id=" + id +
                ", name='" + name + '\'' +
                '}';
    }
}