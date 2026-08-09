/**
 * Shared HTTP client. In development, Vite proxies routes to the backend; in
 * production, requests remain same-origin.
 */
const BASE = ''
const BACKUP_TOKEN_KEY = 'backup_admin_token'

export function setBackupAdminToken(token: string): void {
  if (token.trim()) sessionStorage.setItem(BACKUP_TOKEN_KEY, token.trim())
  else sessionStorage.removeItem(BACKUP_TOKEN_KEY)
}

function requestHeaders(url: string, headers?: HeadersInit): HeadersInit {
  const result = new Headers(headers)
  if (!result.has('Content-Type')) result.set('Content-Type', 'application/json')
  if (url === '/backup' || url.startsWith('/backup/')) {
    const token = sessionStorage.getItem(BACKUP_TOKEN_KEY)
    if (token) result.set('X-Backup-Admin-Token', token)
  }
  return result
}

/** Extracts the machine-readable code from a JSON error response. */
export function errorCode(err: unknown): string | null {
  const parsed = errorBody(err)
  return typeof parsed?.error === 'string' ? parsed.error
    : typeof parsed?.message === 'string' ? parsed.message : null
}

/** Extracts the HTTP status attached to client errors in a type-safe way. */
export function errorStatus(err: unknown): number | null {
  const status = (err as { status?: unknown })?.status
  return typeof status === 'number' ? status : null
}

/** Returns the structured JSON body attached to an error, if present. */
export function errorBody(err: unknown): Record<string, unknown> | null {
  const msg = (err as { message?: unknown })?.message
  if (typeof msg !== 'string') return null
  try {
    const parsed: unknown = JSON.parse(msg)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}

function statusError(message: string, status: number): Error & { status: number } {
  return Object.assign(new Error(message), { status })
}

/**
 * 401 listeners. A session can expire while the user is working; without a single coordination
 * point, every in-flight request would show its own error and the user would see a burst of
 * toasts instead of being returned to the login page only once.
 */
const unauthorizedListeners = new Set<() => void>()

/**
 * Current session generation. This discards late 401 responses: a slow request started before
 * expiration may respond after the user has already authenticated again, and without this
 * counter it would sign them out of a valid session.
 */
let sessionGeneration = 0

/** @brief Signals that the session changed: 401 responses from earlier requests no longer count. */
export function bumpSessionGeneration(): void {
  sessionGeneration++
}

/** @brief Registers a 401 listener. @return Function that unsubscribes it. */
export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener)
  return () => { unauthorizedListeners.delete(listener) }
}

function notifyUnauthorized(url: string, generation: number): void {
  // /auth/me returns 200 even for anonymous callers: its 401 would be a fault, not expiration.
  if (url.startsWith('/auth/')) return
  if (generation !== sessionGeneration) return
  unauthorizedListeners.forEach(listener => listener())
}

/** The account was disabled while the user was working: treat it as the end of the session. */
function isAccountInactive(body: string): boolean {
  try {
    const parsed: unknown = JSON.parse(body)
    return parsed !== null && typeof parsed === 'object'
      && (parsed as { error?: unknown }).error === 'ACCOUNT_INACTIVE'
  } catch {
    return false
  }
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const generation = sessionGeneration
  const res = await fetch(BASE + url, {
    ...options,
    headers: requestHeaders(url, options?.headers),
  })
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText)
    // A role-related 403 (or backup-token 403) is not expiration: react only to
    // ACCOUNT_INACTIVE, or a missing permission would sign the user out.
    if (res.status === 401 || (res.status === 403 && isAccountInactive(text))) {
      notifyUnauthorized(url, generation)
    }
    throw statusError(text || `HTTP ${res.status}`, res.status)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

/**
 * @brief Raw fetch that still participates in end-of-session detection.
 * @details Used by solver paths that exchange payloads not modeled in TypeScript and therefore
 *          cannot go through `request`. Without this, a session expiring during a solve leaves
 *          the user on an unresponsive page instead of returning them to login. The body is read
 *          from a clone, so the caller can consume it normally.
 */
export async function rawFetch(url: string, options?: RequestInit): Promise<Response> {
  const generation = sessionGeneration
  const res = await fetch(BASE + url, options)
  if (!res.ok) {
    const body = await res.clone().text().catch(() => '')
    if (res.status === 401 || (res.status === 403 && isAccountInactive(body))) {
      notifyUnauthorized(url, generation)
    }
  }
  return res
}

/** Downloads a binary response while including the backup administration token. */
export async function downloadBlob(url: string): Promise<Blob> {
  const res = await fetch(BASE + url, { headers: requestHeaders(url) })
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText)
    throw statusError(text || `HTTP ${res.status}`, res.status)
  }
  return res.blob()
}

export const api = {
  get: <T>(url: string) => request<T>(url),
  post: <T>(url: string, body: unknown) =>
    request<T>(url, { method: 'POST', body: JSON.stringify(body) }),
  put: <T>(url: string, body: unknown) =>
    request<T>(url, { method: 'PUT', body: JSON.stringify(body) }),
  delete: <T>(url: string) => request<T>(url, { method: 'DELETE' }),
}
