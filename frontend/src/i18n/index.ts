/**
 * @file i18n/index.ts
 * @brief Initializes react-i18next with translations loaded from the backend.
 *
 * @details
 * ## Loading flow
 * 1. Checks the localStorage cache (`i18n_cache`) — 1-hour TTL
 * 2. If expired or missing, calls `GET /translations` to obtain the map
 *    `{ langCode: { key: value } }` from the backend (Quarkus)
 * 3. Saves it to the cache and initializes i18next with all available languages
 *
 * ## Usage in components
 * ```tsx
 * import { useTranslation } from 'react-i18next'
 * const { t } = useTranslation()
 * t('btn.save', 'Salva')   // second parameter = fallback when the key is missing
 * ```
 *
 * ## Language switching
 * Call `useAppStore().setLanguage(code)` + `i18n.changeLanguage(code)`.
 * The language is persisted in localStorage through the Zustand store.
 */

import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { api } from '../api/client'

/**
 * @brief localStorage key for the translation cache.
 * @note The version suffix acts as a cache buster: incrementing it invalidates
 *       client caches after backend translations are updated,
 *       forcing a new `/translations` fetch on the next load.
 */
const CACHE_PREFIX = 'i18n_cache'
const CACHE_KEY = `${CACHE_PREFIX}-v59`
/** @brief Cache TTL in milliseconds (10 minutes). */
const CACHE_TTL = 10 * 60 * 1000

/**
 * @brief Reads translations from the localStorage cache.
 * @param ignoreTtl When true, also returns an expired cache: if the backend is unreachable,
 *                  stale translations are better than bare keys.
 * @returns Translation map, or `null` when the cache is missing, unreadable, or expired.
 */
function loadCache(ignoreTtl = false): Record<string, Record<string, string>> | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const { ts, data } = JSON.parse(raw)
    if (!ignoreTtl && Date.now() - ts > CACHE_TTL) return null
    return data
  } catch {
    return null
  }
}

/**
 * @brief Saves translations to localStorage with the current timestamp.
 * @param data Map in the form `{ langCode: { key: value } }`
 *
 * @details Never propagates errors: the cache is an optimization, not a requirement. It first
 *          removes caches from previous versions — the key contains the version number, so
 *          without this cleanup they accumulate indefinitely until the storage quota is full,
 *          after which every write fails.
 */
function saveCache(data: Record<string, Record<string, string>>) {
  try {
    for (const key of Object.keys(localStorage)) {
      if (key.startsWith(CACHE_PREFIX) && key !== CACHE_KEY) localStorage.removeItem(key)
    }
    localStorage.setItem(CACHE_KEY, JSON.stringify({ ts: Date.now(), data }))
  } catch {
    /* Quota full or storage denied: continue with in-memory translations. */
  }
}

/**
 * @brief Initializes i18next by loading translations from the backend or cache.
 * @param lang Initial language code (default "it")
 * @returns Configured i18n instance
 *
 * @details On a network error, an empty object is used as the fallback
 * (the application displays keys instead of translated text).
 */
export async function initI18n(lang = 'it') {
  let translations = loadCache()

  if (!translations) {
    try {
      translations = await api.get<Record<string, Record<string, string>>>('/translations')
      // saveCache does not propagate errors: an unwritable cache must no longer be able to
      // discard already downloaded translations, as happened when the quota was exhausted.
      saveCache(translations)
    } catch {
      // Backend unreachable: an expired cache is better than bare keys.
      translations = loadCache(true) ?? {}
    }
  }

  const resources: Record<string, { translation: Record<string, string> }> = {}
  for (const [langCode, keys] of Object.entries(translations)) {
    resources[langCode] = { translation: keys }
  }

  await i18n.use(initReactI18next).init({
    resources,
    lng: lang,
    fallbackLng: 'it',
    interpolation: { escapeValue: false },
  })

  return i18n
}

/**
 * @brief Reloads translations from the backend and updates i18next at runtime.
 * @details Used after saving dynamic translations (e.g. skill names):
 * bypasses and rewrites the localStorage cache, then replaces resource bundles
 * so new values appear without reloading the page.
 */
export async function refreshTranslations() {
  try {
    const translations = await api.get<Record<string, Record<string, string>>>('/translations')
    saveCache(translations)
    for (const [langCode, keys] of Object.entries(translations)) {
      i18n.addResourceBundle(langCode, 'translation', keys, false, true)
    }
    await i18n.changeLanguage(i18n.language) // force components to re-render
  } catch {
    /* best effort: the cache expires on the next load anyway */
  }
}

export default i18n
