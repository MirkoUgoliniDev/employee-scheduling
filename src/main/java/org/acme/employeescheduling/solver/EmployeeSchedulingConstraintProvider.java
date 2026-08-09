package org.acme.employeescheduling.solver;

import static ai.timefold.solver.core.api.score.stream.Joiners.equal;
import static ai.timefold.solver.core.api.score.stream.Joiners.filtering;
import static ai.timefold.solver.core.api.score.stream.Joiners.lessThanOrEqual;
import static ai.timefold.solver.core.api.score.stream.Joiners.overlapping;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Function;
import ai.timefold.solver.core.api.score.buildin.hardsoftbigdecimal.HardSoftBigDecimalScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.Skill;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.dto.SolverSettings;
import java.math.BigDecimal;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;






/**
 * @brief Constraint provider defining scheduling rules for the employee scheduling solver.
 * @details Implements the Timefold ConstraintProvider interface to define both hard
 *          and soft constraints used during optimization. Hard constraints include
 *          required skills, no overlapping shifts, minimum rest between shifts, one
 *          shift per day, and employee unavailability. Soft constraints handle
 *          preferred/undesired scheduling days and workload balancing.
 * @author Employee Scheduling Team
 * @version 1.0
 */
public class EmployeeSchedulingConstraintProvider implements ConstraintProvider {

    /**
     * Scale factor for the soft constraints that measure a violation as a "count"
     * (shifts/days) instead of minutes: the equivalent minutes of a standard day (8h).
     * It makes `count × weight` commensurable with the soft constraints in minutes×weight
     * (undesired, avoidSpecialist, unassigned), which would otherwise dominate by two orders of magnitude.
     */
    private static final int SOFT_UNIT_MINUTES = 480;

    private record EmployeeDay(Employee employee, java.time.LocalDate day) {}
    private record EmployeeWeek(Employee employee, int year, int week) {}
    private record Week(int year, int week) {}
    private static EmployeeWeek employeeWeek(Shift shift) {
        var date = shift.getStart().toLocalDate();
        return new EmployeeWeek(shift.getEmployee(), date.get(WeekFields.ISO.weekBasedYear()), date.get(WeekFields.ISO.weekOfWeekBasedYear()));
    }
    private static Week weekOf(Shift shift) {
        var date = shift.getStart().toLocalDate();
        return new Week(date.get(WeekFields.ISO.weekBasedYear()), date.get(WeekFields.ISO.weekOfWeekBasedYear()));
    }
    private static int durationMinutes(Shift shift) { return (int) Duration.between(shift.getStart(), shift.getEnd()).toMinutes(); }
    private static int maxConsecutiveDays(List<Shift> shifts) {
        var days = shifts.stream().map(s -> s.getStart().toLocalDate()).distinct().sorted().toList();
        int max=0,current=0; java.time.LocalDate previous=null;
        for (var day:days) { current = previous != null && previous.plusDays(1).equals(day) ? current+1 : 1; max=Math.max(max,current); previous=day; }
        return max;
    }

    /**
     * @brief Calculates the overlap in minutes between two shifts.
     * @param shift1 the first shift
     * @param shift2 the second shift
     * @return the number of overlapping minutes, or 0 if no overlap exists
     */
    private static int getMinuteOverlap(Shift shift1, Shift shift2) {
        if (shift1.getStart() == null || shift1.getEnd() == null
                || shift2.getStart() == null || shift2.getEnd() == null) {
            return 0;
        }
        LocalDateTime shift1Start = shift1.getStart();
        LocalDateTime shift1End = shift1.getEnd();
        LocalDateTime shift2Start = shift2.getStart();
        LocalDateTime shift2End = shift2.getEnd();
        LocalDateTime maxStart = shift1Start.isAfter(shift2Start) ? shift1Start : shift2Start;
        LocalDateTime minEnd = shift1End.isBefore(shift2End) ? shift1End : shift2End;
        long minutes = Duration.between(maxStart, minEnd).toMinutes();
        return minutes > 0 ? (int) minutes : 0;
    }

    /**
     * @brief Defines the complete set of hard and soft constraints for the solver.
     * @param constraintFactory the factory used to create constraint streams
     * @return an array of all defined constraints
     */
    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                requiredSkill(constraintFactory),
                noOverlappingShifts(constraintFactory),
                atLeast10HoursBetweenTwoShifts(constraintFactory),
                oneShiftPerDay(constraintFactory),
                unavailableEmployee(constraintFactory),
                incompatibleSpecialist(constraintFactory),
                avoidSpecialist(constraintFactory),
                undesiredDayForEmployee(constraintFactory),
                desiredDayForEmployee(constraintFactory),
                optionalSkillForEmployee(constraintFactory),
                balanceEmployeeShiftAssignments(constraintFactory),
                balanceEmployeeHours(constraintFactory),
                weeklyHoursLimit(constraintFactory), weeklyShiftLimits(constraintFactory),
                minimumWeeklyShiftsEmptyWeek(constraintFactory),
                minimumDaysOff(constraintFactory), maximumConsecutiveDays(constraintFactory),
                sameLocationContinuity(constraintFactory), balanceNightShifts(constraintFactory),
                unassignedHard(constraintFactory), unassignedSoft(constraintFactory)
        };
    }

    /**
     * @brief Hard constraint: penalizes shifts assigned to employees lacking required skills.
     * @details Penalizes in MINUTES (shift duration), like unavailableEmployee and the
     *          rest/overlap constraints — not 1: on the hard level "covering a
     *          shift with unqualified staff" (1) tied with "leaving it
     *          uncovered" (unassignedHard=1) and the tie was broken by the
     *          balancing soft constraint, which could systematically prefer the
     *          unqualified employee. In minutes, the missing skill always costs more.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "Missing required skill" constraint
     */
    Constraint requiredSkill(ConstraintFactory constraintFactory) {
        // Context shifts (outside the window, pinned) are FACTS: a pre-existing
        // violation of theirs cannot be corrected by the solver and would make every
        // solve infeasible. Here and in the other "state" constraints they are
        // excluded; they stay visible only to the BOUNDARY constraints (overlap,
        // rest, weekly hours, consecutive days) where they interact with the window's shifts.
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null && !shift.isContext() && isMissingRequiredSkill(shift))
                .penalize(HardSoftBigDecimalScore.ONE_HARD, EmployeeSchedulingConstraintProvider::durationMinutes)
                .asConstraint("Missing required skill");
    }

    /** @brief IDs of only the skills actually flagged as `used` (null-safe). */
    private static Set<Integer> usedSkillIds(List<Skill> skills) {
        if (skills == null) return Set.of();
        return skills.stream().filter(Skill::isUsed).map(Skill::getId).collect(Collectors.toSet());
    }

    /**
     * @brief true if the assigned employee is missing at least one skill required by the shift.
     * @details The skill lists in the payload are the FULL CATALOGUE with a `used` flag:
     *          the comparison must consider only the used skills and be done by ID
     *          (the Skill instances of the shift and of the employee are distinct objects).
     */
    private static boolean isMissingRequiredSkill(Shift shift) {
        Set<Integer> required = usedSkillIds(shift.getRequiredSkill());
        if (required.isEmpty()) return false;
        return !usedSkillIds(shift.getEmployee().getSkills()).containsAll(required);
    }

    /**
     * @brief Hard constraint: penalizes overlapping shifts assigned to the same employee.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "Overlapping shift" constraint
     */
    Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
        // Boundary constraint: mixed window↔context pairs must be penalized
        // (that is the very reason the context exists); a pair of context-only shifts is
        // an immutable fact and must be ignored so the solve is not made infeasible.
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                overlapping(Shift::getStart, Shift::getEnd))
                .filter((shift1, shift2) -> shift1.getEmployee() != null
                        && !(shift1.isContext() && shift2.isContext()))
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                .asConstraint("Overlapping shift");
    }

    /**
     * @brief Hard constraint: penalizes consecutive shifts with less than 10 hours of rest.
     * @details The minimum rest applies only BETWEEN DIFFERENT DAYS. Two shifts on the same
     *          day (e.g. morning 07:30-13:00 and afternoon 14:30-18:00) are governed
     *          by maxShiftsPerDay: requiring a 10-hour break there too would make the
     *          double shift mathematically impossible.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "At least 10 hours between 2 shifts" constraint
     */
    Constraint atLeast10HoursBetweenTwoShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Shift.class, equal(Shift::getEmployee), lessThanOrEqual(Shift::getEnd, Shift::getStart))
                .join(SolverSettings.class)
                .filter((firstShift, secondShift, settings) -> firstShift.getEmployee() != null
                        // Boundary constraint: context-only pairs ignored (immutable facts).
                        && !(firstShift.isContext() && secondShift.isContext())
                        && !firstShift.getStart().toLocalDate().equals(secondShift.getStart().toLocalDate())
                        && Duration.between(firstShift.getEnd(), secondShift.getStart()).toHours() < settings.getMinimumRestHours())
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (firstShift, secondShift, settings) -> {
                            long breakMinutes = Duration.between(firstShift.getEnd(), secondShift.getStart()).toMinutes();
                            long missingMinutes = (settings.getMinimumRestHours() * 60L) - breakMinutes;
                            return Math.max(0, (int) missingMinutes);
                        })
                .asConstraint("At least 10 hours between 2 shifts");
    }

    /**
     * @brief Hard constraint: penalizes assigning more than one shift per day to the same employee.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "Max one shift per day" constraint
     */
    Constraint oneShiftPerDay(ConstraintFactory constraintFactory) {
        // Context excluded: the windows start at midnight, so a day is never
        // shared between window and context; including them could only import
        // pre-existing, uncorrectable violations.
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null && !shift.isContext())
                .groupBy(shift -> new EmployeeDay(shift.getEmployee(), shift.getStart().toLocalDate()),
                        ConstraintCollectors.count())
                .join(SolverSettings.class)
                .filter((employeeDay, count, settings) -> count > settings.getMaxShiftsPerDay())
                // Scale of "one day of minutes" per excess shift: at weight 1 the
                // constraint tied with unassignedHard and the solver could prefer
                // to exceed the daily maximum just to cover a shift.
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employeeDay, count, settings) -> (count - settings.getMaxShiftsPerDay()) * 1440)
                .asConstraint("Max one shift per day");
    }

    
    

    /**
     * @brief Hard constraint: penalizes assigning shifts to employees during their unavailable dates.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "Unavailable employee" constraint
     */
    Constraint unavailableEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.isContext())
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUnavailableDates)
                .filter((shift, date) -> shift.isOverlappingWithDate(date))
                .penalize(HardSoftBigDecimalScore.ONE_HARD, Shift::getOverlappingDurationInMinutes)
                .asConstraint("Unavailable employee");
    }


    /**
     * @brief Hard constraint: forbids shifts whose location has a specialist
     *        incompatible with the assigned employee.
     * @details Twin of unavailableEmployee: shift.specialistId is denormalized
     *          from the location (locations.specialist_id) when the payload is built,
     *          employee.incompatibleSpecialistIds from operator_specialist_affinity (type=3).
     */
    Constraint incompatibleSpecialist(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null && !shift.isContext()
                        && shift.getSpecialistId() != null
                        && shift.getEmployee().getIncompatibleSpecialistIds().contains(shift.getSpecialistId()))
                // In minutes like requiredSkill: "incompatible" must cost more than
                // "uncovered shift" (1), never tie with it.
                .penalize(HardSoftBigDecimalScore.ONE_HARD, EmployeeSchedulingConstraintProvider::durationMinutes)
                .asConstraint("Incompatible specialist");
    }

    /**
     * @brief Soft constraint: penalizes shifts whose location has a specialist
     *        "to be avoided" for the assigned employee (type=2), weighted by
     *        avoid_specialist_weight in the SolverSettings.
     */
    Constraint avoidSpecialist(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null && !shift.isContext()
                        && shift.getSpecialistId() != null
                        && shift.getEmployee().getAvoidSpecialistIds().contains(shift.getSpecialistId()))
                .join(SolverSettings.class)
                // In minutes×weight like undesiredDayForEmployee: as a bare weight (1-10) the
                // constraint was almost irrelevant compared to the soft constraints in minutes (an
                // 8h undesired day weighs 480); same unit = same order of magnitude.
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (shift, settings) -> durationMinutes(shift) * settings.getAvoidSpecialistWeight())
                .asConstraint("Avoid specialist");
    }

    /**
     * @brief Soft constraint: penalizes assigning shifts on days undesired by the employee.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "Undesired day for employee" constraint
     */
    Constraint undesiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.isContext())
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUndesiredDates)
                .filter((shift, date) -> shift.isOverlappingWithDate(date))
                .join(SolverSettings.class)
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (shift, date, settings) -> shift.getOverlappingDurationInMinutes(date) * settings.getUndesiredDateWeight())
                .asConstraint("Undesired day for employee");
    }

    
    

    /**
     * @brief Soft constraint: rewards assigning shifts on days desired by the employee.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "Desired day for employee" constraint
     */
    Constraint desiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.isContext())
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getDesiredDates)
                .filter((shift, date) -> shift.isOverlappingWithDate(date))
                .join(SolverSettings.class)
                .reward(HardSoftBigDecimalScore.ONE_SOFT,
                        (shift, date, settings) -> shift.getOverlappingDurationInMinutes(date) * settings.getDesiredDateWeight())
                .asConstraint("Desired day for employee");
    }

    Constraint optionalSkillForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null && !shift.isContext() && shift.getOptionalSkill() != null)
                .join(SolverSettings.class)
                .reward(HardSoftBigDecimalScore.ONE_SOFT, (shift, settings) -> {
                    // As for requiredSkill: only the `used` skills count, compared by ID.
                    Set<Integer> owned = usedSkillIds(shift.getEmployee().getSkills());
                    return (int) usedSkillIds(shift.getOptionalSkill()).stream().filter(owned::contains).count()
                            * settings.getOptionalSkillWeight();
                })
                .asConstraint("Optional skill match");
    }

    /**
     * @brief Soft constraint: penalizes uneven distribution of shifts among employees.
     * @details Uses load balancing to minimize the unfairness in the number of shifts
     *          assigned to each employee, promoting equitable workload distribution.
     * @param constraintFactory the factory used to create constraint streams
     * @return the "Balance employee shift assignments" constraint
     */
    Constraint balanceEmployeeShiftAssignments(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null && !shift.isContext())
                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                .complement(Employee.class, e -> 0)
                .groupBy(ConstraintCollectors.loadBalance((employee, shiftCount) -> employee,
                        (employee, shiftCount) -> shiftCount))
                .join(SolverSettings.class)
                .filter((loadBalance, settings) -> !settings.isBalanceByHours())
                .penalizeBigDecimal(HardSoftBigDecimalScore.ONE_SOFT,
                        (loadBalance, settings) -> loadBalance.unfairness().multiply(BigDecimal.valueOf(settings.getBalanceWeight())))
                .asConstraint("Balance employee shift assignments");
    }

    Constraint balanceEmployeeHours(ConstraintFactory f) {
        return f.forEach(Shift.class).filter(s -> s.getEmployee()!=null && !s.isContext())
                .groupBy(Shift::getEmployee, ConstraintCollectors.sum(EmployeeSchedulingConstraintProvider::durationMinutes))
                .complement(Employee.class, e -> 0)
                .groupBy(ConstraintCollectors.loadBalance((employee, minutes)->employee, (employee, minutes)->minutes))
                .join(SolverSettings.class).filter((load, settings)->settings.isBalanceByHours())
                .penalizeBigDecimal(HardSoftBigDecimalScore.ONE_SOFT,
                        (load,settings)->load.unfairness().multiply(BigDecimal.valueOf(settings.getBalanceWeight())))
                .asConstraint("Balance employee hours");
    }

    Constraint weeklyHoursLimit(ConstraintFactory f) {
        // Boundary constraint: the minutes of context shifts count towards the ISO
        // week total (the hours already worked beyond the window edge do exist), but the
        // penalty covers only the part the solver can REDUCE: if the context alone
        // already exceeds the limit, the pre-existing excess must not be counted (it would be
        // a constant, uncorrectable hard penalty).
        return f.forEach(Shift.class).filter(s->s.getEmployee()!=null)
                .groupBy(EmployeeSchedulingConstraintProvider::employeeWeek,
                        ConstraintCollectors.sum(EmployeeSchedulingConstraintProvider::durationMinutes),
                        ConstraintCollectors.sum(s -> s.isContext() ? durationMinutes(s) : 0))
                .join(SolverSettings.class)
                .filter((key,minutes,contextMinutes,settings)->settings.getMaxWeeklyHours()>0
                        && minutes > Math.max(settings.getMaxWeeklyHours()*60, contextMinutes))
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (key,minutes,contextMinutes,settings)->minutes - Math.max(settings.getMaxWeeklyHours()*60, contextMinutes))
                .asConstraint("Maximum weekly hours");
    }

    Constraint weeklyShiftLimits(ConstraintFactory f) {
        return f.forEach(Shift.class).filter(s->s.getEmployee()!=null && !s.isContext())
                .groupBy(EmployeeSchedulingConstraintProvider::employeeWeek, ConstraintCollectors.count())
                .join(SolverSettings.class)
                .filter((key,count,settings)->(settings.getMaxWeeklyShifts()>0 && count>settings.getMaxWeeklyShifts()) || count<settings.getMinWeeklyShifts())
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,(key,count,settings)-> (count<settings.getMinWeeklyShifts()?settings.getMinWeeklyShifts()-count:count-settings.getMaxWeeklyShifts()) * settings.getWeeklyShiftWeight() * SOFT_UNIT_MINUTES)
                .asConstraint("Weekly shift range");
    }

    /**
     * @brief Soft: completes the weekly minimum for the employees with ZERO shifts.
     * @details weeklyShiftLimits groups by (employee, week) and therefore does not see
     *          those who have no shift at all: without this complement, removing the only shift
     *          of a below-minimum employee REDUCED the penalty and the solver tended to
     *          empty them out completely instead of filling them up. Here every pair
     *          (week present in the problem × employee with no shifts in that
     *          week) penalizes the whole minimum, making the gradient monotonic:
     *          0 shifts → min, 1 shift → min-1, … min shifts → 0.
     */
    Constraint minimumWeeklyShiftsEmptyWeek(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(Shift.class).filter(s -> !s.isContext())
                .groupBy(EmployeeSchedulingConstraintProvider::weekOf)
                .join(Employee.class)
                .ifNotExists(Shift.class,
                        equal((week, employee) -> week, EmployeeSchedulingConstraintProvider::weekOf),
                        equal((week, employee) -> employee, Shift::getEmployee),
                        filtering((week, employee, shift) -> !shift.isContext()))
                .join(SolverSettings.class)
                .filter((week, employee, settings) -> settings.getMinWeeklyShifts() > 0)
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (week, employee, settings) -> settings.getMinWeeklyShifts() * settings.getWeeklyShiftWeight() * SOFT_UNIT_MINUTES)
                .asConstraint("Minimum weekly shifts (empty week)");
    }

    Constraint minimumDaysOff(ConstraintFactory f) {
        return f.forEach(Shift.class).filter(s->s.getEmployee()!=null && !s.isContext())
                .groupBy(EmployeeSchedulingConstraintProvider::employeeWeek,
                        ConstraintCollectors.countDistinct(s->s.getStart().toLocalDate()))
                .join(SolverSettings.class).filter((key,workedDays,settings)->settings.getMinDaysOffPerWeek()>0 && 7-workedDays<settings.getMinDaysOffPerWeek())
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,(key,workedDays,settings)->(settings.getMinDaysOffPerWeek()-(7-workedDays)) * settings.getDaysOffWeight() * SOFT_UNIT_MINUTES)
                .asConstraint("Minimum days off per week");
    }

    Constraint maximumConsecutiveDays(ConstraintFactory f) {
        // Boundary constraint: context shifts DO COUNT in the sequence of consecutive
        // days (5 days at the end of the previous week + 3 at the start of the window
        // = a run of 8). Soft penalty, partially reducible: the constant residue is
        // accepted in the rare case of a pre-existing run already beyond the maximum.
        return f.forEach(Shift.class).filter(s->s.getEmployee()!=null)
                .groupBy(Shift::getEmployee, ConstraintCollectors.toList())
                .join(SolverSettings.class).filter((employee,shifts,settings)->settings.getMaxConsecutiveDays()>0 && maxConsecutiveDays(shifts)>settings.getMaxConsecutiveDays())
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,(employee,shifts,settings)->(maxConsecutiveDays(shifts)-settings.getMaxConsecutiveDays()) * settings.getConsecutiveDaysWeight() * SOFT_UNIT_MINUTES)
                .asConstraint("Maximum consecutive days");
    }

    Constraint sameLocationContinuity(ConstraintFactory f) {
        return f.forEachUniquePair(Shift.class, equal(Shift::getEmployee), equal(Shift::getLocation_id))
                .filter((a,b)->a.getEmployee()!=null && !a.isContext() && !b.isContext()).join(SolverSettings.class)
                .reward(HardSoftBigDecimalScore.ONE_SOFT,(a,b,settings)->settings.getSameLocationWeight())
                .asConstraint("Same location continuity");
    }

    Constraint balanceNightShifts(ConstraintFactory f) {
        return f.forEach(Shift.class).filter(s->s.getEmployee()!=null && !s.isContext())
                .join(SolverSettings.class)
                .filter((s,settings)->settings.getNightBalanceWeight()>0
                        && isNightHour(s.getStart().getHour(), settings.getNightStartHour(), settings.getNightEndHour()))
                .groupBy((s,settings)->s.getEmployee(), ConstraintCollectors.countBi())
                .complement(Employee.class,e->0)
                .groupBy(ConstraintCollectors.loadBalance((employee,count)->employee,(employee,count)->count))
                .join(SolverSettings.class)
                .penalizeBigDecimal(HardSoftBigDecimalScore.ONE_SOFT,(load,settings)->load.unfairness().multiply(BigDecimal.valueOf(settings.getNightBalanceWeight())))
                .asConstraint("Balance night shifts");
    }

    /**
     * @brief true if hour `h` falls in the night band [start, end) — wrap-aware.
     * @details The old fixed OR (h>=start || h<end) was correct only for bands
     *          straddling midnight (22→6): with start<end (e.g. 0→6) it was ALWAYS
     *          true and every shift counted as a night shift, degenerating the night
     *          balancing into a duplicate of the general one. start==end = empty band.
     */
    private static boolean isNightHour(int h, int start, int end) {
        if (start < end) return h >= start && h < end;
        if (start > end) return h >= start || h < end;
        return false;
    }

    Constraint unassignedHard(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(Shift.class).filter(s->s.getEmployee()==null).join(SolverSettings.class)
                .filter((s,settings)->!settings.isAllowUnassigned()).penalize(HardSoftBigDecimalScore.ONE_HARD)
                .asConstraint("Unassigned shift forbidden");
    }
    Constraint unassignedSoft(ConstraintFactory f) {
        // Use minutes×weight like the other soft constraints: with the raw weight (default 10),
        // an 8-hour (480-minute) undesired day cost 48 times as much as an uncovered shift, so
        // the solver systematically preferred leaving the shift uncovered to honor a preference.
        // With the same unit, coverage wins when durations are equal as long as
        // unassigned_weight > undesired_date_weight.
        return f.forEachIncludingUnassigned(Shift.class).filter(s->s.getEmployee()==null).join(SolverSettings.class)
                .filter((s,settings)->settings.isAllowUnassigned())
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (s,settings)->durationMinutes(s)*settings.getUnassignedWeight())
                .asConstraint("Unassigned shift penalty");
    }
}
