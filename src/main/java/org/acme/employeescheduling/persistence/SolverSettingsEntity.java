package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.acme.employeescheduling.dto.SolverSettings;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "solver_settings")
public class SolverSettingsEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name="structure_id", nullable=false, unique=true) public int structureId;
    @Column(name="max_solve_seconds", nullable=false) public int maxSolveSeconds;
    @Column(name="unimproved_seconds", nullable=false) public int unimprovedSeconds;
    @Column(name="minimum_rest_hours", nullable=false) public int minimumRestHours;
    @Column(name="max_shifts_per_day", nullable=false) public int maxShiftsPerDay;
    @Column(name="desired_date_weight", nullable=false) public int desiredDateWeight;
    @Column(name="undesired_date_weight", nullable=false) public int undesiredDateWeight;
    @Column(name="balance_weight", nullable=false) public int balanceWeight;
    @Column(name="optional_skill_weight", nullable=false) public int optionalSkillWeight;
    @JdbcTypeCode(SqlTypes.INTEGER) @Column(name="balance_by_hours", nullable=false) public boolean balanceByHours;
    @Column(name="max_weekly_hours", nullable=false) public int maxWeeklyHours;
    @Column(name="min_weekly_shifts", nullable=false) public int minWeeklyShifts;
    @Column(name="max_weekly_shifts", nullable=false) public int maxWeeklyShifts;
    @Column(name="max_consecutive_days", nullable=false) public int maxConsecutiveDays;
    @Column(name="min_days_off_per_week", nullable=false) public int minDaysOffPerWeek;
    @JdbcTypeCode(SqlTypes.INTEGER) @Column(name="allow_unassigned", nullable=false) public boolean allowUnassigned;
    @Column(name="unassigned_weight", nullable=false) public int unassignedWeight;
    @Column(name="same_location_weight", nullable=false) public int sameLocationWeight;
    @Column(name="night_balance_weight", nullable=false) public int nightBalanceWeight;
    @Column(name="night_start_hour", nullable=false) public int nightStartHour;
    @Column(name="night_end_hour", nullable=false) public int nightEndHour;
    @JdbcTypeCode(SqlTypes.INTEGER) @Column(name="stop_when_feasible", nullable=false) public boolean stopWhenFeasible;
    @Column(name="avoid_specialist_weight", nullable=false) public int avoidSpecialistWeight;
    @Column(name="context_days", nullable=false) public int contextDays;
    @Column(name="diminished_window_seconds", nullable=false) public int diminishedWindowSeconds;
    @Column(name="diminished_ratio_pct", nullable=false) public int diminishedRatioPct;
    @Column(name="weekly_shift_weight", nullable=false) public int weeklyShiftWeight;
    @Column(name="days_off_weight", nullable=false) public int daysOffWeight;
    @Column(name="consecutive_days_weight", nullable=false) public int consecutiveDaysWeight;

    public SolverSettings toDto() {
        SolverSettings s = new SolverSettings(id, structureId, maxSolveSeconds, unimprovedSeconds,
            minimumRestHours, maxShiftsPerDay, desiredDateWeight, undesiredDateWeight,
            balanceWeight, optionalSkillWeight);
        s.setBalanceByHours(balanceByHours); s.setMaxWeeklyHours(maxWeeklyHours);
        s.setMinWeeklyShifts(minWeeklyShifts); s.setMaxWeeklyShifts(maxWeeklyShifts);
        s.setMaxConsecutiveDays(maxConsecutiveDays); s.setMinDaysOffPerWeek(minDaysOffPerWeek);
        s.setAllowUnassigned(allowUnassigned); s.setUnassignedWeight(unassignedWeight);
        s.setSameLocationWeight(sameLocationWeight); s.setNightBalanceWeight(nightBalanceWeight);
        s.setNightStartHour(nightStartHour); s.setNightEndHour(nightEndHour);
        s.setStopWhenFeasible(stopWhenFeasible); s.setAvoidSpecialistWeight(avoidSpecialistWeight);
        s.setContextDays(contextDays); s.setDiminishedWindowSeconds(diminishedWindowSeconds);
        s.setDiminishedRatioPct(diminishedRatioPct); s.setWeeklyShiftWeight(weeklyShiftWeight);
        s.setDaysOffWeight(daysOffWeight); s.setConsecutiveDaysWeight(consecutiveDaysWeight);
        return s;
    }

    public void apply(SolverSettings s) {
        maxSolveSeconds=s.getMaxSolveSeconds(); unimprovedSeconds=s.getUnimprovedSeconds();
        minimumRestHours=s.getMinimumRestHours(); maxShiftsPerDay=s.getMaxShiftsPerDay();
        desiredDateWeight=s.getDesiredDateWeight(); undesiredDateWeight=s.getUndesiredDateWeight();
        balanceWeight=s.getBalanceWeight(); optionalSkillWeight=s.getOptionalSkillWeight();
        balanceByHours=s.isBalanceByHours(); maxWeeklyHours=s.getMaxWeeklyHours();
        minWeeklyShifts=s.getMinWeeklyShifts(); maxWeeklyShifts=s.getMaxWeeklyShifts();
        maxConsecutiveDays=s.getMaxConsecutiveDays(); minDaysOffPerWeek=s.getMinDaysOffPerWeek();
        allowUnassigned=s.isAllowUnassigned(); unassignedWeight=s.getUnassignedWeight();
        sameLocationWeight=s.getSameLocationWeight(); nightBalanceWeight=s.getNightBalanceWeight();
        nightStartHour=s.getNightStartHour(); nightEndHour=s.getNightEndHour();
        stopWhenFeasible=s.isStopWhenFeasible(); avoidSpecialistWeight=s.getAvoidSpecialistWeight();
        contextDays=s.getContextDays(); diminishedWindowSeconds=s.getDiminishedWindowSeconds();
        diminishedRatioPct=s.getDiminishedRatioPct(); weeklyShiftWeight=s.getWeeklyShiftWeight();
        daysOffWeight=s.getDaysOffWeight(); consecutiveDaysWeight=s.getConsecutiveDaysWeight();
    }
}
