package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * @brief Unit test of the recurrence rule, without a database or a solver.
 *
 * @details The date arithmetic is the part that silently produces a wrong plan — a biweekly
 *          series whose parity shifts when the planner asks for the next month, or a "last
 *          Friday" that does not exist in February. Every case below fails if the calculation
 *          changes meaning, which is why it is tested without any of the surrounding plumbing.
 */
class AssignmentSeriesOccurrencesTest {

    private static List<LocalDate> weekly(String weekdays, String from, String to) {
        return AssignmentSeriesService.occurrences(org.acme.employeescheduling.persistence.AssignmentSeriesEntity.WEEKLY,
                weekdays, 0, null, null, LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void weeklyReturnsEveryListedWeekday() {
        // July 2026: the 6th is a Monday, the 9th a Thursday.
        List<LocalDate> dates = weekly("1,4", "2026-07-01", "2026-07-19");
        assertEquals(List.of(
                LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-06"),
                LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-13"),
                LocalDate.parse("2026-07-16")), dates);
    }

    @Test
    void biweeklyKeepsItsParityWhenThePeriodMoves() {
        // Effective start 2026-03-04 (a Wednesday) anchors the parity on the week of 2026-03-02.
        java.time.LocalDate anchor = LocalDate.parse("2026-03-04");
        List<LocalDate> march = AssignmentSeriesService.occurrences("BIWEEKLY", "1", 0,
                anchor, null, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"));
        assertEquals(List.of(LocalDate.parse("2026-03-16"), LocalDate.parse("2026-03-30")), march);

        // Asking for the next month must continue the pattern, not restart it.
        List<LocalDate> april = AssignmentSeriesService.occurrences("BIWEEKLY", "1", 0,
                anchor, null, LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"));
        assertEquals(List.of(LocalDate.parse("2026-04-13"), LocalDate.parse("2026-04-27")), april);
    }

    @Test
    void monthlyTakesTheNthWeekdayOfEachMonth() {
        List<LocalDate> firstFridays = AssignmentSeriesService.occurrences("MONTHLY_NTH_WEEKDAY", "5", 1,
                null, null, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-09-30"));
        assertEquals(List.of(
                LocalDate.parse("2026-07-03"), LocalDate.parse("2026-08-07"),
                LocalDate.parse("2026-09-04")), firstFridays);
    }

    @Test
    void monthlyNthFiveMeansTheLastWeekdayOfTheMonth() {
        // July 2026 has five Fridays (3, 10, 17, 24, 31), August only four (7, 14, 21, 28).
        List<LocalDate> lastFridays = AssignmentSeriesService.occurrences("MONTHLY_NTH_WEEKDAY", "5", 5,
                null, null, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-31"));
        assertEquals(List.of(LocalDate.parse("2026-07-31"), LocalDate.parse("2026-08-28")), lastFridays);
    }

    @Test
    void theEffectiveRangeWinsOverTheRequestedPeriod() {
        List<LocalDate> dates = AssignmentSeriesService.occurrences("WEEKLY", "1", 0,
                LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"));
        assertEquals(List.of(LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20")), dates);
    }

    @Test
    void aDailyRuleBeyondTheGuardIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> weekly("1,2,3,4,5,6,7", "2026-01-01", "2027-12-31"));
        assertEquals("SERIES_TOO_MANY_OCCURRENCES", error.getMessage());
    }

    @Test
    void invalidWeekdaysAndRangesAreRejected() {
        assertEquals("SERIES_WEEKDAYS_INVALID", assertThrows(IllegalArgumentException.class,
                () -> weekly("9", "2026-07-01", "2026-07-31")).getMessage());
        assertEquals("SERIES_RANGE_INVALID", assertThrows(IllegalArgumentException.class,
                () -> weekly("1", "2026-07-31", "2026-07-01")).getMessage());
        assertEquals("SERIES_NTH_INVALID", assertThrows(IllegalArgumentException.class,
                () -> AssignmentSeriesService.occurrences("MONTHLY_NTH_WEEKDAY", "1", 0, null, null,
                        LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"))).getMessage());
    }
}
