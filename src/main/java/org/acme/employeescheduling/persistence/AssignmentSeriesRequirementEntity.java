package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code assignment_series_requirements} table.
 *
 * @details The team composition of a series, copied into every occurrence it generates. Mirrors
 *          {@link AssignmentRequirementEntity} so an occurrence can be created through the normal
 *          assignment write path without translation.
 */
@Entity
@Table(name = "assignment_series_requirements")
public class AssignmentSeriesRequirementEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "series_id", nullable = false)
    public int seriesId;

    @Column(name = "role", nullable = false)
    public String role = "MEDICAL_ASSISTANT";

    @Column(name = "skill_id")
    public Integer skillId;

    @Column(name = "headcount", nullable = false)
    public int headcount = 1;
}
