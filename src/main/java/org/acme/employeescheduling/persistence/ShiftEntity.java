package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * @brief JPA/Panache entity for the {@code shifts} table.
 *
 * @details start_time/end_time are textual "yyyy-MM-dd HH:mm:ss" DATETIME values (legacy
 *          dbFormatter): map them as String — fixed width makes lexicographic and temporal order
 *          equivalent, matching windowed-query conventions. employee_id nullable (unassigned
 *          shift), pinned INTEGER 0/1.
 */
@Entity
@Table(name = "shifts")
public class ShiftEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "location_id", nullable = false)
    public int locationId;

    @Column(name = "start_time", nullable = false)
    public String startTime;

    @Column(name = "end_time", nullable = false)
    public String endTime;

    @Column(name = "employee_id")
    public Integer employeeId;

    /** INTEGER 0/1 in the DB (SQLite convention) — see SkillEntity. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "pinned", nullable = false)
    public boolean pinned = false;

    /**
     * @brief Revision counter incremented by Hibernate on every ORM write.
     *
     * @details The solver reads shifts when solving starts, and the user saves the solution minutes
     *          later. If someone moves a shift or changes its requirements in the meantime, saving
     *          used to assign the employee to a shift different from the one the solver considered —
     *          no error or warning. The client sends back the revision it saw, and saving refuses
     *          to proceed if it does not match.
     */
    @Version
    @Column(name = "version", nullable = false)
    public int version = 0;
}
