package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code assignment_exceptions} table.
 *
 * @details One row per occurrence date the planner deviated from the series. Keeping the
 *          deviation here instead of rewriting the series is what makes "cancel this Tuesday,
 *          keep every other Tuesday" possible, and it is what stops a re-generation of the
 *          period from resurrecting the cancelled visit: the generator skips every date that has
 *          an exception row.
 */
@Entity
@Table(name = "assignment_exceptions")
public class AssignmentExceptionEntity extends PanacheEntityBase {

    public static final String CANCELLED = "CANCELLED";
    public static final String MOVED = "MOVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "series_id", nullable = false)
    public int seriesId;

    /** ISO date of the occurrence as the series defined it, not the moved-to date. */
    @Column(name = "occurrence_date", nullable = false)
    public String occurrenceDate = "";

    @Column(name = "kind", nullable = false)
    public String kind = CANCELLED;

    @Column(name = "new_date", nullable = false)
    public String newDate = "";

    @Column(name = "new_start_time", nullable = false)
    public String newStartTime = "";

    @Column(name = "notes", nullable = false)
    public String notes = "";

    @Column(name = "created_at", nullable = false)
    public String createdAt = "";
}
