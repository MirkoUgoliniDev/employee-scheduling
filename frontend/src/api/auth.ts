/**
 * @file auth.ts
 * @brief Login, logout, and session status.
 *
 * @details
 * Login does not go through `api`: credentials are sent to `/j_security_check` as form-encoded
 * data because Quarkus handles that endpoint itself (form auth). This means no password
 * comparison is implemented manually, either here or on the server.
 *
 * The result is NOT inferred from the POST response—which varies with redirect configuration—
 * but by reading the state again from `/auth/me`, the authoritative source.
 */

import { api } from './client'

export interface SessionInfo {
  authenticated: boolean
  username?: string
  displayName?: string
  roles?: string[]
  admin?: boolean
  /**
   * @brief true on the single-PC desktop package, false on a shared server installation.
   * @details Only sent when authenticated. Drives actions whose meaning changes with the
   *          deployment: closing the application is closing a window on a desktop and a service
   *          outage on a server, so a CAPOSALA may do it only on the former — see
   *          `SystemInfoResource.exit()`, which answers 403 EXIT_REQUIRES_ADMIN otherwise.
   */
  standalone?: boolean
  /** @brief Failure reason when authenticated=false: INACTIVE for pending/disabled accounts. */
  reason?: string
}

/** @brief Invalid-credentials error, distinct from a network failure. */
export class InvalidCredentialsError extends Error {
  constructor() {
    super('INVALID_CREDENTIALS')
    this.name = 'InvalidCredentialsError'
  }
}

/** @brief The account exists but is inactive (pending approval or disabled). */
export class InactiveAccountError extends Error {
  constructor() {
    super('INACTIVE_ACCOUNT')
    this.name = 'InactiveAccountError'
  }
}

export const authApi = {
  /** @brief Current session status. Returns 200 even for anonymous callers. */
  me: () => api.get<SessionInfo>('/auth/me'),

  /**
   * @brief Authenticates and returns the newly created session.
   * @throws InvalidCredentialsError if the credentials are invalid.
   * @throws InactiveAccountError if the account is inactive (pending approval or disabled).
   */
  async login(username: string, password: string): Promise<SessionInfo> {
    const body = new URLSearchParams({ j_username: username, j_password: password })
    await fetch('/j_security_check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
      credentials: 'same-origin',
      // A 302 to the landing page is not an error: /auth/me determines the outcome.
      redirect: 'manual',
    }).catch(() => undefined)

    const session = await authApi.me()
    if (!session.authenticated) {
      if (session.reason === 'INACTIVE') throw new InactiveAccountError()
      throw new InvalidCredentialsError()
    }
    return session
  },

  logout: () => api.post<{ loggedOut: boolean }>('/auth/logout', {}),
}
