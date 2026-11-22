package org.acme.employeescheduling.rest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.acme.employeescheduling.dto.AssignmentException;
import org.acme.employeescheduling.dto.AssignmentSeries;
import org.acme.employeescheduling.dto.AssignmentSeriesRequirement;
import org.acme.employeescheduling.persistence.AssignmentExceptionEntity;
import org.acme.employeescheduling.persistence.AssignmentRequirementEntity;
import org.acme.employeescheduling.persistence.AssignmentSeriesEntity;
import org.acme.employeescheduling.persistence.AssignmentSeriesRequirementEntity;
import org.acme.employeescheduling.persistence.CustomerAssignmentEntity;
import org.acme.employeescheduling.persistence.CustomerEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.SkillEntity;

/**
 * @brief Turns a recurring-visit template into concrete customer assignments.
 *
 * @details The generator is a top-up, never a rebuild: it looks at which dates of the period
 *          already exist for the series and creates only the missing ones. That single property
 *          carries three requirements at once — running it twice is harmless, extending the
 *          horizon only adds the new weeks, and a re-generation after a cancellation cannot
 *          resurrect the cancelled visit because its date has an exception row.
 *
 *          <p>An occurrence is written through {@link AssignmentService#create}, so it gets the
 *          same materialised positions as a hand-made assignment. There is no second planning
 *          model: a generated visit can be pinned, moved, re-teamed or deleted individually
 *          without touching the series.
 */
@ApplicationScoped
public class AssignmentSeriesService {

    /** Guard against a typo in the horizon: 400 visits is already more than a year of dailies. */
    public static final int MAX_OCCURRENCES = 400;

    @Inject
    AssignmentService assignments;

    // -----------------------------------------------------------------------
    // Occurrence calculation (pure, no persistence)
    // -----------------------------------------------------------------------

    /**
     * @brief Computes the occurrence dates of a rule inside a period.
     *
     * @details The result is the intersection of the requested period and the series' effective
     *          range, so the caller can ask for a month of a year-long series without getting the
     *          year. {@code nth == 5} means <b>the last</b> listed weekday of the month, which
     *          exists in every month (a 5th Friday does not).
     *
     * @throws IllegalArgumentException with a contract code the resource maps to 400
     */
    public static List<LocalDate> occurrences(String patternType, String weekdays, int nth,
            LocalDate effectiveFrom, LocalDate effectiveTo, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("SERIES_RANGE_REQUIRED");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("SERIES_RANGE_INVALID");
        }
        LocalDate lower = effectiveFrom != null && from.isBefore(effectiveFrom) ? effectiveFrom : from;
        LocalDate upper = effectiveTo != null && to.isAfter(effectiveTo) ? effectiveTo : to;
        if (lower.isAfter(upper)) {
            return List.of();
        }
        Set<DayOfWeek> days = parseWeekdays(weekdays);
        String pattern = patternType == null || patternType.isBlank()
                ? AssignmentSeriesEntity.WEEKLY : patternType.trim().toUpperCase();

        List<LocalDate> dates = new ArrayList<>();
        switch (pattern) {
            case AssignmentSeriesEntity.WEEKLY, AssignmentSeriesEntity.BIWEEKLY -> {
                // The biweekly parity is anchored on the effective start, not on the requested
                // period: asking for March and then for April must not shift which week is "on".
                LocalDate anchor = (effectiveFrom == null ? lower : effectiveFrom)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (LocalDate day = lower; !day.isAfter(upper); day = day.plusDays(1)) {
                    if (!days.contains(day.getDayOfWeek())) {
                        continue;
                    }
                    if (AssignmentSeriesEntity.BIWEEKLY.equals(pattern)) {
                        long weeks = ChronoUnit.WEEKS.between(anchor, day.with(
                                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                        if (weeks % 2 != 0) {
                            continue;
                        }
                    }
                    dates.add(day);
                }
            }
            case AssignmentSeriesEntity.MONTHLY_NTH_WEEKDAY -> {
                if (nth < 1 || nth > 5) {
                    throw new IllegalArgumentException("SERIES_NTH_INVALID");
                }
                for (LocalDate cursor = lower.withDayOfMonth(1);
                        !cursor.isAfter(upper.withDayOfMonth(1));
                        cursor = cursor.plusMonths(1)) {
                    for (DayOfWeek day : days) {
                        LocalDate hit = nthWeekdayOfMonth(cursor, day, nth);
                        if (hit != null && !hit.isBefore(lower) && !hit.isAfter(upper)) {
                            dates.add(hit);
                        }
                    }
                }
                dates.sort(null);
            }
            default -> throw new IllegalArgumentException("SERIES_PATTERN_INVALID");
        }
        if (dates.size() > MAX_OCCURRENCES) {
            throw new IllegalArgumentException("SERIES_TOO_MANY_OCCURRENCES");
        }
        return dates;
    }

    /** @brief The nth occurrence of {@code day} in the month of {@code anyDayOfMonth}. */
    static LocalDate nthWeekdayOfMonth(LocalDate anyDayOfMonth, DayOfWeek day, int nth) {
        LocalDate first = anyDayOfMonth.withDayOfMonth(1);
        LocalDate firstHit = first.plusDays((day.getValue() - first.getDayOfWeek().getValue() + 7) % 7);
        if (nth >= 5) {
            // "the last one" — 5th when it exists, 4th when the month only has four.
            LocalDate fifth = firstHit.plusWeeks(4);
            return fifth.getMonth() == first.getMonth() ? fifth : firstHit.plusWeeks(3);
        }
        LocalDate hit = firstHit.plusWeeks(nth - 1L);
        return hit.getMonth() == first.getMonth() ? hit : null;
    }

    static Set<DayOfWeek> parseWeekdays(String weekdays) {
        if (weekdays == null || weekdays.isBlank()) {
            throw new IllegalArgumentException("SERIES_WEEKDAYS_REQUIRED");
        }
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String token : weekdays.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("SERIES_WEEKDAYS_INVALID");
            }
            if (value < 1 || value > 7) {
                throw new IllegalArgumentException("SERIES_WEEKDAYS_INVALID");
            }
            days.add(DayOfWeek.of(value));
        }
        if (days.isEmpty()) {
            throw new IllegalArgumentException("SERIES_WEEKDAYS_REQUIRED");
        }
        return days;
    }

    // -----------------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------------

    /** @brief What one generation run did, so the UI can report it instead of guessing. */
    public record GenerationResult(int occurrences, int generated, int skippedException, int positions) {
    }

    /**
     * @brief Creates the missing occurrences of a series in a period.
     *
     * @details Deliberately <b>not</b> one transaction around the loop: each occurrence is
     *          committed on its own through {@link AssignmentService#create}, so a single bad
     *          requirement cannot throw away a month of correctly generated visits.
     */
    public GenerationResult generate(int seriesId, LocalDate from, LocalDate to) {
        AssignmentSeriesEntity series = AssignmentSeriesEntity.findById(seriesId);
        if (series == null) {
            return null;
        }
        if (!series.active) {
            return new GenerationResult(0, 0, 0, 0);
        }
        List<LocalDate> dates = occurrences(series.patternType, series.weekdays, series.nth,
                parseDate(series.effectiveFrom), parseDate(series.effectiveTo), from, to);
        if (dates.isEmpty()) {
            return new GenerationResult(0, 0, 0, 0);
        }
        Set<String> existing = new HashSet<>();
        for (CustomerAssignmentEntity assignment : CustomerAssignmentEntity.<CustomerAssignmentEntity>list(
                "seriesId = ?1 and assignmentDate >= ?2 and assignmentDate <= ?3",
                seriesId, from.toString(), to.toString())) {
            existing.add(assignment.assignmentDate);
        }
        Set<String> exceptions = new HashSet<>();
        for (AssignmentExceptionEntity exception : AssignmentExceptionEntity
                .<AssignmentExceptionEntity>list("seriesId = ?1", seriesId)) {
            exceptions.add(exception.occurrenceDate);
        }

        List<AssignmentSeriesRequirementEntity> requirements = AssignmentSeriesRequirementEntity
                .list("seriesId = ?1 order by id", seriesId);
        List<Integer> created = new ArrayList<>();
        int skipped = 0;
        for (LocalDate date : dates) {
            String day = date.toString();
            if (existing.contains(day) || exceptions.contains(day)) {
                skipped++;
                continue;
            }
            created.add(createOccurrence(series, requirements, day));
        }
        int positions = created.isEmpty() ? 0
                : (int) ShiftEntity.count("assignmentId in ?1", created);
        return new GenerationResult(dates.size(), created.size(), skipped, positions);
    }

    private int createOccurrence(AssignmentSeriesEntity series,
            List<AssignmentSeriesRequirementEntity> requirements, String day) {
        CustomerAssignmentEntity assignment = new CustomerAssignmentEntity();
        assignment.structureId = series.structureId;
        assignment.customerId = series.customerId;
        assignment.locationId = series.locationId;
        assignment.title = series.title;
        assignment.service = series.service;
        assignment.assignmentDate = day;
        assignment.startTime = series.startTime;
        assignment.durationMinutes = series.durationMinutes;
        assignment.priority = series.priority;
        // A flexible series leaves the team and the exact minute to the solver; a fixed one pins
        // the time, which is what a customer with a contractual slot needs.
        assignment.status = series.fixedTime ? "FIXED" : "FLEXIBLE";
        assignment.fixedTime = series.fixedTime;
        assignment.notes = series.notes;
        assignment.seriesId = series.id;
        List<AssignmentRequirementEntity> mapped = new ArrayList<>();
        for (AssignmentSeriesRequirementEntity requirement : requirements) {
            AssignmentRequirementEntity entity = new AssignmentRequirementEntity();
            entity.role = requirement.role;
            entity.skillId = requirement.skillId;
            entity.headcount = requirement.headcount;
            mapped.add(entity);
        }
        return assignments.create(assignment, mapped);
    }

    // -----------------------------------------------------------------------
    // Exceptions (single occurrences)
    // -----------------------------------------------------------------------

    /** @brief The occurrence of a series on a date, or null when none was generated. */
    public CustomerAssignmentEntity occurrence(int seriesId, LocalDate date) {
        return CustomerAssignmentEntity.<CustomerAssignmentEntity>find(
                "seriesId = ?1 and assignmentDate = ?2", seriesId, date.toString()).firstResult();
    }

    /**
     * @brief Cancels one occurrence and remembers that the slot is accounted for.
     * @return true when a generated occurrence existed; the exception is recorded either way
     */
    @Transactional
    public boolean cancelOccurrence(int seriesId, LocalDate date, String notes) {
        CustomerAssignmentEntity assignment = occurrence(seriesId, date);
        if (assignment != null) {
            assignment.status = "CANCELLED";
            // Deletes the positions: a cancelled visit must not hold staff.
            assignments.regeneratePositions(assignment.id);
        }
        recordException(seriesId, date, AssignmentExceptionEntity.CANCELLED, "", "", notes);
        return assignment != null;
    }

    /** @brief Moves one occurrence to another date/time, keeping the series untouched. */
    @Transactional
    public boolean moveOccurrence(int seriesId, LocalDate date, LocalDate newDate, String newStartTime,
            String notes) {
        CustomerAssignmentEntity assignment = occurrence(seriesId, date);
        if (assignment == null) {
            return false;
        }
        assignment.assignmentDate = newDate.toString();
        if (newStartTime != null && !newStartTime.isBlank()) {
            assignment.startTime = newStartTime;
        }
        // Start/end of the materialised positions follow the new slot.
        assignments.regeneratePositions(assignment.id);
        recordException(seriesId, date, AssignmentExceptionEntity.MOVED, newDate.toString(),
                newStartTime == null ? "" : newStartTime, notes);
        return true;
    }

    /** @brief The deviation recorded for one date, or null when the date follows the series. */
    public AssignmentExceptionEntity exceptionOf(int seriesId, LocalDate date) {
        return AssignmentExceptionEntity.<AssignmentExceptionEntity>find(
                "seriesId = ?1 and occurrenceDate = ?2", seriesId, date.toString()).firstResult();
    }

    /**
     * @brief Forgets the deviation of one date, so the next generation may recreate it.
     *
     * @details A cancellation leaves the assignment row behind as the audit trail, and that row
     *          would block the regeneration on its own. Undoing a cancellation therefore removes
     *          the cancelled occurrence as well — otherwise "restore" would report success while
     *          the visit stayed missing.
     */
    @Transactional
    public void clearException(int seriesId, LocalDate date) {
        AssignmentExceptionEntity.delete("seriesId = ?1 and occurrenceDate = ?2",
                seriesId, date.toString());
        CustomerAssignmentEntity ghost = occurrence(seriesId, date);
        if (ghost != null && "CANCELLED".equals(ghost.status)) {
            assignments.delete(ghost.id);
        }
    }

    @Transactional
    public void recordException(int seriesId, LocalDate date, String kind, String newDate,
            String newStartTime, String notes) {
        AssignmentExceptionEntity exception = AssignmentExceptionEntity.find(
                "seriesId = ?1 and occurrenceDate = ?2", seriesId, date.toString()).firstResult();
        if (exception == null) {
            exception = new AssignmentExceptionEntity();
            exception.seriesId = seriesId;
            exception.occurrenceDate = date.toString();
            exception.createdAt = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        exception.kind = kind;
        exception.newDate = newDate == null ? "" : newDate;
        exception.newStartTime = newStartTime == null ? "" : newStartTime;
        exception.notes = notes == null ? "" : notes;
        exception.persist();
    }

    public List<AssignmentException> exceptions(int seriesId) {
        List<AssignmentException> result = new ArrayList<>();
        for (AssignmentExceptionEntity entity : AssignmentExceptionEntity
                .<AssignmentExceptionEntity>list("seriesId = ?1 order by occurrenceDate", seriesId)) {
            AssignmentException dto = new AssignmentException();
            dto.id = entity.id;
            dto.seriesId = entity.seriesId;
            dto.occurrenceDate = entity.occurrenceDate;
            dto.kind = entity.kind;
            dto.newDate = entity.newDate;
            dto.newStartTime = entity.newStartTime;
            dto.notes = entity.notes;
            result.add(dto);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Read / delete
    // -----------------------------------------------------------------------

    public AssignmentSeries toDto(AssignmentSeriesEntity entity) {
        AssignmentSeries dto = new AssignmentSeries();
        dto.id = entity.id;
        dto.structureId = entity.structureId;
        dto.customerId = entity.customerId;
        dto.locationId = entity.locationId;
        dto.title = entity.title;
        dto.service = entity.service;
        dto.startTime = entity.startTime;
        dto.durationMinutes = entity.durationMinutes;
        dto.priority = entity.priority;
        dto.fixedTime = entity.fixedTime;
        dto.notes = entity.notes;
        dto.patternType = entity.patternType;
        dto.weekdays = entity.weekdays;
        dto.nth = entity.nth;
        dto.effectiveFrom = entity.effectiveFrom;
        dto.effectiveTo = entity.effectiveTo;
        dto.active = entity.active;
        CustomerEntity customer = CustomerEntity.findById(entity.customerId);
        dto.customerName = customer == null ? "" : customer.name;
        LocationEntity location = LocationEntity.findById(entity.locationId);
        dto.locationName = location == null ? "" : location.name;
        dto.occurrenceCount = (int) CustomerAssignmentEntity.count("seriesId = ?1", entity.id);
        for (AssignmentSeriesRequirementEntity requirement : AssignmentSeriesRequirementEntity
                .<AssignmentSeriesRequirementEntity>list("seriesId = ?1 order by id", entity.id)) {
            AssignmentSeriesRequirement line = new AssignmentSeriesRequirement();
            line.id = requirement.id;
            line.seriesId = requirement.seriesId;
            line.role = requirement.role;
            line.skillId = requirement.skillId;
            line.headcount = requirement.headcount;
            if (requirement.skillId != null) {
                SkillEntity skill = SkillEntity.findById(requirement.skillId);
                line.skillName = skill == null ? "" : skill.name;
            }
            dto.requirements.add(line);
        }
        return dto;
    }

    public List<AssignmentSeries> list(int structureId) {
        List<AssignmentSeries> result = new ArrayList<>();
        for (AssignmentSeriesEntity entity : AssignmentSeriesEntity
                .<AssignmentSeriesEntity>list("structureId = ?1 order by id desc", structureId)) {
            result.add(toDto(entity));
        }
        return result;
    }

    /**
     * @brief Deletes a series, optionally its occurrences too.
     *
     * @details Keeping the occurrences is the default: a planner who stops a series usually wants
     *          the already-planned visits to stay in the plan. Deleting them would silently empty
     *          days the solver has already staffed.
     */
    @Transactional
    public void delete(int seriesId, boolean deleteOccurrences) {
        List<CustomerAssignmentEntity> occurrences = CustomerAssignmentEntity.list("seriesId = ?1", seriesId);
        for (CustomerAssignmentEntity occurrence : occurrences) {
            if (deleteOccurrences) {
                assignments.delete(occurrence.id);
            } else {
                occurrence.seriesId = null;
            }
        }
        AssignmentExceptionEntity.delete("seriesId = ?1", seriesId);
        AssignmentSeriesRequirementEntity.delete("seriesId = ?1", seriesId);
        AssignmentSeriesEntity.deleteById(seriesId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

}
