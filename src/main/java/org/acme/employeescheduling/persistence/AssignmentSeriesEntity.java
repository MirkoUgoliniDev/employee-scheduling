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
 * @brief JPA/Panache entity for the {@code assignment_series} table.
 *
 * @details A series is a template, never a plan: it holds the recurrence rule (pattern type, ISO
 *          weekdays, nth weekday, effective range) and the visit parameters, and the generator
 *          turns it into ordinary {@code customer_assignments} rows carrying {@code series_id}.
 *          Nothing in the solver knows about series — that is deliberate, so a generated
 *          occurrence is indistinguishable from a hand-made one and can still be moved, pinned or
 *          cancelled on its own.
 */
@Entity
@Table(name = "assignment_series")
public class AssignmentSeriesEntity extends PanacheEntityBase {

    /** Every week on the listed weekdays. */
    public static final String WEEKLY = "WEEKLY";
    /** Every second week, anchored on {@code effectiveFrom}. */
    public static final String BIWEEKLY = "BIWEEKLY";
    /** The nth listed weekday of each month; {@code nth} says which one. */
    public static final String MONTHLY_NTH_WEEKDAY = "MONTHLY_NTH_WEEKDAY";

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

    /** "HH:mm" — the time of day every occurrence starts at. */
    @Column(name = "start_time", nullable = false)
    public String startTime = "08:00";

    @Column(name = "duration_minutes", nullable = false)
    public int durationMinutes = 480;

    /** 1 = high … 3 = low, same scale as the assignment priority. */
    @Column(name = "priority", nullable = false)
    public int priority = 2;

    /** INTEGER 0/1 in the DB (SQLite convention) — see SkillEntity. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "fixed_time", nullable = false)
    public boolean fixedTime;

    @Column(name = "notes", nullable = false)
    public String notes = "";

    @Column(name = "pattern_type", nullable = false)
    public String patternType = WEEKLY;

    /** ISO weekday numbers 1..7 (Monday..Sunday), comma separated. */
    @Column(name = "weekdays", nullable = false)
    public String weekdays = "1";

    @Column(name = "nth", nullable = false)
    public int nth;

    @Column(name = "effective_from", nullable = false)
    public String effectiveFrom = "";

    @Column(name = "effective_to", nullable = false)
    public String effectiveTo = "";

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "created_at", nullable = false)
    public String createdAt = "";
}
