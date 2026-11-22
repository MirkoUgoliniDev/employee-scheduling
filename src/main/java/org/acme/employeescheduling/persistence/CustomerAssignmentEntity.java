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
 * @brief JPA/Panache entity for the {@code customer_assignments} table.
 *
 * @details Schema (V9): id PK, structure_id, customer_id, location_id, title, service,
 *          assignment_date "yyyy-MM-dd", start_time "HH:mm", duration_minutes, priority 1-3,
 *          status, fixed_time INTEGER 0/1, notes, series_id, created_at. The team composition
 *          lives in {@link AssignmentRequirementEntity}; the materialised positions are plain
 *          {@link ShiftEntity} rows pointing back through {@code shifts.assignment_id}.
 */
@Entity
@Table(name = "customer_assignments")
public class CustomerAssignmentEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "structure_id", nullable = false)
    public int structureId = 1;

    @Column(name = "customer_id", nullable = false)
    public int customerId;

    @Column(name = "location_id", nullable = false)
    public int locationId;

    @Column(name = "title", nullable = false)
    public String title = "";

    @Column(name = "service", nullable = false)
    public String service = "";

    /** "yyyy-MM-dd": fixed width, so lexicographic and chronological order coincide. */
    @Column(name = "assignment_date", nullable = false)
    public String assignmentDate;

    /** "HH:mm". */
    @Column(name = "start_time", nullable = false)
    public String startTime = "08:00";

    @Column(name = "duration_minutes", nullable = false)
    public int durationMinutes = 480;

    @Column(name = "priority", nullable = false)
    public int priority = 3;

    @Column(name = "status", nullable = false)
    public String status = "FLEXIBLE";

    /** INTEGER 0/1 in the DB (SQLite convention) — see SkillEntity. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "fixed_time", nullable = false)
    public boolean fixedTime = false;

    @Column(name = "notes", nullable = false)
    public String notes = "";

    @Column(name = "series_id")
    public Integer seriesId;

    @Column(name = "created_at", nullable = false)
    public String createdAt = "";
}
