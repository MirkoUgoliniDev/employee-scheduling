package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code travel_times} table (location × location minutes).
 *
 * @details No routing service is called: the planner maintains the minutes, and a later
 *          integration only has to fill this table. Entries are stored per direction, so a
 *          lookup is one indexed read and asymmetric travel (one-way streets, ferries) is
 *          representable without a schema change.
 */
@Entity
@Table(name = "travel_times")
public class TravelTimeEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "structure_id", nullable = false)
    public int structureId = 1;

    @Column(name = "from_location_id", nullable = false)
    public int fromLocationId;

    @Column(name = "to_location_id", nullable = false)
    public int toLocationId;

    @Column(name = "travel_minutes", nullable = false)
    public int travelMinutes;

    /** @brief Travel minutes between two locations, or 0 when the matrix has no entry. */
    public static int minutesBetween(int structureId, int fromLocationId, int toLocationId) {
        if (fromLocationId == toLocationId) {
            return 0;
        }
        TravelTimeEntity entry = find("structureId = ?1 and fromLocationId = ?2 and toLocationId = ?3",
                structureId, fromLocationId, toLocationId).firstResult();
        return entry == null ? 0 : entry.travelMinutes;
    }
}
