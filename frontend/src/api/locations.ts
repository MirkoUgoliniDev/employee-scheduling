/**
 * @file locations.ts
 * @brief API for managing locations.
 *
 * @details
 * WARNING: `GET /demo-data/getlocations` (list) does NOT return skills.
 * To obtain complete data, including skills, call `get(id)` for each location.
 * LocationsPage does this with `Promise.all(locs.map(l => locationsApi.get(l.id)))`.
 */

import { api } from './client'
import type { Localizzazione } from './labels'

/** @brief Re-export of SkillOption from employees for convenience. */
export interface SkillOption {
  id: number
  name: string
  /** @brief true if the skill is assigned to this location. */
  used?: boolean
}

/**
 * @brief Location as returned by the API.
 */
export interface Location {
  id: number
  /** @brief Unique code (for example, "LOC001"). */
  code: string
  name: string
  /** @brief Display order in the timeline. */
  order: number
  /** @brief Skills required for shifts at this location. */
  requiredSkills?: SkillOption[]
  /** @brief Optional skills (preferred but not required). */
  optionalSkills?: SkillOption[]
  /** @brief If false, the location is disabled (excluded from Shift Management and the solver). */
  active?: boolean
  /** @brief ID of the specialist (physician) assigned to the location, or null if none. */
  specialistId?: number | null
}

/**
 * @brief Payload for creating/updating a location.
 */
export interface LocationPayload {
  id?: number
  code: string
  name: string
  order: number
  requiredSkills: { id: number }[]
  optionalSkills: { id: number }[]
  /** @brief Enabled/disabled status. */
  active?: boolean
  /** @brief ID of the assigned specialist (null = none). */
  specialistId?: number | null
}

/**
 * @brief Location API methods.
 */
export const locationsApi = {
  /**
   * @brief Lists locations for a structure (without skills—use get() for complete details).
   * @param structureId Structure ID
   */
  list: (structureId: number) =>
    api.get<Location[]>(`/demo-data/getlocations?structureId=${structureId}`),

  /**
   * @brief Retrieves a location with complete details, including skills.
   * @param id Location ID
   */
  get: (id: number, structureId: number) =>
    api.get<Location>(`/demo-data/getlocation/${id}?structureId=${structureId}`),

  /**
   * @brief Generates the next available sequential code (for example, "LOC015").
   */
  nextCode: () =>
    api.get<{ code: string }>('/demo-data/next-location-code'),

  /**
   * @brief Creates a new location.
   * @param payload     Location data
   * @param structureId ID of the structure it belongs to
   * @returns { message, id }—ID of the newly created location
   */
  add: (payload: LocationPayload, structureId: number) =>
    api.post<{ message: string; id: number }>(`/demo-data/addlocation?structureId=${structureId}`, payload),

  /**
   * @brief Updates an existing location (the backend returns 200 with no body).
   * @param id      Location ID
   * @param payload New data
   */
  update: (id: number, payload: LocationPayload, structureId: number) =>
    api.put<void>(`/demo-data/updatelocation/${id}?structureId=${structureId}`, payload),

  /**
   * @brief Deletes a location.
   * @param id Location ID
   */
  delete: (id: number, structureId: number) =>
    api.delete<void>(`/demo-data/deletelocation/${id}?structureId=${structureId}`),

  /**
   * @brief Location-name translations (one row per language in the localizations table).
   * @details At runtime they are exposed through /translations as dynamic
   *          `location.<id>` keys; rendering uses t('location.'+id, name) with
   *          automatic fallback to the base name.
   */
  getTranslations: (id: number, structureId: number) =>
    api.get<Localizzazione[]>(`/localizzazioni/locations/${id}?structureId=${structureId}`),

  /**
   * @brief Replaces the location-name translations (replace semantics).
   * @param id    Location ID
   * @param items Non-empty entries only (missing languages use the fallback)
   */
  saveTranslations: (id: number, items: Localizzazione[], structureId: number) =>
    api.put<void>(`/localizzazioni/locations/${id}?structureId=${structureId}`, items),
}
