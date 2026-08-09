/**
 * @file labels.ts
 * @brief API for managing labels and localizations.
 *
 * @details
 * The i18n system consists of three layers:
 * 1. **Labels** (`/labels`)—translation keys with descriptions (for example, `btn.save`)
 * 2. **Languages** (`/languages`)—available languages (it, en, fr, es, de)
 * 3. **Translations** (`/translations`)—the `{ langCode: { key: value } }` map used at runtime
 *
 * Per-label translations are managed through `/localizzazioni/labels/{id}`, which accepts
 * an array of `Localizzazione` objects (one per language).
 */

import { api } from './client'

/**
 * @brief Translation label.
 */
export interface Label {
  id: number
  /** @brief Unique key (for example, "btn.save" or "modal.title.employees"). */
  key: string
  /** @brief Human-readable description of the usage context. */
  description: string
  /**
   * @brief For entity-name pseudo-labels (skill.<id>/location.<id>):
   *        target localization table ("skills"/"locations").
   *        Absent for regular UI labels.
   */
  entityType?: string
  /** @brief Entity ID (skill/location) for name pseudo-labels. */
  entityId?: number
}

/**
 * @brief Language available in the system.
 */
export interface Language {
  id: number
  /** @brief ISO 639-1 code (for example, "it", "en", or "fr"). */
  code: string
  /** @brief Full language name (for example, "Italiano"). */
  description: string
  /** @brief If false, the language is not shown in the selector. */
  active: boolean
}

/**
 * @brief Localization record: translated value for a specific entity/language pair.
 * @details Used in the `translationsApi.saveForLabel()` payload.
 */
export interface Localizzazione {
  entityType: string
  entityId: number
  fieldName: string
  languageId: number
  value: string
}

/**
 * @brief Label API methods (CRUD).
 */
export const labelsApi = {
  /** @brief Complete label list. */
  list: () => api.get<Label[]>('/labels'),

  /**
   * @brief Pseudo-labels for localizable entity names (skills/locations).
   * @details They live in the `localizzazioni` table rather than `labels`; the
   *          Localizations page merges them into the list so they can be translated.
   */
  dynamicNames: (structureId: number) =>
    api.get<Label[]>(`/labels/dynamic-names?structureId=${structureId}`),

  /**
   * @brief Creates a new label.
   * @param payload Key and description
   */
  add: (payload: { key: string; description: string; translations?: Record<number, string> }) =>
    api.post<Label>('/labels', payload),

  /**
   * @brief Updates a label's key and description.
   * @param id      Label ID
   * @param payload New data
   */
  update: (id: number, payload: { key: string; description: string; translations?: Record<number, string> }) =>
    api.put<void>(`/labels/${id}`, payload),

  /** @brief Deletes a label by ID. */
  delete: (id: number) => api.delete<void>(`/labels/${id}`),
}

/**
 * @brief Language API methods.
 */
export const languagesApi = {
  /** @brief Lists all languages, both active and inactive. */
  list: () => api.get<Language[]>('/languages'),
}

/**
 * @brief Translation API methods.
 */
export const translationsApi = {
  /**
   * @brief Retrieves all translations as `{ langCode: { key: value } }`.
   * @details Used by the i18n store to load UI strings at runtime.
   */
  all: () => api.get<Record<string, Record<string, string>>>('/translations'),

  /**
   * @brief Saves a label's translation for a specific language.
   * @param labelId    Label ID
   * @param languageId Language ID
   * @param value      Translated text
   */
  saveForLabel: (labelId: number, languageId: number, value: string) =>
    api.put<void>(`/localizzazioni/labels/${labelId}`, [
      { entityType: 'labels', entityId: labelId, fieldName: 'value', languageId, value }
    ]),

  /**
   * @brief Name translations for a generic entity (skills/locations).
   * @details Used by the Localizations page to edit `skill.<id>`/`location.<id>`
   *          pseudo-labels. Read through GET and saved with replace semantics through PUT.
   */
  getForEntity: (entityType: string, entityId: number, structureId?: number) =>
    api.get<Localizzazione[]>(`/localizzazioni/${entityType}/${entityId}${structureId ? `?structureId=${structureId}` : ''}`),

  /** @brief Replaces an entity's name translations. */
  saveForEntity: (entityType: string, entityId: number, items: Localizzazione[], structureId?: number) =>
    api.put<void>(`/localizzazioni/${entityType}/${entityId}${structureId ? `?structureId=${structureId}` : ''}`, items),

  /**
   * @brief Updates ONLY the name translation for one language, preserving the others.
   * @details The PUT endpoint has replace semantics, so this reads the existing set, replaces
   *          the entry for the specified language, and sends everything again. Entity modals
   *          use it so the "Name" field edits the current UI language.
   */
  upsertEntityName: async (entityType: string, entityId: number, languageId: number, value: string, structureId?: number) => {
    const suffix = structureId ? `?structureId=${structureId}` : ''
    const existing = await api.get<Localizzazione[]>(`/localizzazioni/${entityType}/${entityId}${suffix}`)
    const byLang: Record<number, string> = {}
    existing.forEach(l => { if (l.fieldName === 'name') byLang[l.languageId] = l.value ?? '' })
    const v = value.trim()
    if (v) byLang[languageId] = v
    else delete byLang[languageId]
    const items: Localizzazione[] = Object.entries(byLang)
      .filter(([, val]) => val.trim() !== '')
      .map(([lid, val]) => ({ entityType, entityId, fieldName: 'name', languageId: Number(lid), value: val.trim() }))
    await api.put<void>(`/localizzazioni/${entityType}/${entityId}${suffix}`, items)
  },
}
