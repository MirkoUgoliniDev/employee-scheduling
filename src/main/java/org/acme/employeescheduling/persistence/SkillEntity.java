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

import org.acme.employeescheduling.dto.Skill;

/**
 * @brief JPA/Panache entity for the {@code skills} table (incremental ORM migration).
 *
 * @details Actual schema: id INTEGER PK, structure_id INTEGER NOT NULL,
 *          name VARCHAR(255) NOT NULL, skill_order INTEGER (nullable),
 *          active INTEGER NOT NULL DEFAULT 1, with name uniqueness <b>per structure</b>. The Java
 *          field is named {@code skillOrder} because "order" is reserved in HQL; JSON remains
 *          "order" through the DTO
 *          {@link Skill}.
 *
 *          <p>Each skill belongs to <b>exactly one structure</b> (migration V5). Previously the
 *          catalog was global, and a {@code structure_skills} bridge only determined which skills
 *          a structure saw: renaming one changed it for all, and two structures could not have
 *          different "Nurse" skills because the name was globally unique.</p>
 */
@Entity
@Table(name = "skills")
public class SkillEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    /** @brief Owning structure: skills are never shared. */
    @Column(name = "structure_id", nullable = false)
    public Integer structureId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "skill_order")
    public Integer skillOrder;

    /** INTEGER 0/1 in the DB (SQLite convention): without the explicit JdbcTypeCode, schema
     *  validation reports a boolean/INTEGER mismatch. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * @brief Converts to the skill-catalog REST DTO.
     * @details Fixed {@code used=true}: legacy getSkills() parity, which does not calculate actual
     *          use in this endpoint (the flag is truly populated only in per-employee/per-shift catalogs).
     */
    public Skill toDto() {
        return new Skill(id, name, skillOrder != null ? skillOrder : 0, true, active);
    }

    /** @brief Copies fields from the DTO (excluding ID). */
    public void applyDto(Skill dto) {
        this.name = dto.getName();
        this.skillOrder = dto.getOrder();
        this.active = dto.isActive();
    }
}
