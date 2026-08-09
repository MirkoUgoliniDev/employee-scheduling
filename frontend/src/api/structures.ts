/**
 * @file structures.ts
 * @brief API for managing organizational structures.
 *
 * @details
 * A structure represents an organization (for example, a hospital or clinic).
 * Employees and locations belong to a structure through the `structure_id` field.
 * Shifts inherit their structure through the `location_id → locations.structure_id` relationship.
 *
 * Endpoint backend: `/structures` (StructureResource.java)
 * The "Default" structure with id=1 is always present as initial seed data.
 */

import { api } from './client'

/**
 * @brief Organizational structure.
 */
export interface Structure {
  id: number
  name: string
  address?: string
  phone?: string
}

/**
 * @brief Structure API methods.
 */
export const structuresApi = {
  /**
   * @brief Retrieves all available structures.
   */
  list: () =>
    api.get<Structure[]>('/structures'),

  /**
   * @brief Creates a new structure.
   * @param payload Structure data (without an ID)
   */
  add: (payload: Omit<Structure, 'id'>) =>
    api.post<Structure>('/structures', payload),

  /**
   * @brief Updates an existing structure.
   * @param id      Structure ID
   * @param payload New data
   */
  update: (id: number, payload: Structure) =>
    api.put<void>(`/structures/${id}`, payload),

  /**
   * @brief Deletes a structure.
   * @param id Structure ID
   * @throws 409 Conflict if the structure has associated employees or locations
   */
  delete: (id: number) =>
    api.delete<void>(`/structures/${id}`),
}
