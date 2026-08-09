package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.acme.employeescheduling.dto.Localizzazione;

/**
 * @brief JPA/Panache entity for the {@code localizzazioni} table (per-entity translations).
 *
 * @details One row per (entity_type, entity_id, field_name, language_id), UNIQUE across the four.
 *          Stores both UI-label translations (entity_type='labels', field_name='value') and
 *          dynamic names (skills/locations, field_name='name').
 */
@Entity
@Table(name = "localizzazioni",
       uniqueConstraints = @UniqueConstraint(columnNames = { "entity_type", "entity_id", "field_name", "language_id" }))
public class LocalizzazioneEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "entity_type", nullable = false)
    public String entityType;

    @Column(name = "entity_id", nullable = false)
    public int entityId;

    @Column(name = "field_name", nullable = false)
    public String fieldName;

    @Column(name = "language_id", nullable = false)
    public int languageId;

    @Column(name = "value", nullable = false)
    public String value;

    /** @brief Converts to the REST DTO. */
    public Localizzazione toDto() {
        return new Localizzazione(id, entityType, entityId, fieldName, languageId, value);
    }
}
