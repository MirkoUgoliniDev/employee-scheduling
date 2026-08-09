/**
 * @file affinity.ts
 * @brief API for employee ↔ specialist compatibility.
 *
 * @details
 * Sparse relationships: a row exists only for NON-neutral pairs.
 * `type`: 2 = avoid (soft constraint), 3 = incompatible (hard constraint).
 * Endpoint backend: `/affinities`.
 */

import { api } from './client'

/** @brief Relationship type: avoid (soft solver constraint). */
export const AFFINITY_AVOID = 2
/** @brief Relationship type: incompatible (hard solver constraint). */
export const AFFINITY_INCOMPATIBLE = 3

/**
 * @brief Employee↔specialist compatibility relationship.
 */
export interface SpecialistAffinity {
  operatorId?: number
  specialistId: number
  /** @brief 2 = avoid, 3 = incompatible. */
  type: number
}

/**
 * @brief Compatibility API methods.
 */
export const affinityApi = {
  /**
   * @brief Non-neutral relationships for an employee.
   * @param operatorId Employee ID
   */
  byOperator: (operatorId: number, structureId: number) =>
    api.get<SpecialistAffinity[]>(`/affinities/operator/${operatorId}?structureId=${structureId}`),

  /**
   * @brief All employee relationships for a structure.
   * @param structureId Current structure ID
   */
  byStructure: (structureId: number) =>
    api.get<SpecialistAffinity[]>(`/affinities?structureId=${structureId}`),

  /**
   * @brief Replaces all relationships for an employee (replace semantics).
   * @param operatorId Employee ID
   * @param affinities Complete list of non-neutral relationships
   */
  replace: (operatorId: number, affinities: SpecialistAffinity[], structureId: number) =>
    api.put<void>(`/affinities/operator/${operatorId}?structureId=${structureId}`, affinities),
}
