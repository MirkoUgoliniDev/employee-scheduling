package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code shift_template_skills} bridge (template ↔ skill).
 * @details skill_type_id: 1=required, 2=optional. Flat columns like the other bridges.
 */
@Entity
@Table(name = "shift_template_skills")
public class ShiftTemplateSkillEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "template_id", nullable = false)
    public int templateId;

    @Column(name = "skill_id", nullable = false)
    public int skillId;

    @Column(name = "skill_type_id", nullable = false)
    public int skillTypeId;
}
