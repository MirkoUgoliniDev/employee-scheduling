/**
 * @file shifts.ts
 * @brief API for shift management and Timefold Solver integration.
 *
 * @details
 * ## Regular flow (shift CRUD)
 * - `schedule()`—loads the entire schedule (employees + locations + shifts)
 * - `get()` / `add()` / `update()` / `delete()`—single-shift CRUD
 * - `skillsForNew()`—skills that can be preselected for a new shift
 *
 * ## Solver flow (Timefold)
 * 1. `solve(structureId)`—retrieves raw JSON from `/demo-data/generate` and posts it to
 *    `POST /schedules`; returns a plain-text `jobId`
 * 2. `getJob(jobId)`—polls `GET /schedules/{jobId}` until `solverStatus !== "SOLVING"`
 * 3. `stopJob(jobId)`—calls `DELETE /schedules/{jobId}` to stop solving
 * 4. `analyze(jobId)`—retrieves the solved schedule and sends it to
 *    `PUT /schedules/analyze` to obtain the constraint analysis
 *
 * ## Timeline colors
 * `getShiftColor()` determines a shift's color in vis-timeline based on overlaps
 * with the assigned employee's availability ranges.
 */

import { api, rawFetch } from './client'
import type { SkillOption } from './employees'

/**
 * @brief Employee assigned to a shift (reduced representation).
 */
export interface ShiftEmployee {
  id: number
  fullName: string
  /** @brief Complete skill catalog with `used` flags (only used skills are held). */
  skills?: SkillOption[]
}

/**
 * @brief Shift as returned by the API.
 */
export interface Shift {
  id: number
  /** @brief ID of the location where the shift takes place. */
  location_id: number
  /** @brief Location name (denormalized for the timeline). */
  location_desc: string
  /** @brief Shift start in ISO format (for example, "2024-01-15T08:00:00"). */
  start: string
  /** @brief Shift end in ISO format. */
  end: string
  /** @brief Required skills (with `used` flags to filter assigned skills). */
  requiredSkills?: SkillOption[]
  /** @brief Optional skills (with `used` flags). */
  optionalSkills?: SkillOption[]
  /** @brief Employee assigned by the solver (null if not assigned yet). */
  employee?: ShiftEmployee
  /** @brief Assigned employee ID (used by `getShiftColor()`). */
  employeeId?: number
  /**
   * @brief Out-of-window context shift (only in the solver payload/solution).
   * @details Loaded as pinned so boundary constraints can see adjacent days
   *          (`context_days` in Solver Settings); it must not be displayed or saved.
   */
  context?: boolean
  /**
   * @brief Shift revision at solve time.
   * @details It must be sent back with assignments: if the shift was modified in the meantime,
   *          the server rejects the save instead of assigning the employee to a shift different
   *          from the one evaluated by the solver.
   */
  version?: number
}

/**
 * @brief Complete schedule returned by `/demo-data/generate`.
 * @details Contains employees with their availability ranges, locations, and shifts.
 *          The `score` field is populated only after solving.
 */
export interface ScheduleData {
  employees: {
    id: number
    fullName: string
    /** @brief Complete skill catalog with `used` flags (only used skills are held). */
    skills?: SkillOption[]
    active?: boolean
    unavailableDates?: { id: number; dateStart: string; dateEnd: string }[]
    undesiredDates?:   { id: number; dateStart: string; dateEnd: string }[]
    desiredDates?:     { id: number; dateStart: string; dateEnd: string }[]
  }[]
  locations: { id: number; name: string; active?: boolean }[]
  shifts: Shift[]
  /** @brief Timefold score (for example, "0hard/-2soft"). Present after solving. */
  score?: string
  /** @brief Solver status: "SOLVING_ACTIVE" | "NOT_SOLVING" | "SOLVING_SCHEDULED". */
  solverStatus?: string
}

/**
 * @brief Data for the shift-edit modal (shift plus location list).
 */
export interface EditShiftData {
  shift: Shift & { requiredSkills: SkillOption[]; optionalSkills: SkillOption[] }
  locations: { id: number; name: string }[]
}

/**
 * @brief Skills available for a new shift (pregrouped by type).
 */
export interface ShiftSkills {
  requiredSkills: SkillOption[]
  optionalSkills: SkillOption[]
}

/**
 * @brief Analysis of a single Timefold constraint.
 * @details Types are `unknown` because the score format varies between Timefold versions
 *          and can be either a string or a structured object.
 */
export interface ConstraintAnalysis {
  /** @brief Constraint package (for example, "org.acme.employeescheduling.domain"). */
  package?: string
  /** @brief Constraint name as passed to `asConstraint()` in the ConstraintProvider. */
  name?: string
  /** @brief Constraint weight in score calculation. */
  weight?: unknown
  /** @brief Net contribution of this constraint to the final score. */
  score?: unknown
  /** @brief Number of tuples that violate (or satisfy) the constraint. */
  matchCount?: number
}

/**
 * @brief Complete Timefold constraint-analysis result.
 * @details Returned by `PUT /schedules/analyze` after solving. Timefold serializes the list
 *          as `constraints`, with the constraint name in `name` (not
 *          `constraintAnalyses`/`constraintRef.constraintName`).
 */
export interface ScoreAnalysis {
  /** @brief Overall solution score. */
  score?: unknown
  /** @brief List of individual analyzed constraints. */
  constraints?: ConstraintAnalysis[]
}

/**
 * @brief Shift and solver API methods.
 */
export const shiftsApi = {
  /** IDs referenced by shifts, used by CRUD pages without loading the full schedule. */
  usage: (structureId: number) =>
    api.get<{ employeeIds: number[]; locationIds: number[] }>(
      `/demo-data/usage?structureId=${structureId}`),

  /**
   * @brief Loads a structure's schedule (employees + locations + shifts).
   * @param structureId Structure ID
   * @param start Optional window start in "yyyy-MM-dd HH:mm:ss" format; absent means all shifts.
   * @param end   Optional exclusive window end. Employees and locations are always complete.
   * @details With start+end, shifts are filtered to the timeline window. Without them, the full
   *          schedule is returned for Locations/Employees/Reports that need every shift.
   */
  schedule: (structureId: number, start?: string, end?: string, activeOnly = false) => {
    const q = new URLSearchParams({ structureId: String(structureId) })
    if (start && end) { q.set('start', start); q.set('end', end) }
    if (activeOnly) q.set('activeOnly', 'true')
    return api.get<ScheduleData>(`/demo-data/generate?${q.toString()}`)
  },

  /**
   * @brief Structure's earliest/latest shift date (min/max), used to position the timeline.
   * @param structureId Structure ID
   */
  dateRange: (structureId: number) =>
    api.get<{ min: string | null; max: string | null }>(
      `/demo-data/shift-date-range?structureId=${structureId}`),

  /**
   * @brief Retrieves shift data for editing (including the location list).
   * @param id          Shift ID
   * @param structureId Structure ID (used to filter locations in the select control)
   */
  get: (id: number, structureId: number) =>
    api.get<EditShiftData>(`/demo-data/editshift/${id}?structureId=${structureId}`),

  /**
   * @brief Retrieves the skills available for a new shift.
   */
  skillsForNew: (structureId: number) =>
    api.get<ShiftSkills>(`/demo-data/get_skills_for_shift?structureId=${structureId}`),

  /**
   * @brief Creates a new shift.
   * @param payload Shift data (start, end, location, skills, etc.)
   */
  add: (payload: object, structureId: number) =>
    api.post<Shift>(`/demo-data/addshift?structureId=${structureId}`, payload),

  /**
   * @brief Updates an existing shift.
   * @param id      Shift ID
   * @param payload New data
   */
  update: (id: number, payload: object, structureId: number) =>
    api.put<{ message: string }>(`/demo-data/updateshift/${id}?structureId=${structureId}`, payload),

  /**
   * @brief Deletes a shift.
   * @param id Shift ID
   */
  delete: (id: number, structureId: number) =>
    api.delete<void>(`/demo-data/delete_shift/${id}?structureId=${structureId}`),

  /**
   * @brief Starts Timefold solving for a structure.
   * @param structureId Structure ID
   * @returns jobId as a plain-text string
   *
   * @details
   * IMPORTANT: retrieves raw JSON from `/demo-data/generate` (without using the TypeScript
   * `ScheduleData` interface) to send Timefold the complete payload expected by the Java
   * backend, including additional fields not modeled by TypeScript.
   */
  solve: async (structureId: number, start?: string, end?: string): Promise<string> => {
    const q = new URLSearchParams({ structureId: String(structureId) })
    if (start && end) { q.set('start', start); q.set('end', end) }
    // The solver must not assign shifts to disabled employees/locations.
    q.set('activeOnly', 'true')
    // Include shifts adjacent to the window as pinned context (`context_days`) so boundary
    // constraints (overlaps, minimum rest, etc.) can see nearby days.
    q.set('context', 'true')
    const problemRes = await rawFetch(`/demo-data/generate?${q.toString()}`)
    if (!problemRes.ok) throw new Error(`HTTP ${problemRes.status}`)
    const problem = await problemRes.json()

    const res = await rawFetch('/schedules', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(problem),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return res.text()
  },

  /**
   * @brief Persists shift→employee assignments from the accepted solver solution.
   * @param assignments List of { shift_id, employee_id|null, version } (null = unassigned)
   * @param start Optional window start: restricts the UPDATE to shifts in the window.
   * @param end Optional window end.
   * @returns { updated: number of updated shifts }
   * @throws 409 SHIFTS_CHANGED if a shift was modified after solving.
   */
  saveAssignments: (
    assignments: { shift_id: number; employee_id: number | null; version?: number }[],
    structureId: number,
    start?: string,
    end?: string,
  ) => {
    const q = `?structureId=${structureId}` + (start && end
      ? `&start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}` : '')
    return api.post<{ updated: number }>(`/demo-data/save-assignments${q}`, assignments)
  },

  /**
   * @brief Populates the window with weekly-template shifts (REPLACES existing shifts).
   * @param structureId Structure ID
   * @param start Window start, "yyyy-MM-dd HH:mm:ss"
   * @param end   Exclusive window end
   * @returns { created: number of shifts created }
   */
  applyTemplate: (structureId: number, start: string, end: string) =>
    api.post<{ created: number }>(
      `/demo-data/apply-template?structureId=${structureId}&start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`, {}),

  /**
   * @brief Retrieves the current status of a solver job.
   * @param jobId Job ID (returned by `solve()`)
   * @details Poll `solverStatus`; solving is complete when it becomes "NOT_SOLVING".
   */
  getJob: (jobId: string) =>
    api.get<ScheduleData>(`/schedules/${jobId}`),

  /**
   * @brief Stops a solver job in progress.
   * @param jobId Job ID
   */
  stopJob: (jobId: string) =>
    api.delete<void>(`/schedules/${jobId}`),

  /**
   * @brief Retrieves and analyzes the constraints of the solution found.
   * @param jobId Completed job ID
   * @returns Detailed Timefold constraint analysis
   *
   * @details
   * 1. Retrieves the solved schedule from `GET /schedules/{jobId}`
   * 2. Sends it to `PUT /schedules/analyze` for constraint analysis
   */
  analyze: async (jobId: string): Promise<ScoreAnalysis> => {
    const schedRes = await rawFetch(`/schedules/${jobId}`)
    if (!schedRes.ok) throw new Error(`HTTP ${schedRes.status}`)
    const solved = await schedRes.json()
    const analysisRes = await rawFetch('/schedules/analyze', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(solved),
    })
    if (!analysisRes.ok) throw new Error(`HTTP ${analysisRes.status}`)
    return analysisRes.json()
  },
}

// ─── Availability colors in the timeline ───────────────────────────────────

/** @brief Shift overlapping an "Unavailable" range → red. */
const UNAVAILABLE_COLOR = '#FF0000'
/** @brief Shift overlapping an "Undesired" range → orange. */
const UNDESIRED_COLOR   = '#FFA500'
/** @brief Shift overlapping a "Preferred" range → green. */
const DESIRED_COLOR     = '#00FF00'
/** @brief No overlap with any range → neutral blue. */
const DEFAULT_COLOR     = '#729fcf'

/**
 * @brief Calculates a shift's vis-timeline color based on employee availability.
 * @param shift     Shift to color
 * @param employees Employee list with availability ranges
 * @returns Hexadecimal color (#RRGGBB)
 *
 * @details Priority: gray (unassigned) > red > orange > green > blue.
 */
export function getShiftColor(shift: Shift, employees: ScheduleData['employees']): string {
  // The solver updates only `employee` (the planning variable); `employeeId` retains the
  // pre-solve database value. Checking `employee` first prevents newly assigned shifts from
  // appearing gray in the solution preview and reassigned shifts from being colored using
  // the OLD employee's availability.
  const effectiveId = shift.employee?.id ?? shift.employeeId
  if (!effectiveId) return '#aaaaaa'
  const employee = employees.find(e => e.id === effectiveId)
  if (!employee) return DEFAULT_COLOR

  const start = new Date(shift.start).getTime()
  const end   = new Date(shift.end).getTime()

  function overlaps(dates?: { dateStart: string; dateEnd: string }[]) {
    return (dates ?? []).some(d => {
      const ds = new Date(d.dateStart).getTime()
      const de = new Date(d.dateEnd).getTime()
      return start < de && end > ds
    })
  }

  if (overlaps(employee.unavailableDates)) return UNAVAILABLE_COLOR
  if (overlaps(employee.undesiredDates))   return UNDESIRED_COLOR
  if (overlaps(employee.desiredDates))     return DESIRED_COLOR
  return DEFAULT_COLOR
}
