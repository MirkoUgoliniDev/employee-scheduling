package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * @brief Turns an assignment's staffing requirements into concrete positions.
 *
 * @details Pure and database-free on purpose: "1 anaesthetist + 2 MFAs" must become exactly
 *          three positions with the right roles, and that rule is worth a unit test without a
 *          Quarkus container. The service only persists what this class decides.
 *
 *          <p>A <b>position</b> maps one-to-one onto a {@code shifts} row, because Timefold
 *          assigns at most one employee per planning entity. The churn key identifies a position
 *          across regenerations, so editing an assignment's duration keeps the employees already
 *          booked instead of re-planning the whole team.
 */
public final class AssignmentMaterializer {

    public static final String ROLE_PHYSICIAN = "PHYSICIAN";
    public static final String ROLE_MEDICAL_ASSISTANT = "MEDICAL_ASSISTANT";

    /** Roles accepted by the API; anything else is rejected instead of silently normalised. */
    public static final List<String> ROLES = List.of(ROLE_PHYSICIAN, ROLE_MEDICAL_ASSISTANT);

    /** Assignment statuses that still produce open positions. */
    public static final List<String> PLANNED_STATUSES = List.of("FIXED", "FLEXIBLE", "RESCHEDULED");

    private static final DateTimeFormatter DB_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AssignmentMaterializer() {
    }

    /** @brief One staffing line: a role, an optional qualification, and how many people. */
    public record Requirement(String role, Integer skillId, int headcount) {
    }

    /** @brief One concrete team slot. */
    public record Position(String role, Integer skillId) {

        /**
         * @brief Identity of the slot across regenerations: role plus qualification.
         * @details The occurrence index is deliberately <b>not</b> part of the key: three MFAs
         *          of the same qualification are interchangeable, so keeping "some MFA" is
         *          enough to avoid churn. Pairing is done in list order by the service.
         */
        public String churnKey() {
            return role + "|" + (skillId == null ? "" : skillId);
        }
    }

    /**
     * @brief Expands requirements into positions, one per required head.
     *
     * @details Requirements with a blank role or a headcount below 1 are skipped rather than
     *          throwing: the REST layer validates them and returns a translatable error code,
     *          while the solver-facing path must never fail on data it did not write itself.
     */
    public static List<Position> materialize(List<Requirement> requirements) {
        List<Position> positions = new ArrayList<>();
        if (requirements == null) {
            return positions;
        }
        for (Requirement requirement : requirements) {
            if (requirement == null || requirement.role() == null || requirement.role().isBlank()) {
                continue;
            }
            for (int i = 0; i < requirement.headcount(); i++) {
                positions.add(new Position(requirement.role().trim(), requirement.skillId()));
            }
        }
        return positions;
    }

    /** @brief Start of the visit, from "yyyy-MM-dd" plus "HH:mm". */
    public static LocalDateTime startAt(String assignmentDate, String startTime) {
        return LocalDate.parse(assignmentDate).atTime(LocalTime.parse(startTime));
    }

    /** @brief End of the visit: start plus the configurable duration. */
    public static LocalDateTime endAt(String assignmentDate, String startTime, int durationMinutes) {
        return startAt(assignmentDate, startTime).plusMinutes(durationMinutes);
    }

    /** @brief Formats a date-time the way the {@code shifts} table stores it. */
    public static String toDbDateTime(LocalDateTime value) {
        return value.format(DB_FORMAT);
    }
}
