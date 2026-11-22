package org.acme.employeescheduling.persistence;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * @brief JPA/Panache entity for the {@code employee_locations} bridge (eligible locations).
 *
 * @details <b>No rows for an employee means unrestricted</b>, which is what every installation
 *          created before V9 implicitly assumes: adding the table must not suddenly declare all
 *          existing staff unqualified for all their locations. Adding a single row narrows the
 *          set, and the {@code locationEligibility} constraint then enforces it.
 */
@Entity
@Table(name = "employee_locations")
public class EmployeeLocationEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "employee_id", nullable = false)
    public int employeeId;

    @Column(name = "location_id", nullable = false)
    public int locationId;

    /** @brief Eligible location IDs of one employee (empty means unrestricted). */
    public static Set<Integer> locationsOf(int employeeId) {
        return find("employeeId = ?1", employeeId).<EmployeeLocationEntity>list().stream()
                .map(row -> row.locationId).collect(Collectors.toSet());
    }

    /** @brief Eligible location IDs of every employee, keyed by employee ID. */
    public static java.util.Map<Integer, Set<Integer>> allByEmployee() {
        List<EmployeeLocationEntity> rows = listAll();
        return rows.stream().collect(Collectors.groupingBy(row -> row.employeeId,
                Collectors.mapping(row -> row.locationId, Collectors.toSet())));
    }
}
