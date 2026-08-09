/**
 * @file specialists.ts
 * @brief API for managing specialists (outpatient-clinic physicians).
 *
 * @details
 * Independent records linked to a structure, using the same schema as employees
 * but without skills or dates. Backend endpoint: `/specialists`.
 */

import { api } from './client'

/**
 * @brief Specialist as returned by the API.
 */
export interface Specialist {
  id: number
  /** @brief Unique code (for example, "SPE001"). */
  code: string
  firstName: string
  lastName: string
  email?: string
  /** @brief Full name (firstName + lastName), calculated by the backend. */
  fullName?: string
  /** @brief If false, the specialist is disabled. */
  active?: boolean
}

/**
 * @brief Payload for creating/updating a specialist.
 */
export interface SpecialistPayload {
  id?: number
  code: string
  firstName: string
  lastName: string
  email?: string
  active?: boolean
}

/**
 * @brief Specialist API methods.
 */
export const specialistsApi = {
  /**
   * @brief Retrieves all specialists belonging to a structure.
   * @param structureId Current structure ID
   */
  list: (structureId: number) =>
    api.get<Specialist[]>(`/specialists?structureId=${structureId}`),

  /**
   * @brief Retrieves a single specialist.
   * @param id Specialist ID
   */
  get: (id: number, structureId: number) =>
    api.get<Specialist>(`/specialists/${id}?structureId=${structureId}`),

  /**
   * @brief Generates the next available sequential code (for example, "SPE015").
   */
  nextCode: () =>
    api.get<{ code: string }>('/specialists/next-code'),

  /**
   * @brief Creates a new specialist in the specified structure.
   * @param payload     Specialist data
   * @param structureId ID of the structure it belongs to
   */
  add: (payload: SpecialistPayload, structureId: number) =>
    api.post<void>(`/specialists?structureId=${structureId}`, payload),

  /**
   * @brief Updates an existing specialist (the backend returns 200 with no body).
   * @param id      ID of the specialist to update
   * @param payload New data
   */
  update: (id: number, payload: SpecialistPayload, structureId: number) =>
    api.put<void>(`/specialists/${id}?structureId=${structureId}`, payload),

  /**
   * @brief Deletes a specialist.
   * @param id Specialist ID
   */
  delete: (id: number, structureId: number) =>
    api.delete<void>(`/specialists/${id}?structureId=${structureId}`),
}
