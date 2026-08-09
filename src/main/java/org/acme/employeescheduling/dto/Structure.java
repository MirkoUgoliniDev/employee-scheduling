package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Represents a top-level organizational structure (e.g., a clinic or hospital branch).
 *
 * @details Each structure groups its own locations (clinics), employees,
 *          and shifts. The "Default" structure (id=1) is seeded automatically.
 */
public class Structure {

    @JsonProperty("id")
    private int id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("address")
    private String address;

    @JsonProperty("phone")
    private String phone;

    public Structure() {}

    public Structure(int id, String name, String address, String phone) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.phone = phone;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    @Override
    public String toString() {
        return "Structure{id=" + id + ", name='" + name + "', address='" + address + "', phone='" + phone + "'}";
    }
}
