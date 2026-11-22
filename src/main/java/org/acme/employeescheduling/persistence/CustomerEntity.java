package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import org.acme.employeescheduling.dto.Customer;

/**
 * @brief JPA/Panache entity for the {@code customers} table.
 *
 * @details Schema (V9): id PK, structure_id, code UNIQUE per structure, name, address, phone,
 *          active INTEGER 0/1, default_start_time "HH:mm", default_duration_minutes,
 *          default_skill_id (nullable FK), recurrence_pattern, restrictions. The location
 *          relation lives on {@code locations.customer_id}, so a customer read has to query
 *          it separately.
 */
@Entity
@Table(name = "customers")
public class CustomerEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "structure_id", nullable = false)
    public int structureId = 1;

    @Column(name = "code", nullable = false)
    public String code;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "address", nullable = false)
    public String address = "";

    @Column(name = "phone", nullable = false)
    public String phone = "";

    /** INTEGER 0/1 in the DB (SQLite convention) — see SkillEntity. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "default_start_time", nullable = false)
    public String defaultStartTime = "08:00";

    @Column(name = "default_duration_minutes", nullable = false)
    public int defaultDurationMinutes = 480;

    @Column(name = "default_skill_id")
    public Integer defaultSkillId;

    @Column(name = "recurrence_pattern", nullable = false)
    public String recurrencePattern = "";

    @Column(name = "restrictions", nullable = false)
    public String restrictions = "";

    /** @brief Converts to the REST DTO; {@code locationIds} is filled by the resource. */
    public Customer toDto() {
        Customer dto = new Customer();
        dto.setId(id);
        dto.setStructureId(structureId);
        dto.setCode(code);
        dto.setName(name);
        dto.setAddress(address);
        dto.setPhone(phone);
        dto.setActive(active);
        dto.setDefaultStartTime(defaultStartTime);
        dto.setDefaultDurationMinutes(defaultDurationMinutes);
        dto.setDefaultSkillId(defaultSkillId);
        dto.setRecurrencePattern(recurrencePattern);
        dto.setRestrictions(restrictions);
        return dto;
    }

    /** @brief Copies the editable fields from the DTO (id and structureId are set by the caller). */
    public void applyDto(Customer dto) {
        this.code = dto.getCode();
        this.name = dto.getName();
        this.address = dto.getAddress();
        this.phone = dto.getPhone();
        this.active = dto.isActive();
        this.defaultStartTime = dto.getDefaultStartTime();
        this.defaultDurationMinutes = dto.getDefaultDurationMinutes();
        this.defaultSkillId = dto.getDefaultSkillId();
        this.recurrencePattern = dto.getRecurrencePattern();
        this.restrictions = dto.getRestrictions();
    }
}
