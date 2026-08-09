package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code shift_templates} table.
 *
 * @details day_of_week: 0=Monday..6=Sunday. start/end_time are textual "HH:mm" times.
 *          header_id NULL = structure's "working" template; non-null = row in a named saved
 *          template (shift_template_headers).
 */
@Entity
@Table(name = "shift_templates")
public class ShiftTemplateEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "structure_id", nullable = false)
    public int structureId;

    @Column(name = "day_of_week", nullable = false)
    public int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    public String startTime;

    @Column(name = "end_time", nullable = false)
    public String endTime;

    @Column(name = "location_id", nullable = false)
    public int locationId;

    @Column(name = "header_id")
    public Integer headerId;
}
