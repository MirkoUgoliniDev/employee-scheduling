package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.acme.employeescheduling.dto.Label;

/**
 * @brief JPA/Panache entity for the {@code labels} table (static i18n keys).
 *
 * @details Actual schema: id PK AUTOINCREMENT, key TEXT NOT NULL UNIQUE, description TEXT NOT NULL.
 *          The Java field is named {@code labelKey} because "key" is reserved in HQL; JSON remains
 *          "key" through the {@link Label} DTO.
 */
@Entity
@Table(name = "labels")
public class LabelEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "key", nullable = false, unique = true)
    public String labelKey;

    @Column(name = "description", nullable = false)
    public String description;

    /** @brief Converts to the REST DTO (without translation). */
    public Label toDto() {
        return new Label(id, labelKey, description);
    }

    /** @brief Copies fields from the DTO (excluding ID). */
    public void applyDto(Label dto) {
        this.labelKey = dto.getKey();
        this.description = dto.getDescription();
    }
}
