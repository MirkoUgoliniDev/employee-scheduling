/**
 * @file languages.ts
 * @brief Supported UI languages and the heuristic that picks the initial one.
 *
 * @details
 * The catalogue carries five languages, but which one a user starts in used to be hardcoded to
 * Italian. That is wrong for this application's target audience: a German MVZ — the deployment
 * the customer-scheduling work is built for — opened the login screen, the registration screen
 * and the first-boot administrator setup in Italian, and those screens have no navbar, so there
 * was no way to switch before signing in. The language had to be understood to be changed, in
 * the same language.
 *
 * `detectInitialLanguage()` therefore follows the browser's preference list and falls back to
 * German, the language of the deployment this work is built for: an operator who has none of the
 * five catalogues configured still lands on a screen they can read. Only an unsupported browser
 * language reaches that fallback — `it`, `en`, `fr`, `es` and `de` all select their own catalogue
 * from the browser settings, so nobody who asked for another supported language is overridden.
 *
 * A language the user picks explicitly is persisted in the store and always wins over detection.
 */

/** @brief Languages offered in the UI, in display order. Labels are native names, not translated. */
export const LANGUAGES = [
  { code: 'it', label: 'Italiano' },
  { code: 'en', label: 'English' },
  { code: 'fr', label: 'Français' },
  { code: 'es', label: 'Español' },
  { code: 'de', label: 'Deutsch' },
]

/** @brief Language codes present in the catalogue, used to validate a browser preference. */
export const LANGUAGE_CODES: readonly string[] = LANGUAGES.map(l => l.code)

/** @brief Language used when the browser expresses no supported preference. */
export const FALLBACK_LANGUAGE = 'de'

/**
 * @brief Picks the initial language from the browser's preference list.
 *
 * @details Only the primary subtag is compared (`de-AT`, `de-CH` and plain `de` all select the
 *          single `de` catalogue entry, which is what we have). Ties are resolved in the
 *          browser's own order, so an explicit choice in the browser settings is respected.
 * @returns A supported language code, or {@link FALLBACK_LANGUAGE}.
 */
export function detectInitialLanguage(): string {
  // Guarded because the store is also imported by non-browser code paths (tests, SSR-like tooling).
  if (typeof navigator === 'undefined') return FALLBACK_LANGUAGE
  const preferred = [navigator.language, ...(navigator.languages ?? [])]
  for (const tag of preferred) {
    if (!tag) continue
    const base = tag.slice(0, 2).toLowerCase()
    if (LANGUAGE_CODES.includes(base)) return base
  }
  return FALLBACK_LANGUAGE
}
