package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code employee_skills} bridge (employee ↔ skill).
 * @details Flat columns without @ManyToOne, as in {@link LocationSkillEntity}.
 */
@Entity
@Table(name = "employee_skills")
public class EmployeeSkillEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "employee_id", nullable = false)
    public int employeeId;

    @Column(name = "skill_id", nullable = false)
    public int skillId;
}
