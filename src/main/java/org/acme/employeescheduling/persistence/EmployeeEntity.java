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

/**
 * @brief JPA/Panache entity for the {@code employees} table.
 *
 * @details Actual schema: id PK AUTOINCREMENT, code TEXT UNIQUE, first/last_name TEXT,
 *          structure_id INTEGER default 1, active INTEGER 0/1, email TEXT default ''.
 *          Skills and dates live in the {@link EmployeeSkillEntity}/{@link EmployeeDateEntity}
 *          bridges; the Employee DTO is composed in the resource (its lists have different
 *          shapes for list versus detail views).
 */
@Entity
@Table(name = "employees")
public class EmployeeEntity extends PanacheEntityBase {

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
}
