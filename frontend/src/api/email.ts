/**
 * @file email.ts
 * @brief API for emailing employee shifts (with a PDF attachment).
 *
 * @details
 * The PDF is generated client-side (jsPDF) and sent to the backend as base64;
 * the subject and body come from the structure's email template, with the
 * {{Nominativo}} and {{Giorno}} placeholders replaced server-side.
 */

import { api } from './client'

/** @brief Payload for POST /email/send-shifts. */
export interface SendShiftEmailPayload {
  employee_id: number
  structure_id: number
  /** @brief Period label (for example, "29 Jun – 5 Jul 2026") for {{Giorno}}. */
  period_label: string
  /** @brief Period slug ("2026-06-29" for a week, "2026-06" for a month)—send-log key. */
  period_slug: string
  filename: string
  pdf_base64: string
}

/** @brief Send-log entry: latest successful send per employee and period. */
export interface EmailLogEntry {
  employee_id: number
  /** @brief Local "yyyy-MM-dd HH:mm:ss" timestamp. */
  sent_at: string
  sent_to: string
}

/** @brief Converts a Blob to a base64 string in chunks to avoid stack limits. */
export async function blobToBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK)
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  return btoa(binary)
}

/** @brief Global SMTP settings (write-only password, never returned by GET). */
export interface EmailSettings {
  host: string
  port: number
  start_tls: boolean
  username: string
  /** @brief Write-only: empty means keep the saved value. */
  password: string
  mail_from: string
  /** @brief Read-only: true if a password has already been saved. */
  has_password?: boolean
  /** @brief Read-only: true if email sending is available (complete SMTP config or active .env fallback). */
  configured?: boolean
}

export const emailApi = {
  /** @brief Current SMTP settings (without the password). */
  getSettings: () =>
    api.get<EmailSettings>('/email/settings'),

  /** @brief Saves SMTP settings (takes effect immediately, no restart required). */
  saveSettings: (payload: EmailSettings) =>
    api.put<EmailSettings>('/email/settings', payload),

  /** @brief Sends a test email to the specified recipient. */
  sendTest: (to: string) =>
    api.post<{ sent: boolean; to: string }>('/email/settings/test', { to }),

  /** @brief Emails the employee their shifts with the PDF attached. */
  sendShifts: (payload: SendShiftEmailPayload) =>
    api.post<{ sent: boolean; to: string }>('/email/send-shifts', payload),

  /** @brief Structure's send log for a period (latest send per employee). */
  log: (structureId: number, periodSlug: string) =>
    api.get<EmailLogEntry[]>(`/email/log?structureId=${structureId}&periodSlug=${encodeURIComponent(periodSlug)}`),
}
