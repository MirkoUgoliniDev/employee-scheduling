import { api } from './client'

export interface SystemInfo {
  backendVersion: string
  timefoldVersion: string
  quarkusVersion: string
  /** @brief Persistence engine (Hibernate ORM/Panache), with its version from the Quarkus BOM. */
  hibernateVersion: string
  javaVersion: string
  databaseProductName: string
  databaseProductVersion: string
  jdbcDriverName: string
  jdbcDriverVersion: string
  databaseUpdateComponent: 'sqlite' | 'postgresql' | null
}

export interface UpdateInfo {
  status: 'UP_TO_DATE' | 'UPDATE_AVAILABLE' | 'UNAVAILABLE'
  latestVersion: string | null
}

/** @brief Result of checking the installed application version. */
export interface AppUpdate {
  /** @brief Currently running version. */
  current: string
  /** @brief Latest published version; null if the check failed or is disabled. */
  latest: string | null
  /** @brief true only when a newer version actually exists. */
  updateAvailable: boolean
  /** @brief Download page; null when unavailable. */
  releaseUrl: string | null
}

export const systemInfoApi = {
  get: () => api.get<SystemInfo>('/system-info'),
  checkUpdates: (installed: Record<string, string>) =>
    api.post<Record<string, UpdateInfo>>('/system-info/check-updates', installed),
  /**
   * @brief Checks whether a version newer than the installed one exists.
   * @details Never throws due to an unavailable network: the backend still responds with
   *          updateAvailable=false. Restricted to ADMIN users, the only users who can update.
   */
  appUpdate: () => api.get<AppUpdate>('/system-info/app-update'),
  /** @brief Requests shutdown of the local application (ADMIN). */
  exit: () => api.post<{ exiting: boolean }>('/system-info/exit', {}),
}
