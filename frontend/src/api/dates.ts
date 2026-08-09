/**
 * @file dates.ts
 * @brief API for managing employee availability time ranges.
 *
 * @details
 * Each employee can have three types of time range:
 * - **Preferred** (id=1): the employee prefers to work during this time
 * - **Undesired** (id=2): the employee would prefer not to work during this time
 * - **Unavailable** (id=3): the employee cannot work during this time
 *
 * Timefold Solver uses this data as soft/hard constraints, and the ranges are displayed
 * with different colors in the timeline through `getShiftColor()`.
 */

import { api } from './client'

/**
 * @brief Employee time range.
 */
export interface EmployeeDate {
  id: number
  employee_id: number
  /** @brief Start date/time in ISO format (for example, "2024-01-15T10:00:00"). */
  dateStart: string
  /** @brief End date/time in ISO format. */
  dateEnd: string
  /**
   * @brief Availability type.
   * - 1 = Preferred
   * - 2 = Undesired
   * - 3 = Unavailable
   */
  dateTypeId: number
}

/**
 * @brief Availability-type definitions with a label and Bootstrap color.
 * @details Used to render badges and the select control in DatesModal.
 */
export const DATE_TYPES = [
  { id: 1, label: 'Desiderata',      bg: 'success' },
  { id: 2, label: 'Indesiderata',    bg: 'warning' },
  { id: 3, label: 'Non disponibile', bg: 'danger'  },
] as const

/** @brief Date-constraint summary row: per-type counts for an employee. */
export interface EmployeeDatesSummary {
  employee_id: number
  full_name: string
  desired: number
  undesired: number
  unavailable: number
  total: number
}

/**
 * @brief Employee time-range API methods.
 */
export const datesApi = {
  /** @brief Per-structure summary: one employee per row, including only those with constraints. */
  summary: (structureId: number) =>
    api.get<EmployeeDatesSummary[]>(`/demo-data/employee-dates-summary?structureId=${structureId}`),

  /**
   * @brief Retrieves all time ranges for an employee.
   * @param employeeId Employee ID
   */
  listForEmployee: (employeeId: number, structureId: number) =>
    api.get<EmployeeDate[]>(`/demo-data/getemployeedates/${employeeId}?structureId=${structureId}`),

  /**
   * @brief Adds a time range for an employee.
   * @param employeeId Employee ID
   * @param payload    Time-range data (without an ID)
   */
  add: (employeeId: number, payload: Omit<EmployeeDate, 'id'>, structureId: number) =>
    api.post<{ id: number }>(`/demo-data/add_employee_dates/${employeeId}?structureId=${structureId}`, payload),

  /**
   * @brief Updates an existing time range.
   * @param dateId  Time-range ID
   * @param payload New data
   */
  update: (dateId: number, payload: EmployeeDate, structureId: number) =>
    api.put<{ message: string }>(`/demo-data/update_employee_dates/${dateId}?structureId=${structureId}`, payload),

  /**
   * @brief Deletes a time range.
   * @param dateId Time-range ID
   */
  delete: (dateId: number, structureId: number) =>
    api.delete<void>(`/demo-data/delete_date/${dateId}?structureId=${structureId}`),

  /**
   * @brief Atomically replaces all date ranges for an employee (transactional batch).
   */
  batchSave: (employeeId: number, dates: Omit<EmployeeDate, 'id'>[], structureId: number) =>
    api.post<{ saved: number }>(`/demo-data/batch_save_employee_dates/${employeeId}?structureId=${structureId}`, dates),
}
