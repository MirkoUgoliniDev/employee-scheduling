package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Solver settings associated with a structure. */
public class SolverSettings {
    @JsonProperty("id") private int id;
    @JsonProperty("structure_id") private int structureId;
    @JsonProperty("max_solve_seconds") private int maxSolveSeconds = 30;
    @JsonProperty("unimproved_seconds") private int unimprovedSeconds = 0;
    @JsonProperty("minimum_rest_hours") private int minimumRestHours = 10;
    @JsonProperty("max_shifts_per_day") private int maxShiftsPerDay = 1;
    @JsonProperty("desired_date_weight") private int desiredDateWeight = 1;
    @JsonProperty("undesired_date_weight") private int undesiredDateWeight = 1;
    @JsonProperty("balance_weight") private int balanceWeight = 1;
    @JsonProperty("optional_skill_weight") private int optionalSkillWeight = 1;
    @JsonProperty("balance_by_hours") private boolean balanceByHours = true;
    @JsonProperty("max_weekly_hours") private int maxWeeklyHours = 0;
    @JsonProperty("min_weekly_shifts") private int minWeeklyShifts = 0;
    @JsonProperty("max_weekly_shifts") private int maxWeeklyShifts = 0;
    @JsonProperty("max_consecutive_days") private int maxConsecutiveDays = 0;
    @JsonProperty("min_days_off_per_week") private int minDaysOffPerWeek = 0;
    @JsonProperty("allow_unassigned") private boolean allowUnassigned = false;
    @JsonProperty("unassigned_weight") private int unassignedWeight = 10;
    @JsonProperty("same_location_weight") private int sameLocationWeight = 0;
    @JsonProperty("night_balance_weight") private int nightBalanceWeight = 0;
    @JsonProperty("night_start_hour") private int nightStartHour = 22;
    @JsonProperty("night_end_hour") private int nightEndHour = 6;
    @JsonProperty("stop_when_feasible") private boolean stopWhenFeasible = false;
    /** Soft weight for shifts with a specialist to "avoid" (employee-specialist compatibility). */
    @JsonProperty("avoid_specialist_weight") private int avoidSpecialistWeight = 1;
    /** Days adjacent to the window loaded as pinned context shifts (0 = window only). */
    @JsonProperty("context_days") private int contextDays = 1;
    /** Diminishing-returns stop: observation window in seconds (0 = disabled). */
    @JsonProperty("diminished_window_seconds") private int diminishedWindowSeconds = 0;
    /** Diminishing-returns stop: minimum threshold as a percentage of the initial improvement rate (1-100). */
    @JsonProperty("diminished_ratio_pct") private int diminishedRatioPct = 25;
    /** Soft weight for respecting minimum/maximum weekly shifts (0-10). */
    @JsonProperty("weekly_shift_weight") private int weeklyShiftWeight = 1;
    /** Soft weight for respecting minimum weekly days off (0-10). */
    @JsonProperty("days_off_weight") private int daysOffWeight = 1;
    /** Soft weight for respecting the maximum consecutive working days (0-10). */
    @JsonProperty("consecutive_days_weight") private int consecutiveDaysWeight = 1;

    public SolverSettings() {}
    public SolverSettings(int id, int structureId, int maxSolveSeconds, int unimprovedSeconds,
            int minimumRestHours, int maxShiftsPerDay, int desiredDateWeight,
            int undesiredDateWeight, int balanceWeight, int optionalSkillWeight) {
        this.id=id; this.structureId=structureId; this.maxSolveSeconds=maxSolveSeconds;
        this.unimprovedSeconds=unimprovedSeconds; this.minimumRestHours=minimumRestHours;
        this.maxShiftsPerDay=maxShiftsPerDay; this.desiredDateWeight=desiredDateWeight;
        this.undesiredDateWeight=undesiredDateWeight; this.balanceWeight=balanceWeight;
        this.optionalSkillWeight=optionalSkillWeight;
    }
    public int getId(){return id;} public void setId(int v){id=v;}
    public int getStructureId(){return structureId;} public void setStructureId(int v){structureId=v;}
    public int getMaxSolveSeconds(){return maxSolveSeconds;} public void setMaxSolveSeconds(int v){maxSolveSeconds=v;}
    public int getUnimprovedSeconds(){return unimprovedSeconds;} public void setUnimprovedSeconds(int v){unimprovedSeconds=v;}
    public int getMinimumRestHours(){return minimumRestHours;} public void setMinimumRestHours(int v){minimumRestHours=v;}
    public int getMaxShiftsPerDay(){return maxShiftsPerDay;} public void setMaxShiftsPerDay(int v){maxShiftsPerDay=v;}
    public int getDesiredDateWeight(){return desiredDateWeight;} public void setDesiredDateWeight(int v){desiredDateWeight=v;}
    public int getUndesiredDateWeight(){return undesiredDateWeight;} public void setUndesiredDateWeight(int v){undesiredDateWeight=v;}
    public int getBalanceWeight(){return balanceWeight;} public void setBalanceWeight(int v){balanceWeight=v;}
    public int getOptionalSkillWeight(){return optionalSkillWeight;} public void setOptionalSkillWeight(int v){optionalSkillWeight=v;}
    public boolean isBalanceByHours(){return balanceByHours;} public void setBalanceByHours(boolean v){balanceByHours=v;}
    public int getMaxWeeklyHours(){return maxWeeklyHours;} public void setMaxWeeklyHours(int v){maxWeeklyHours=v;}
    public int getMinWeeklyShifts(){return minWeeklyShifts;} public void setMinWeeklyShifts(int v){minWeeklyShifts=v;}
    public int getMaxWeeklyShifts(){return maxWeeklyShifts;} public void setMaxWeeklyShifts(int v){maxWeeklyShifts=v;}
    public int getMaxConsecutiveDays(){return maxConsecutiveDays;} public void setMaxConsecutiveDays(int v){maxConsecutiveDays=v;}
    public int getMinDaysOffPerWeek(){return minDaysOffPerWeek;} public void setMinDaysOffPerWeek(int v){minDaysOffPerWeek=v;}
    public boolean isAllowUnassigned(){return allowUnassigned;} public void setAllowUnassigned(boolean v){allowUnassigned=v;}
    public int getUnassignedWeight(){return unassignedWeight;} public void setUnassignedWeight(int v){unassignedWeight=v;}
    public int getSameLocationWeight(){return sameLocationWeight;} public void setSameLocationWeight(int v){sameLocationWeight=v;}
    public int getNightBalanceWeight(){return nightBalanceWeight;} public void setNightBalanceWeight(int v){nightBalanceWeight=v;}
    public int getNightStartHour(){return nightStartHour;} public void setNightStartHour(int v){nightStartHour=v;}
    public int getNightEndHour(){return nightEndHour;} public void setNightEndHour(int v){nightEndHour=v;}
    public boolean isStopWhenFeasible(){return stopWhenFeasible;} public void setStopWhenFeasible(boolean v){stopWhenFeasible=v;}
    public int getAvoidSpecialistWeight(){return avoidSpecialistWeight;} public void setAvoidSpecialistWeight(int v){avoidSpecialistWeight=v;}
    public int getContextDays(){return contextDays;} public void setContextDays(int v){contextDays=v;}
    public int getDiminishedWindowSeconds(){return diminishedWindowSeconds;} public void setDiminishedWindowSeconds(int v){diminishedWindowSeconds=v;}
    public int getDiminishedRatioPct(){return diminishedRatioPct;} public void setDiminishedRatioPct(int v){diminishedRatioPct=v;}
    public int getWeeklyShiftWeight(){return weeklyShiftWeight;} public void setWeeklyShiftWeight(int v){weeklyShiftWeight=v;}
    public int getDaysOffWeight(){return daysOffWeight;} public void setDaysOffWeight(int v){daysOffWeight=v;}
    public int getConsecutiveDaysWeight(){return consecutiveDaysWeight;} public void setConsecutiveDaysWeight(int v){consecutiveDaysWeight=v;}
}
