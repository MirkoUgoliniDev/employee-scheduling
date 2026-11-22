package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code shift_skills} bridge (shift ↔ skill).
 * @details skill_type_id: 1=required, 2=optional. Flat columns like the other bridges.
 */
@Entity
@Table(name = "shift_skills")
public class ShiftSkillEntity extends PanacheEntityBase {

    /** Required skill type (mandatory for the shift). */
    public static final int TYPE_REQUIRED = 1;
    /** Optional skill type (preferred). */
    public static final int TYPE_OPTIONAL = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "shift_id", nullable = false)
    public int shiftId;

    @Column(name = "skill_id", nullable = false)
    public int skillId;

    @Column(name = "skill_type_id", nullable = false)
    public int skillTypeId;
}
