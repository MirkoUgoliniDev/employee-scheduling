/**
 * @file customers.ts
 * @brief API for customer master data (MVZ customer-centric scheduling).
 *
 * @details
 * A customer owns a set of locations and the default requirements that every new
 * assignment for that customer starts from. Backend endpoint: `/customers`.
 */

import { api } from './client'

/**
 * @brief Customer as returned by the API.
 */
export interface Customer {
  id: number
  /** @brief Unique short code, for example "K001". */
  code: string
  name: string
  address?: string | null
  phone?: string | null
  active: boolean
  /** @brief Default start time of an assignment, "HH:mm". */
  defaultStartTime?: string | null
  defaultDurationMinutes?: number | null
  /** @brief Skill preselected for the default requirements. */
  defaultSkillId?: number | null
  defaultSkillName?: string | null
  /** @brief Free-form note about preferred recurrence, for example "every 2nd Monday". */
  recurrencePattern?: string | null
  restrictions?: string | null
  /** @brief Locations belonging to this customer. */
  locationIds: number[]
}

/**
 * @brief Payload for creating/updating a customer.
 */
export interface CustomerPayload {
  code: string
  name: string
  address?: string | null
  phone?: string | null
  active?: boolean
  defaultStartTime?: string | null
  defaultDurationMinutes?: number | null
  defaultSkillId?: number | null
  recurrencePattern?: string | null
  restrictions?: string | null
  locationIds?: number[]
}

/**
 * @brief Customer API methods.
 */
export const customersApi = {
  /**
   * @brief Retrieves all customers of a structure.
   * @param structureId Current structure ID
   */
  list: (structureId: number) =>
    api.get<Customer[]>(`/customers?structureId=${structureId}`),

  /**
   * @brief Retrieves a single customer.
   */
  get: (id: number) => api.get<Customer>(`/customers/${id}`),

  /**
   * @brief Creates a customer.
   */
  create: (payload: CustomerPayload, structureId: number) =>
    api.post<Customer>(`/customers?structureId=${structureId}`, payload),

  /**
   * @brief Updates a customer.
   */
  update: (id: number, payload: CustomerPayload) =>
    api.put<Customer>(`/customers/${id}`, payload),

  /**
   * @brief Deletes a customer.
   * @throws 409 CUSTOMER_IN_USE if assignments still reference it.
   */
  remove: (id: number) => api.delete<void>(`/customers/${id}`),
}
