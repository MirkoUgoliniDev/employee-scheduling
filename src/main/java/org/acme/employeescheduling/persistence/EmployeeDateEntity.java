package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.acme.employeescheduling.dto.EmployeeDate;

/**
 * @brief JPA/Panache entity for the {@code employee_dates} table (employee dates).
 *
 * @details date_type_id: 1=Desired, 2=Undesired, 3=Unavailable. date_start/date_end are
 *          DATETIME columns (TEXT in SQLite), but legacy code wrote both "yyyy-MM-dd" (setDate)
 *          and full timestamps: map them as String and parse leniently (same flexibility as the
 *          xerial driver's rs.getTimestamp).
 */
@Entity
@Table(name = "employee_dates")
public class EmployeeDateEntity extends PanacheEntityBase {

    public static final int TYPE_DESIRED = 1;
    public static final int TYPE_UNDESIRED = 2;
    public static final int TYPE_UNAVAILABLE = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "employee_id", nullable = false)
    public int employeeId;

    @Column(name = "date_start", nullable = false)
    public String dateStart;

    @Column(name = "date_end", nullable = false)
    public String dateEnd;

    @Column(name = "date_type_id", nullable = false)
    public int dateTypeId;

    /** @brief Converts to the REST DTO (lenient date parsing: date-only or date+time). */
    public EmployeeDate toDto() {
        EmployeeDate dto = new EmployeeDate();
        dto.setId(id);
        dto.setEmployeeId(employeeId);
        dto.setDateTypeId(dateTypeId);
        dto.setDateStart(parseDbDateTime(dateStart));
        dto.setDateEnd(parseDbDateTime(dateEnd));
        return dto;
    }

    /**
     * @brief Lenient parsing of DATETIME values saved by legacy code: "yyyy-MM-dd" (setDate) or
     *        "yyyy-MM-dd HH:mm:ss[.SSS]" (setTimestamp/text).
     */
    public static LocalDateTime parseDbDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() == 10) return LocalDate.parse(t).atStartOfDay();
        return LocalDateTime.parse(t.replace(' ', 'T'));
    }
}
