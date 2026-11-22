package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code assignment_requirements} table.
 *
 * @details One row per role/qualification combination with the headcount it needs, so the team
 *          composition stays dynamic: nothing is bound to a person here, only to a role. Each
 *          headcount unit becomes one shift row when the assignment is materialised.
 */
@Entity
@Table(name = "assignment_requirements")
public class AssignmentRequirementEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "assignment_id", nullable = false)
    public int assignmentId;

    @Column(name = "role", nullable = false)
    public String role = "MEDICAL_ASSISTANT";

    /** Optional qualification (skills.id); null means "role is enough". */
    @Column(name = "skill_id")
    public Integer skillId;

    @Column(name = "headcount", nullable = false)
    public int headcount = 1;
}
