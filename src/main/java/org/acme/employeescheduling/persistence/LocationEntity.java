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

import org.acme.employeescheduling.dto.Location;

/**
 * @brief JPA/Panache entity for the {@code locations} table (locations/clinics).
 *
 * @details Actual schema: id PK AUTOINCREMENT, name TEXT NOT NULL, l_order INTEGER (nullable),
 *          code TEXT (UNIQUE through idx_locations_code — not declared on the annotation to avoid
 *          confusing schema validation),
 *          structure_id INTEGER default 1, active INTEGER 0/1, specialist_id
 *          INTEGER nullable. The Java field is {@code displayOrder} because "order" is reserved
 *          in HQL; JSON remains "order" through the {@link Location} DTO. Required/optional skills
 *          live in the {@link LocationSkillEntity} bridge.
 */
@Entity
@Table(name = "locations")
public class LocationEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "l_order")
    public Integer displayOrder;

    @Column(name = "code")
    public String code;

    @Column(name = "structure_id", nullable = false)
    public int structureId = 1;

    /** INTEGER 0/1 in the DB (SQLite convention) — see SkillEntity. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "specialist_id")
    public Integer specialistId;

    /** @brief Converts to the REST DTO (without skill lists; the caller populates them). */
    public Location toDto() {
        Location dto = new Location();
        dto.setId(id);
        dto.setCode(code);
        dto.setOrder(displayOrder != null ? displayOrder : 0);
        dto.setName(name);
        dto.setActive(active);
        dto.setSpecialistId(specialistId);
        return dto;
    }

    /**
     * @brief Copies fields from the DTO (excluding ID and structureId: updates do not change the
     *        structure, for legacy parity).
     */
    public void applyDto(Location dto) {
        this.code = dto.getCode();
        this.name = dto.getName();
        this.displayOrder = dto.getOrder();
        this.active = dto.isActive();
        this.specialistId = dto.getSpecialistId();
    }
}
