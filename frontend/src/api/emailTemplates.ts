/**
 * @file emailTemplates.ts
 * @brief API for per-structure email templates (subject plus HTML body with placeholders).
 *
 * @details
 * The subject and body can contain placeholders (for example, {{Nominativo}} and {{Giorno}})
 * that are replaced when sending. One row per structure (backend upsert).
 */

import { api } from './client'

/** @brief Email template as returned by the API. */
export interface EmailTemplate {
  id: number
  structure_id: number
  /** @brief Email subject (text with placeholders). */
  subject: string
  /** @brief Email body (HTML with placeholders). */
  body: string
}

/** @brief Placeholders available in the email template. */
export const EMAIL_PLACEHOLDERS = ['{{Nominativo}}', '{{Giorno}}'] as const

export const emailTemplatesApi = {
  /** @brief Structure's email template (empty if it has not been saved yet). */
  get: (structureId: number) =>
    api.get<EmailTemplate>(`/demo-data/email-template?structureId=${structureId}`),

  /** @brief Saves (upserts) the structure's email template. */
  save: (structureId: number, payload: { subject: string; body: string }) =>
    api.put<EmailTemplate>(`/demo-data/email-template?structureId=${structureId}`, payload),
}
