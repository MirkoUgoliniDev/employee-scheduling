package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code location_skills} bridge (location ↔ skill).
 *
 * @details skill_type_id: 1=required, 2=optional (skill_type table). Flat columns without
 *          @ManyToOne: during incremental migration the bridge remains an explicit association,
 *          as in the legacy code; joins with skills use JPQL (theta join) where names/order are needed.
 */
@Entity
@Table(name = "location_skills")
public class LocationSkillEntity extends PanacheEntityBase {

    /** Required skill type (mandatory for the shift). */
    public static final int TYPE_REQUIRED = 1;
    /** Optional skill type (preferred). */
    public static final int TYPE_OPTIONAL = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "location_id", nullable = false)
    public int locationId;

    @Column(name = "skill_id", nullable = false)
    public int skillId;

    @Column(name = "skill_type_id", nullable = false)
    public int skillTypeId;
}
