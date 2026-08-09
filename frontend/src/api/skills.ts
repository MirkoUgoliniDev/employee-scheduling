/**
 * @file skills.ts
 * @brief API for managing skills.
 *
 * @details
 * Skills are enabled per structure (global catalog plus a per-structure filter).
 * They are assigned to both employees and locations to define minimum shift requirements.
 *
 * The `used` flag returned by `list()` indicates whether the skill is currently
 * assigned to at least one employee or location; SkillsPage uses it to disable
 * the delete button.
 */

import { api } from './client'
import type { Localizzazione } from './labels'

/**
 * @brief Skill as returned by the API.
 */
export interface Skill {
  id: number
  name: string
  /** @brief Display order in the list. */
  order: number
  /** @brief true if the skill is assigned to at least one employee or location. */
  used?: boolean
  /** @brief false if the skill is unavailable for new associations. */
  active: boolean
}

/**
 * @brief Payload for creating/updating a skill (used for batch saves).
 */
export interface SkillPayload {
  /** @brief null for new skills (the backend assigns the ID). */
  id: number | null
  name: string
  order: number
  /** @brief Indicates whether the skill is in use; the backend uses it to prevent deletion. */
  used: boolean
  active: boolean
}

/**
 * @brief Skill API methods.
 */
export const skillsApi = {
  /**
   * @brief Retrieves all skills available in the system.
   * @details The `used` field indicates whether the skill is assigned to any entity.
   */
  list: (structureId: number) =>
    api.get<Skill[]>(`/demo-data/get_skills?structureId=${structureId}`),

  /**
   * @brief Saves the entire skill array (inserts new entries and updates existing ones).
   * @param skills Complete array of skills to persist
   */
  save: (structureId: number, skills: SkillPayload[]) =>
    api.post<void>(`/demo-data/save_skills?structureId=${structureId}`, skills),

  /**
   * @brief Deletes a skill by ID.
   * @param id ID of the skill to delete
   * @throws 409 Conflict if the skill is still in use
   */
  delete: (structureId: number, id: number) =>
    api.delete<void>(`/demo-data/skills/${id}?structureId=${structureId}`),

  /**
   * @brief Skill-name translations (one row per language in the localizations table).
   * @details The translated name is then exposed through /translations as a dynamic
   *          `skill.<id>` key; rendering uses t('skill.'+id, skill.name) with
   *          automatic fallback to the base name.
   */
  getTranslations: (structureId: number, id: number) =>
    api.get<Localizzazione[]>(`/localizzazioni/skills/${id}?structureId=${structureId}`),

  /**
   * @brief Replaces the skill-name translations (replace semantics).
   * @param id    Skill ID
   * @param items Non-empty entries only (missing languages use the fallback)
   */
  saveTranslations: (structureId: number, id: number, items: Localizzazione[]) =>
    api.put<void>(`/localizzazioni/skills/${id}?structureId=${structureId}`, items),
}
