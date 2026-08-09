import { api } from './client'

export interface SolverSettings {
  id: number; structure_id: number
  max_solve_seconds: number; unimproved_seconds: number
  minimum_rest_hours: number; max_shifts_per_day: number
  desired_date_weight: number; undesired_date_weight: number
  balance_weight: number; optional_skill_weight: number
  balance_by_hours: boolean; max_weekly_hours: number
  min_weekly_shifts: number; max_weekly_shifts: number
  max_consecutive_days: number; min_days_off_per_week: number
  allow_unassigned: boolean; unassigned_weight: number
  same_location_weight: number; night_balance_weight: number
  night_start_hour: number; night_end_hour: number
  stop_when_feasible: boolean
  /** @brief Soft weight for shifts with a specialist marked "avoid". */
  avoid_specialist_weight: number
  /** @brief Days of shifts adjacent to the window loaded as pinned context (0 = window only). */
  context_days: number
  /** @brief Diminishing-returns termination: observation window in seconds (0 = disabled). */
  diminished_window_seconds: number
  /** @brief Diminishing-returns termination: minimum threshold as a percentage of the initial rate (1-100). */
  diminished_ratio_pct: number
  /** @brief Soft weight for respecting minimum/maximum weekly shifts (0-10). */
  weekly_shift_weight: number
  /** @brief Soft weight for respecting minimum weekly rest days (0-10). */
  days_off_weight: number
  /** @brief Soft weight for respecting the maximum consecutive working days (0-10). */
  consecutive_days_weight: number
}
export const solverSettingsApi = {
  get: (structureId: number) => api.get<SolverSettings>(`/solver-settings?structureId=${structureId}`),
  save: (structureId: number, value: SolverSettings) => api.put<SolverSettings>(`/solver-settings?structureId=${structureId}`, value),
}
