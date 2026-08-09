package org.acme.employeescheduling.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * @brief Represents a time slot at a specific location with associated skill requirements.
 *
 * @details This data transfer object models a schedulable time slot defined by a start time,
 *          end time, location, and lists of required and optional skills. Slots are used
 *          to define the granular time-location units within the scheduling system.
 *          Equality and hashing are based on all fields (start time, end time, location ID,
 *          required skills, and optional skills).
 *
 * @author acme
 * @version 1.0
 */
public class Slot {

    /** @brief Start date and time of the slot. */
    private LocalDateTime startTime;

    /** @brief End date and time of the slot. */
    private LocalDateTime endTime;

    /** @brief Identifier of the location associated with this slot. */
    private int locationId;

    /** @brief List of skills that are required for this slot. */
    private List<Skill> requiredSkills;

    /** @brief List of skills that are optional for this slot. */
    private List<Skill> optionalSkills;

    /**
     * @brief Constructs a fully specified Slot.
     *
     * @param startTime      The start date and time of the slot.
     * @param endTime        The end date and time of the slot.
     * @param locationId     The identifier of the location.
     * @param requiredSkills The list of required skills.
     * @param optionalSkills The list of optional skills.
     */
    public Slot(LocalDateTime startTime, LocalDateTime endTime, int locationId,
                List<Skill> requiredSkills, List<Skill> optionalSkills) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.locationId = locationId;
        this.requiredSkills = requiredSkills;
        this.optionalSkills = optionalSkills;
    }

    /**
     * @brief Gets the start date and time of the slot.
     *
     * @return The start date-time.
     */
    public LocalDateTime getStartTime() {
        return startTime;
    }

    /**
     * @brief Sets the start date and time of the slot.
     *
     * @param startTime The start date-time to set.
     */
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    /**
     * @brief Gets the end date and time of the slot.
     *
     * @return The end date-time.
     */
    public LocalDateTime getEndTime() {
        return endTime;
    }

    /**
     * @brief Sets the end date and time of the slot.
     *
     * @param endTime The end date-time to set.
     */
    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    /**
     * @brief Gets the location identifier.
     *
     * @return The location ID.
     */
    public int getLocationId() {
        return locationId;
    }

    /**
     * @brief Sets the location identifier.
     *
     * @param locationId The location ID to set.
     */
    public void setLocationId(int locationId) {
        this.locationId = locationId;
    }

    /**
     * @brief Gets the list of required skills for this slot.
     *
     * @return The list of required skills.
     */
    public List<Skill> getRequiredSkills() {
        return requiredSkills;
    }

    /**
     * @brief Sets the list of required skills for this slot.
     *
     * @param requiredSkills The list of required skills to set.
     */
    public void setRequiredSkills(List<Skill> requiredSkills) {
        this.requiredSkills = requiredSkills;
    }

    /**
     * @brief Gets the list of optional skills for this slot.
     *
     * @return The list of optional skills.
     */
    public List<Skill> getOptionalSkills() {
        return optionalSkills;
    }

    /**
     * @brief Sets the list of optional skills for this slot.
     *
     * @param optionalSkills The list of optional skills to set.
     */
    public void setOptionalSkills(List<Skill> optionalSkills) {
        this.optionalSkills = optionalSkills;
    }

    /**
     * @brief Compares this slot with another object for equality.
     *
     * @details Two slots are considered equal if they have the same location ID, start time,
     *          end time, required skills, and optional skills.
     *
     * @param o The object to compare with.
     * @return true if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Slot slot = (Slot) o;
        return locationId == slot.locationId &&
               Objects.equals(startTime, slot.startTime) &&
               Objects.equals(endTime, slot.endTime) &&
               Objects.equals(requiredSkills, slot.requiredSkills) &&
               Objects.equals(optionalSkills, slot.optionalSkills);
    }

    /**
     * @brief Computes the hash code based on all fields.
     *
     * @return The hash code value.
     */
    @Override
    public int hashCode() {
        return Objects.hash(startTime, endTime, locationId, requiredSkills, optionalSkills);
    }

    /**
     * @brief Returns a string representation of this slot.
     *
     * @details Includes start time, end time, location ID, required skills, and optional skills.
     *
     * @return A human-readable string describing the slot.
     */
    @Override
    public String toString() {
        return "Slot{" +
                "startTime=" + startTime +
                ", endTime=" + endTime +
                ", locationId=" + locationId +
                ", requiredSkills=" + requiredSkills +
                ", optionalSkills=" + optionalSkills +
                '}';
    }
}
