/**
 * @file employees.ts
 * @brief API for managing employees.
 *
 * @details
 * Main backend endpoints: `/demo-data/employees`, `/demo-data/addemployee`, etc.
 * Skills are returned with a `used` flag indicating whether the employee has that skill.
 */

import { api } from './client'

/**
 * @brief Skill with a usage flag.
 * @details Used for both employees and locations.
 *          `used = true` means the skill is assigned to the current entity.
 */
export interface SkillOption {
  id: number
  name: string
  /** @brief true if the skill is assigned to the entity (employee or location). */
  used?: boolean
}

/**
 * @brief Employee as returned by the API.
 */
export interface Employee {
  id: number
  /** @brief Unique code (for example, "EMP001"). */
  code: string
  firstName: string
  lastName: string
  email?: string
  /** @brief Full name (firstName + lastName), calculated by the backend. */
  fullName?: string
  /** @brief List of all skills with their `used` flag. */
  skills?: SkillOption[]
  /** @brief If false, the employee is disabled (excluded from Shift Management and the solver). */
  active?: boolean
}

/**
 * @brief Payload for creating/updating an employee.
 */
export interface EmployeePayload {
  id?: number
  code: string
  firstName: string
  lastName: string
  email?: string
  /** @brief Skills to assign (id and name only, without `used`). */
  skills: { id: number; name: string }[]
  /** @brief Enabled/disabled status. */
  active?: boolean
  /** @brief Specialist affinities (updates only, with replace semantics). */
  affinities?: { specialistId: number; type: number }[]
}

/**
 * @brief Employee API methods.
 */
export const employeesApi = {
  /**
   * @brief Retrieves all employees belonging to a structure.
   * @param structureId Current structure ID
   */
  list: (structureId: number) =>
    api.get<Employee[]>(`/demo-data/employees?structureId=${structureId}`),

  /**
   * @brief Retrieves a single employee with their skills.
   * @param id Employee ID
   */
  get: (id: number, structureId: number) =>
    api.get<Employee>(`/demo-data/getemployee/${id}?structureId=${structureId}`),

  /**
   * @brief Generates the next available sequential code (for example, "EMP015").
   */
  nextCode: () =>
    api.get<{ code: string }>('/demo-data/next-employee-code'),

  /**
   * @brief Creates a new employee in the specified structure (the backend returns 201 with no body).
   * @param payload     Employee data
   * @param structureId ID of the structure it belongs to
   */
  add: (payload: EmployeePayload, structureId: number) =>
    api.post<void>(`/demo-data/addemployee?structureId=${structureId}`, payload),

  /**
   * @brief Updates an existing employee (the backend returns 200 with no body).
   * @param id      ID of the employee to update
   * @param payload New data
   */
  update: (id: number, payload: EmployeePayload, structureId: number) =>
    api.put<void>(`/demo-data/updateemployee/${id}?structureId=${structureId}`, payload),

  /**
   * @brief Deletes an employee.
   * @param id Employee ID
   */
  delete: (id: number, structureId: number) =>
    api.delete<void>(`/demo-data/employees/${id}?structureId=${structureId}`),

  /**
   * @brief Retrieves all skills available in the system.
   * @details Used by modals to show the list of selectable skills.
   */
  allSkills: (structureId: number) =>
    api.get<SkillOption[]>(`/demo-data/get_skills?structureId=${structureId}`),
}
