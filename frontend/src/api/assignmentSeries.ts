/**
 * @file assignmentSeries.ts
 * @brief API for recurring customer assignments — the template that generates visits.
 *
 * @details
 * A series is not a plan: it holds the recurrence rule and the visit parameters, and
 * `generate()` creates the concrete assignments of a period from it. Generation is a top-up, so
 * calling it again after moving the planning window forward only adds the missing weeks. A
 * deviation on a single date is an *exception* and never rewrites the series.
 *
 * Backend endpoint: `/assignments/series`.
 */

import { api } from './client'

/** @brief One team line of a series: role, optional qualification, headcount. */
export interface AssignmentSeriesRequirement {
  id?: number
  seriesId?: number
  role: string
  skillId?: number | null
  skillName?: string
  headcount: number
}

/** @brief Recurrence patterns the generator understands. */
export type SeriesPattern = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY_NTH_WEEKDAY'

/**
 * @brief A recurring-visit template.
 * @details `weekdays` holds ISO weekday numbers (1 = Monday … 7 = Sunday) as a comma-separated
 *          string. For `MONTHLY_NTH_WEEKDAY`, `nth` picks the occurrence and 5 means "the last".
 */
export interface AssignmentSeries {
  id: number
  structureId: number
  customerId: number
  customerName: string
  locationId: number
  locationName: string
  title: string
  service: string
  startTime: string
  durationMinutes: number
  priority: number
  fixedTime: boolean
  notes: string
  patternType: SeriesPattern
  weekdays: string
  nth: number
  effectiveFrom: string
  effectiveTo: string
  active: boolean
  occurrenceCount: number
  requirements: AssignmentSeriesRequirement[]
}

/** @brief Payload for creating or updating a series. */
export interface AssignmentSeriesPayload {
  customerId: number
  locationId: number
  title: string
  service?: string
  startTime: string
  durationMinutes: number
  priority?: number
  fixedTime?: boolean
  notes?: string
  patternType: SeriesPattern
  weekdays: string
  nth?: number
  effectiveFrom: string
  effectiveTo: string
  active?: boolean
  requirements: AssignmentSeriesRequirement[]
}

/** @brief What one generation run did. */
export interface SeriesGenerationResult {
  occurrences: number
  generated: number
  skipped: number
  positions: number
}

/** @brief A deviation from the series on a single date. */
export interface AssignmentSeriesException {
  id: number
  seriesId: number
  occurrenceDate: string
  kind: 'CANCELLED' | 'MOVED'
  newDate: string
  newStartTime: string
  notes: string
}

/** @brief Series API methods. */
export const assignmentSeriesApi = {
  list: (structureId: number) =>
    api.get<AssignmentSeries[]>(`/assignments/series?structureId=${structureId}`),

  get: (id: number, structureId: number) =>
    api.get<AssignmentSeries>(`/assignments/series/${id}?structureId=${structureId}`),

  create: (payload: AssignmentSeriesPayload, structureId: number) =>
    api.post<AssignmentSeries>(`/assignments/series?structureId=${structureId}`, payload),

  update: (id: number, payload: AssignmentSeriesPayload, structureId: number) =>
    api.put<AssignmentSeries>(`/assignments/series/${id}?structureId=${structureId}`, payload),

  /** @brief Creates the missing occurrences of the period; idempotent, so it is safe to repeat. */
  generate: (id: number, from: string, to: string) =>
    api.post<SeriesGenerationResult>(
      `/assignments/series/${id}/generate?from=${from}&to=${to}`, {}),

  remove: (id: number, deleteOccurrences = false) =>
    api.delete<void>(`/assignments/series/${id}?deleteOccurrences=${deleteOccurrences}`),

  exceptions: (id: number) =>
    api.get<AssignmentSeriesException[]>(`/assignments/series/${id}/exceptions`),

  /** @brief Cancels one occurrence without touching the other dates of the series. */
  cancelOccurrence: (id: number, date: string, notes = '') =>
    api.post<{ cancelled: boolean; occurrenceDate: string }>(
      `/assignments/series/${id}/exceptions/cancel?date=${date}&notes=${encodeURIComponent(notes)}`, {}),

  /** @brief Moves one occurrence to another date and/or time. */
  moveOccurrence: (id: number, date: string, newDate: string, newTime = '', notes = '') =>
    api.post<{ moved: boolean; occurrenceDate: string; newDate: string }>(
      `/assignments/series/${id}/exceptions/move?date=${date}&newDate=${newDate}`
      + `&newTime=${encodeURIComponent(newTime)}&notes=${encodeURIComponent(notes)}`, {}),

  /** @brief Forgets the deviation of one date; a cancelled visit comes back. */
  clearException: (id: number, date: string) =>
    api.delete<{ generated: number }>(`/assignments/series/${id}/exceptions/${date}`),
}
