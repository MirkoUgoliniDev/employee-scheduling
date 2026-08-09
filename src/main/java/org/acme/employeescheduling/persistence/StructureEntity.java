package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.acme.employeescheduling.dto.Structure;

/**
 * @brief JPA/Panache entity for the {@code structures} table (first entity in the incremental
 *        ORM migration — ORM branch).
 *
 * @details Maps the existing schema 1:1 (id AUTOINCREMENT, name/address/phone TEXT NOT NULL with
 *          default ''). The schema is NOT generated from the model (schema-management.strategy=none):
 *          the existing DB remains the source of truth. The REST contract continues to use the
 *          {@link Structure} DTO: this class is a persistence detail and never leaves the backend.
 */
@Entity
@Table(name = "structures")
public class StructureEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "address", nullable = false)
    public String address = "";

    @Column(name = "phone", nullable = false)
    public String phone = "";

    /** @brief Converts the entity to the REST DTO (unchanged JSON contract). */
    public Structure toDto() {
        return new Structure(id, name, address, phone);
    }

    /** @brief Copies DTO fields, normalizing null address/phone to "" (legacy parity). */
    public void applyDto(Structure dto) {
        this.name = dto.getName();
        this.address = dto.getAddress() != null ? dto.getAddress() : "";
        this.phone = dto.getPhone() != null ? dto.getPhone() : "";
    }
}
