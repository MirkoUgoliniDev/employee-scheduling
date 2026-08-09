package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Compatibility relationship between an Employee and a Specialist.
 *
 * @details Row in the sparse `operator_specialist_affinity` table: it exists only for
 *          NON-neutral pairs. The semantics of `type` mirror those of the
 *          date types (employee_date_type):
 *          1 = preferred (reserved, unused), 2 = avoid (soft),
 *          3 = incompatible (hard).
 */
public class SpecialistAffinity {

    /** @brief Type: avoid (soft solver constraint). */
    public static final int TYPE_AVOID = 2;
    /** @brief Type: incompatible (hard solver constraint). */
    public static final int TYPE_INCOMPATIBLE = 3;

    /** @brief Operator ID (employees.id). */
    @JsonProperty("operatorId")
    private int operatorId;

    /** @brief Specialist ID (specialists.id). */
    @JsonProperty("specialistId")
    private int specialistId;

    /** @brief Level: 2=avoid, 3=incompatible (1 reserved). */
    @JsonProperty("type")
    private int type;

    /** @brief Default constructor required by Jackson. */
    public SpecialistAffinity() {}

    public SpecialistAffinity(int operatorId, int specialistId, int type) {
        this.operatorId = operatorId;
        this.specialistId = specialistId;
        this.type = type;
    }

    public int getOperatorId() { return operatorId; }
    public void setOperatorId(int operatorId) { this.operatorId = operatorId; }

    public int getSpecialistId() { return specialistId; }
    public void setSpecialistId(int specialistId) { this.specialistId = specialistId; }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }

    @Override
    public String toString() {
        return "SpecialistAffinity{operatorId=" + operatorId + ", specialistId=" + specialistId + ", type=" + type + '}';
    }
}
