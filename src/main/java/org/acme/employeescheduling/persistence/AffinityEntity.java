package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.acme.employeescheduling.dto.SpecialistAffinity;

/**
 * @brief JPA/Panache entity for the {@code operator_specialist_affinity} table.
 *
 * @details Sparse table: one row for each NON-neutral Employee-Specialist pair, with
 *          UNIQUE(operator_id, specialist_id). type: 2=avoid, 3=incompatible
 *          (constants in {@link SpecialistAffinity}).
 */
@Entity
@Table(name = "operator_specialist_affinity",
       uniqueConstraints = @UniqueConstraint(columnNames = { "operator_id", "specialist_id" }))
public class AffinityEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "operator_id", nullable = false)
    public int operatorId;

    @Column(name = "specialist_id", nullable = false)
    public int specialistId;

    @Column(name = "type", nullable = false)
    public int type;

    /** @brief Converts to the REST DTO (internal ID not exposed, as in the legacy code). */
    public SpecialistAffinity toDto() {
        return new SpecialistAffinity(operatorId, specialistId, type);
    }
}
