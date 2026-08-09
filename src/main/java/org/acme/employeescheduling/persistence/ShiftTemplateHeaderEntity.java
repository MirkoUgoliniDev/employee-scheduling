package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code shift_template_headers} table (named saved templates,
 *        per structure).
 * @details created_at is textual "yyyy-MM-dd HH:mm:ss" (legacy datetime('now','localtime'));
 *          headers are still created on the legacy path (addSavedTemplateFromWeek), while they
 *          are read/updated/deleted here.
 */
@Entity
@Table(name = "shift_template_headers")
public class ShiftTemplateHeaderEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "structure_id", nullable = false)
    public int structureId;

    @Column(name = "description", nullable = false)
    public String description = "";

    @Column(name = "created_at", nullable = false)
    public String createdAt = "";
}
