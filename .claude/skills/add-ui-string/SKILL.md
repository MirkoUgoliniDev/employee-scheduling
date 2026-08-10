---
name: add-ui-string
description: Add or change any text the user reads inside the application — a label, button, error message, toast, placeholder, tooltip, validation message, or the name of a solver constraint shown in the UI. Use whenever you are about to write a literal string into a React component. Covers the t() call, the five-language catalogue row, the cache bust and the test that enforces it.
---

# Adding a UI string

Every string the user sees is localized in five languages. This is a hard rule: a
hardcoded string ships an untranslatable application.

## 1. Call `t()` in the frontend

```tsx
t('shifts.publishButton', 'Pubblica turni')
```

The second argument is the **Italian** fallback, shown if the catalogue has not loaded.
In-application text is Italian; only comments and docs are English.

Terminology: the application says **"Operatore"**, never "Dipendente" or "Impiegato".

Key naming: `area.thing`, lowerCamelCase after the dot, grouped by screen
(`shifts.…`, `employees.…`, `settings.…`). Reuse an existing key rather than adding a
near-duplicate — check first:

```
grep -n "Pubblica" src/main/resources/i18n/ui-translations.tsv
```

## 2. Add one row to the catalogue

`src/main/resources/i18n/ui-translations.tsv` — **tab-separated, 7 fields**:

```
key<TAB>description<TAB>it<TAB>en<TAB>fr<TAB>es<TAB>de
```

All five languages mandatory and non-empty. Tabs, not spaces — a space-aligned row is a
malformed row. The description field is for whoever edits translations later; write what
the string is for, not a repeat of the string.

**Never** add the key to `DemoDataRepository.seedLabelTranslations*`. That seed is
SQLite-only SQL and runs only under `app.sqlite.legacy-bootstrap=true`; a key added
there is missing on PostgreSQL, where the user then sees Italian in every language. The
TSV is applied via JPA at startup and lands identically on both engines.

The sync is **additive** — it never overwrites a value already in the database, so
translations edited from the Labels page survive restarts. That also means changing the
Italian text of an existing key in the TSV will **not** update a database that already
has it; change it from the Labels page, or state that only fresh installs will see it.

## 3. Bump the client cache key

`frontend/src/i18n/index.ts`:

```ts
const CACHE_KEY = `${CACHE_PREFIX}-v54`   // → v55
```

Clients cache the catalogue in `localStorage`. Without the bump, existing browsers keep
the old catalogue and never see the new key.

## 4. Verify

```
mvn -B -ntp test
```

`UiTranslationCatalogTest` fails the build if a language is empty, a key is duplicated,
or a key exists in the SQLite seed but not in the portable catalogue.

If the change was substantial, also check both engines:

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

## Known gaps

`frontend/src/utils/pdfHelpers.ts` still holds ~50 hardcoded strings, so generated PDFs
always come out in Italian. Solver constraint names in `SolveResultModal` are likewise
unlocalized. If you are working nearby, that is the debt to pay down.
