/**
 * @file AuthContext.tsx
 * @brief Session state shared across the application.
 *
 * @details
 * Exposes the signed-in user, their role, and login/logout actions. There are three distinct
 * states, not two: `loading` exists because on the first render we do not yet know whether a
 * session exists — treating it as "not authenticated" would flash the login page for signed-in users.
 *
 * Session expiration is handled here rather than in individual pages: `client.ts` reports each
 * 401 and the context returns the user to login only once, instead of displaying dozens of
 * error toasts while all in-flight requests fail together.
 */

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi, type SessionInfo } from '../api/auth'
import { registerApi } from '../api/register'
import { onUnauthorized, bumpSessionGeneration, setBackupAdminToken } from '../api/client'
import toast from 'react-hot-toast'

interface AuthState {
  /** true until we know whether a session exists: it does not mean "not authenticated". */
  loading: boolean
  session: SessionInfo | null
  isAuthenticated: boolean
  isAdmin: boolean
  /**
   * true when the user table is empty and the mode is standalone: no account exists yet,
   * so the UI must immediately start administrator creation.
   */
  needsFirstAdmin: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  /** @brief Reloads the session and first-user state (call after administrator creation). */
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<SessionInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [needsFirstAdmin, setNeedsFirstAdmin] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const me = await authApi.me()
      setSession(me)
      // Checking whether the first administrator must be created only makes sense when unauthenticated:
      // a signed-in user already has an account.
      if (me.authenticated) {
        setNeedsFirstAdmin(false)
      } else {
        try {
          const status = await registerApi.status()
          setNeedsFirstAdmin(status.firstUser && status.mode === 'standalone')
        } catch {
          setNeedsFirstAdmin(false)
        }
      }
    } catch {
      // Server unreachable: remain anonymous; the login page will report it.
      setSession({ authenticated: false })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void refresh() }, [refresh])

  // Session expired while working: return to login without flooding the UI with toasts. Existing
  // toasts must be dismissed manually; otherwise requests that fail in the meantime leave them
  // stacked over the login page.
  useEffect(() => onUnauthorized(() => {
    toast.dismiss()
    bumpSessionGeneration()
    setSession({ authenticated: false })
  }), [])

  const login = useCallback(async (username: string, password: string) => {
    setSession(await authApi.login(username, password))
    // New session: 401 responses from requests belonging to the previous session must no longer sign the user out.
    bumpSessionGeneration()
    setNeedsFirstAdmin(false)
  }, [])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      // Even if the call fails, the user requested logout, so log out anyway.
      // The backup token is stored in sessionStorage, which is per-tab rather than per-login-session:
      // unless it is cleared, the next user signing in from the same tab would inherit it.
      setBackupAdminToken('')
      bumpSessionGeneration()
      setSession({ authenticated: false })
    }
  }, [])

  const value = useMemo<AuthState>(() => ({
    loading,
    session,
    isAuthenticated: session?.authenticated === true,
    isAdmin: session?.admin === true,
    needsFirstAdmin,
    login,
    logout,
    refresh,
  }), [loading, session, needsFirstAdmin, login, logout, refresh])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth deve stare dentro AuthProvider')
  return ctx
}
