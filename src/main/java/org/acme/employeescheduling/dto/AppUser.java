package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief REST DTO for managing application users.
 *
 * @details Does not include the password hash: it is never sent to the client. Password creation
 *          and changes use dedicated fields (rawPassword), which the backend encrypts with bcrypt.
 */
public class AppUser {

    @JsonProperty("id")
    private int id;

    @JsonProperty("username")
    private String username;

    @JsonProperty("role")
    private String role;

    @JsonProperty("active")
    private Boolean active = null;

    @JsonProperty("displayName")
    private String displayName;

    @JsonProperty("email")
    private String email;

    @JsonProperty("createdAt")
    private String createdAt;

    @JsonProperty("lastLoginAt")
    private String lastLoginAt;

    @JsonProperty("rawPassword")
    private String rawPassword;

    public AppUser() {}

    public AppUser(int id, String username, String role, boolean active,
                   String displayName, String createdAt, String lastLoginAt) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.active = active;
        this.displayName = displayName;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
    }
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isActive() { return Boolean.TRUE.equals(active); }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(String lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public String getRawPassword() { return rawPassword; }
    public void setRawPassword(String rawPassword) { this.rawPassword = rawPassword; }
}
