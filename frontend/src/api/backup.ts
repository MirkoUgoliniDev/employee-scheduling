/**
 * @file backup.ts
 * @brief API for database backup and restore.
 *
 * @details
 * The database engine is transparent to the client: SQLite backups are created with
 * `VACUUM INTO`, while PostgreSQL uses `pg_dump` in custom format. In addition to manual
 * backups, there are scheduled automatic backups (`auto` tag), backups taken before
 * destructive operations (`preop` tag), and—only where restore is supported—a snapshot
 * taken before each restore (`prerestore` tag). Downloads use /backup/download/{filename}.
 *
 * Restore is NOT available for every engine: `restoreSupported` in the settings determines
 * whether the action should be offered to the user.
 */

import { api, downloadBlob } from './client'

/** @brief An existing backup file. */
export interface BackupInfo {
  filename: string
  /** @brief "yyyy-MM-dd HH:mm:ss" timestamp. */
  timestamp?: string
  /** @brief auto | manual | preop | prerestore. */
  tag?: string
  /** @brief Size in bytes. */
  size: number
}

export interface BackupSettings {
  intervalMinutes: number
  autoRetentionDays: number
  otherRetentionDays: number
  /** @brief Maximum number of automatic backups retained (second rotation criterion). */
  autoKeep: number
  /** @brief Maximum number of manual/pre-operation backups retained. */
  otherKeep: number
}

/** @brief Settings plus active-engine capabilities in the shape required by the backend. */
export interface BackupSettingsResponse extends BackupSettings {
  /** @brief false when the engine lacks the tools required for restore. */
  restoreSupported: boolean
}

export interface RestoreResult {
  restored: boolean
  status: 'RESTORED' | 'REJECTED' | 'ROLLED_BACK' | 'INCONSISTENT'
  filename: string
  error?: string
  detail?: string
  recoveryFile?: string
}

export const backupApi = {
  /** @brief Lists backups, newest first. */
  list: () => api.get<BackupInfo[]>('/backup/list'),

  /** @brief Runs a manual backup immediately. */
  run: () => api.post<BackupInfo>('/backup/run', {}),

  settings: () => api.get<BackupSettingsResponse>('/backup/settings'),

  /** @details Sends only the five numbers: `restoreSupported` is a capability, not a setting. */
  saveSettings: (settings: BackupSettings) =>
    api.put<BackupSettingsResponse>('/backup/settings', {
      intervalMinutes: settings.intervalMinutes,
      autoRetentionDays: settings.autoRetentionDays,
      otherRetentionDays: settings.otherRetentionDays,
      autoKeep: settings.autoKeep,
      otherKeep: settings.otherKeep,
    }),

  /** @brief Restores the database from the selected backup (the server first saves the current state). */
  restore: (filename: string) =>
    api.post<RestoreResult>('/backup/restore', { filename }),

  /** @brief Permanently deletes a backup. */
  remove: (filename: string) =>
    api.delete<{ deleted: boolean; filename: string }>(`/backup/${encodeURIComponent(filename)}`),

  download: (filename: string) =>
    downloadBlob(`/backup/download/${encodeURIComponent(filename)}`),
}
