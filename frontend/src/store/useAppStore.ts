/**
 * @file useAppStore.ts
 * @brief Global application store (Zustand + persist).
 *
 * @details
 * Keeps the minimum global state persisted in localStorage:
 * - **currentStructure** — organizational structure selected by the user.
 *   All endpoints requiring `structureId` read it from here.
 * - **language** — current language code (e.g. "it", "en").
 *   Read by `initI18n()` at startup to initialize react-i18next.
 *   Until the user picks one explicitly, the value comes from the browser's preference list
 *   (see {@link detectInitialLanguage}) so that the login and setup screens — which have no
 *   language switcher of their own — open in a language the user actually reads.
 *
 * The localStorage key is `app-store` (defined in `persist.name`).
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Structure } from '../types'
import { detectInitialLanguage } from '../i18n/languages'

/**
 * @brief Shape of the global state.
 */
interface AppState {
  /** @brief Currently selected structure (null = no selection). */
  currentStructure: Structure | null
  /** @brief Sets the active structure. Called by the Navbar when the structure changes. */
  setCurrentStructure: (s: Structure | null) => void

  /** @brief Current language code (default: the browser's preference, else "en"). */
  language: string
  /** @brief Sets the language. Called by the Navbar when the language changes. */
  setLanguage: (lang: string) => void

  /** @brief Shows i18n keys instead of translated text. */
  showTranslationKeys: boolean
  /** @brief Enables/disables persistent display of i18n keys. */
  setShowTranslationKeys: (show: boolean) => void

  /**
   * @brief Shift-window granularity ('week' | 'month'). Cache for the current structure:
   *        the backend is the source of truth (per structure), synchronized by the Navbar
   *        when the structure changes. Default 'month'.
   */
  shiftWindowMode: 'week' | 'month'
  /** @brief Updates the granularity cache (Navbar synchronization + save in Configuration). */
  setShiftWindowMode: (m: 'week' | 'month') => void

  /**
   * @brief When true, navigating in Shift Management to a current or future period without
   *        shifts automatically populates it from the template. Cache for the current structure
   *        (source of truth: backend per structure, synchronized by the Navbar). Default false.
   */
  autoPopulateFromTemplate: boolean
  /** @brief Updates the auto-population cache (Navbar synchronization + save in Configuration). */
  setAutoPopulateFromTemplate: (v: boolean) => void
}

/**
 * @brief Zustand hook for accessing the global store.
 * @example
 * ```tsx
 * const { currentStructure, setCurrentStructure } = useAppStore()
 * ```
 */
export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      currentStructure: null,
      setCurrentStructure: (s) => set({ currentStructure: s }),

      language: detectInitialLanguage(),
      setLanguage: (lang) => set({ language: lang }),

      showTranslationKeys: false,
      setShowTranslationKeys: (show) => set({ showTranslationKeys: show }),

      shiftWindowMode: 'month',
      setShiftWindowMode: (m) => set({ shiftWindowMode: m }),

      autoPopulateFromTemplate: false,
      setAutoPopulateFromTemplate: (v) => set({ autoPopulateFromTemplate: v }),
    }),
    {
      name: 'app-store',
      /**
       * @brief Store schema version, bumped when a persisted field needs re-interpretation.
       *
       * @details Version 2 exists for one reason: installations that had already run the
       *          application carry the old hardcoded `"it"` in localStorage, and a persisted value
       *          wins over detection — so without this migration the fix would reach new browsers
       *          only. An Italian browser still resolves to `it`, so nobody who actually reads
       *          Italian sees a change; a German or English one stops opening in Italian.
       */
      version: 3,
      migrate: (persisted, version) => {
        const state = (persisted ?? {}) as Partial<AppState>
        // Version 1 stored the hardcoded 'it'; version 2 stored the result of browser detection,
        // whose fallback was English at the time. Neither value can be a deliberate choice — the
        // language picker did not exist yet — so both are re-detected once, with the German
        // fallback. From version 3 on, whatever is stored is what the user picked.
        if (version < 3) return { ...state, language: detectInitialLanguage() } as AppState
        return state as AppState
      },
      // Persist only the required fields (exclude functions)
      partialize: (state) => ({
        currentStructure: state.currentStructure,
        language: state.language,
        showTranslationKeys: state.showTranslationKeys,
        shiftWindowMode: state.shiftWindowMode,
        autoPopulateFromTemplate: state.autoPopulateFromTemplate,
      }),
    }
  )
)
