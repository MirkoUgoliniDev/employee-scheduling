/**
 * @file templates.ts
 * @brief API for shift templates (recurring weekly schedule per structure).
 *
 * @details
 * A template shift has no date, but has a day of the week (0=Mon … 6=Sun) and a
 * time of day ("HH:mm:ss"), as well as a location and skills. It is used as a pattern
 * for prepopulating future windows (automatic generation = Part 2).
 */

import { api } from './client'

/** @brief Skill belonging to a template shift (with the `used` flag). */
export interface TemplateSkill {
  id: number
  name?: string
  used?: boolean
}

/** @brief Template shift as returned by the API. */
export interface ShiftTemplate {
  id: number
  structure_id: number
  /** @brief 0=Monday … 6=Sunday. */
  day_of_week: number
  /** @brief Start time within the day, "HH:mm:ss". */
  start_time: string
  /** @brief End time within the day, "HH:mm:ss". */
  end_time: string
  location_id: number
  location_desc?: string
  requiredSkills?: TemplateSkill[]
  optionalSkills?: TemplateSkill[]
}

/** @brief Saved (named) template: header containing a description and shift count. */
export interface SavedTemplate {
  id: number
  structure_id: number
  description: string
  /** @brief Creation timestamp, "yyyy-MM-dd HH:mm:ss". */
  created_at: string
  /** @brief Number of template shifts it contains. */
  item_count: number
}

export const templatesApi = {
  /** @brief All template shifts for the structure. */
  list: (structureId: number) =>
    api.get<ShiftTemplate[]>(`/demo-data/shift-templates?structureId=${structureId}`),

  /** @brief Creates a template shift. */
  add: (structureId: number, payload: object) =>
    api.post<ShiftTemplate>(`/demo-data/shift-template?structureId=${structureId}`, payload),

  /** @brief Updates a template shift. */
  update: (id: number, payload: object, structureId: number) =>
    api.put<void>(`/demo-data/shift-template/${id}?structureId=${structureId}`, payload),

  /** @brief Deletes a template shift. */
  delete: (id: number, structureId: number) =>
    api.delete<void>(`/demo-data/shift-template/${id}?structureId=${structureId}`),

  /** @brief Days (yyyy-MM-dd) with at least one shift, used to highlight them in the calendar. */
  shiftDays: (structureId: number) =>
    api.get<string[]>(`/demo-data/shift-days?structureId=${structureId}`),

  // ─── Saved templates (named, multiple per structure) ───────────────────────

  /** @brief Structure's saved templates (newest first). */
  listSaved: (structureId: number) =>
    api.get<SavedTemplate[]>(`/demo-data/saved-templates?structureId=${structureId}`),

  /**
   * @brief Saves the actual week as a NEW named template (adds rather than replaces).
   * @details Saves only the shift structure (day, time, location, skills)—NEVER employee
   *          assignments: templates do not have an employee field.
   * @param weekStart Monday of the source week, "yyyy-MM-dd HH:mm:ss"
   */
  addSaved: (structureId: number, weekStart: string, description: string) =>
    api.post<{ id: number }>(
      `/demo-data/saved-template?structureId=${structureId}&weekStart=${encodeURIComponent(weekStart)}`, { description }),

  /**
   * @brief Applies a saved template to the [start, end) window (REPLACES the window's shifts).
   * @returns { created } number of shifts created.
   */
  applySaved: (id: number, structureId: number, start: string, end: string) =>
    api.post<{ created: number }>(
      `/demo-data/saved-template/${id}/apply?structureId=${structureId}&start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`, {}),

  /** @brief Template-shift rows belonging to a saved template, for the editor. */
  listSavedItems: (id: number, structureId: number) =>
    api.get<ShiftTemplate[]>(`/demo-data/saved-template/${id}/items?structureId=${structureId}`),

  /** @brief Updates a saved template's description. */
  updateSaved: (id: number, structureId: number, description: string) =>
    api.put<void>(`/demo-data/saved-template/${id}?structureId=${structureId}`, { description }),

  /** @brief Deletes a saved template. */
  removeSaved: (id: number, structureId: number) =>
    api.delete<void>(`/demo-data/saved-template/${id}?structureId=${structureId}`),
}
