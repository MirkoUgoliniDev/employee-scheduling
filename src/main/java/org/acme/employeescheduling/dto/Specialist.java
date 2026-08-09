package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Represents a Specialist (the doctor who manages the clinic).
 *
 * @details Simple record associated with a structure, with the same set of fields as an
 *          Employee but without skills, dates, or solver data:
 *          code, first name, last name, email, and active status.
 */
public class Specialist {

    /** @brief Unique identifier of the specialist. */
    @JsonProperty("id")
    private int id;

    /** @brief Short identifying code (e.g., "SPE001"). */
    @JsonProperty("code")
    private String code;

    /** @brief Specialist's first name. */
    @JsonProperty("firstName")
    private String firstName;

    /** @brief Specialist's last name. */
    @JsonProperty("lastName")
    private String lastName;

    /** @brief Specialist's email address. */
    @JsonProperty("email")
    private String email;

    /** @brief Full name (first name + last name), computed by the backend. */
    private String fullName;

    /** @brief If false, the specialist is disabled. */
    @JsonProperty("active")
    private boolean active = true;

    /** @brief Default constructor required by Jackson. */
    public Specialist() {}

    public Specialist(int id, String code, String firstName, String lastName, String email, boolean active) {
        this.id = id;
        this.code = code;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.active = active;
        this.fullName = firstName + " " + lastName;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    @Override
    public String toString() {
        return "Specialist{id=" + id + ", code='" + code + "', firstName='" + firstName
                + "', lastName='" + lastName + "', email='" + email + "', active=" + active + '}';
    }
}
