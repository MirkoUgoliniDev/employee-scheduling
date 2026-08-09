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

import org.acme.employeescheduling.dto.Specialist;

/**
 * @brief JPA/Panache entity for the {@code specialists} table (ORM migration).
 *
 * @details Actual schema: id PK AUTOINCREMENT, code TEXT UNIQUE, first/last_name TEXT,
 *          structure_id INTEGER default 1, active INTEGER 0/1, email TEXT default ''.
 *          REST contract unchanged through the {@link Specialist} DTO (which does not expose
 *          structure_id: the structure filter travels as a query parameter).
 */
@Entity
@Table(name = "specialists")
public class SpecialistEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "code", nullable = false, unique = true)
    public String code;

    @Column(name = "first_name", nullable = false)
    public String firstName;

    @Column(name = "last_name", nullable = false)
    public String lastName;

    @Column(name = "structure_id", nullable = false)
    public int structureId = 1;

    /** INTEGER 0/1 in the DB (SQLite convention) — see SkillEntity. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "email", nullable = false)
    public String email = "";

    /** @brief Converts to the REST DTO. */
    public Specialist toDto() {
        return new Specialist(id, code, firstName, lastName, email, active);
    }

    /**
     * @brief Copies fields from the DTO with legacy normalization: capitalized first/last names,
     *        null email -> "".
     */
    public void applyDto(Specialist dto) {
        this.code = dto.getCode();
        this.firstName = capitalize(dto.getFirstName());
        this.lastName = capitalize(dto.getLastName());
        this.email = dto.getEmail() == null ? "" : dto.getEmail();
        this.active = dto.isActive();
    }

    /** @brief Uppercase first letter, lowercase remainder (legacy parity). */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}
