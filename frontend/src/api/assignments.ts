/**
 * @file assignments.ts
 * @brief API for customer assignments — the customer-centric planning object.
 *
 * @details
 * An assignment ("Kundeneinsatz") is one visit at a customer location with a configurable
 * duration and a required team. The backend materialises one shift row per required team
 * member, so an assignment with 1 physician + 2 medical assistants owns three positions.
 * Backend endpoint: `/assignments`.
 */

import { api } from './client'

/** @brief One staffing line: role, optional qualification, headcount. */
export interface AssignmentRequirement {
  id?: number
  assignmentId?: number
  /** "PHYSICIAN" or "MEDICAL_ASSISTANT". */
  role: string
  skillId?: number | null
  skillName?: string
  headcount: number
}

/**
 * @brief One concrete team slot, backed by exactly one shift row.
 * @details `employeeId === null` is an unfilled position, shown explicitly instead of hidden.
 */
export interface AssignmentPosition {
  id: number
  role: string
  skillId?: number | null
  skillName?: string
  employeeId?: number | null
  employeeName?: string
  start: string
  end: string
  pinned: boolean
  version: number
}

/** @brief Planning object as returned by the API. */
export interface CustomerAssignment {
  id: number
  structureId: number
  customerId: number
  customerName: string
  locationId: number
  locationName: string
  title: string
  service: string
  /** "yyyy-MM-dd". */
  assignmentDate: string
  /** "HH:mm". */
  startTime: string
  durationMinutes: number
  endTime: string
  /** 1 = high, 2 = normal, 3 = low. */
  priority: number
  /** FIXED | FLEXIBLE | RECURRING | RESCHEDULED | CANCELLED | COMPLETED. */
  status: string
  fixedTime: boolean
  notes: string
  seriesId?: number | null
  requirements: AssignmentRequirement[]
  positions: AssignmentPosition[]
  requiredHeadcount: number
  filledPositions: number
  unfilledPositions: number
}

/** @brief Payload for creating/updating an assignment. */
export interface AssignmentPayload {
  customerId: number
  locationId: number
  title: string
  service?: string
  assignmentDate: string
  startTime: string
  durationMinutes: number
  priority?: number
  status?: string
  fixedTime?: boolean
  notes?: string
  requirements: AssignmentRequirement[]
}

/** @brief A locked position whose employee no longer satisfies the requirement. */
export interface AssignmentConflict {
  shiftId: number
  assignmentId: number
  employeeName: string
  conflictType: 'ABSENCE' | 'ROLE' | 'LOCATION' | 'SKILL'
  detail: string
}

function periodQuery(structureId: number, from?: string, to?: string): string {
  const q = new URLSearchParams({ structureId: String(structureId) })
  if (from) q.set('from', from)
  if (to) q.set('to', to)
  return q.toString()
}

/** @brief Assignment API methods. */
export const assignmentsApi = {
  /** @brief Assignments of a period, with team and staffing counters. */
  list: (structureId: number, from?: string, to?: string) =>
    api.get<CustomerAssignment[]>(`/assignments?${periodQuery(structureId, from, to)}`),

  /** @brief A single assignment. */
  get: (id: number, structureId: number) =>
    api.get<CustomerAssignment>(`/assignments/${id}?structureId=${structureId}`),

  /** @brief Creates an assignment and materialises its positions. */
  create: (payload: AssignmentPayload, structureId: number) =>
    api.post<CustomerAssignment>(`/assignments?structureId=${structureId}`, payload),

  /**
   * @brief Updates an assignment.
   * @details A changed duration regenerates the positions; already assigned employees stay
   *          on their position as long as role and qualification are unchanged.
   */
  update: (id: number, payload: AssignmentPayload, structureId: number) =>
    api.put<CustomerAssignment>(`/assignments/${id}?structureId=${structureId}`, payload),

  /** @brief Deletes an assignment together with its positions. */
  remove: (id: number, structureId: number) =>
    api.delete<void>(`/assignments/${id}?structureId=${structureId}`),

  /** @brief Locks or unlocks every position of an assignment (@PlanningPin). */
  pin: (id: number, pinned: boolean, structureId: number) =>
    api.post<{ positions: number; pinned: boolean }>(
      `/assignments/${id}/pin?pinned=${pinned}&structureId=${structureId}`, {}),

  /** @brief Rebuilds the positions from the requirements without touching the team. */
  regenerate: (id: number, structureId: number) =>
    api.post<{ positions: number }>(`/assignments/${id}/regenerate?structureId=${structureId}`, {}),

  /** @brief Explicitly unfilled positions of a period. */
  unfilled: (structureId: number, from?: string, to?: string) =>
    api.get<AssignmentPosition[]>(`/assignments/unfilled?${periodQuery(structureId, from, to)}`),

  /** @brief Locked positions invalidated by an absence, role, location or qualification. */
  conflicts: (structureId: number, from?: string, to?: string) =>
    api.get<AssignmentConflict[]>(`/assignments/conflicts?${periodQuery(structureId, from, to)}`),
}
