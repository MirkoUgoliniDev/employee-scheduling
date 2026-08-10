---
name: add-frontend-page
description: Add a new page or screen to the React frontend — the component, the lazy route, the navbar entry, its API module, store wiring and the five-language labels. Use when adding a screen the user navigates to; for a section inside an existing page, extend that page instead.
---

# Adding a page

## 0. Does it want to be a page?

Several routes are `<Navigate>` redirects into sections of `ConfigPage`:
`/skills`, `/structures` and `/labels` all land on `/config?section=…`. Configuration
belongs there. Add a top-level page only for a screen with its own place in the navbar.

## 1. The component

`frontend/src/pages/ThingPage.tsx`, plus an optional sibling `ThingPage.css` — the
convention used by `ShiftsPage`, `HomePage`, `ConfigPage` and `LabelsPage`.

Read a comparable existing page before writing: table layout, loading state, error
handling and modal patterns are consistent across them, and a page that invents its own
looks foreign.

## 2. The route — lazy loaded

In `frontend/src/App.tsx`:

```tsx
const ThingPage = lazy(() => import('./pages/ThingPage'))
…
<Route path="/thing" element={<ThingPage />} />
```

Every page except `LoginPage` and `RegisterPage` is lazy: those two are needed before
anything else and are imported directly. Keep the new one inside the authenticated
`Routes` block, not next to the public routes.

The catch-all `<Route path="*" element={<Navigate to="/shifts" replace />} />` stays
last.

## 3. Navbar and access

Add the entry in `components/Navbar`. If the page is administrative, gate it by role —
the backend enforces `ADMIN` / `CAPOSALA` with `@RolesAllowed`, and the navbar must not
offer a link that will only produce a 403. Auth state comes from
`auth/AuthContext` via `useAuth()`.

Deep links work because of `SpaRoutingFilter` on the backend; a new route needs nothing
extra there, but check the path does not collide with an API path.

## 4. Data

An API module per backend area under `frontend/src/api/`, always on top of `api` from
`client.ts` — never a bare `fetch`, which loses the 401 handling and the error contract.
New top-level API path? Add it to the `proxy` block in `frontend/vite.config.ts`.

Shared state goes in the single zustand store `store/useAppStore.ts`, selected narrowly
(`useAppStore(s => s.thing)`) so unrelated updates do not re-render the page. Do not add
a second store.

## 5. Every visible string

Titles, buttons, table headers, empty states, error and confirmation messages —
`t('thing.someKey', 'Testo italiano')`, one TSV row in five languages, and a `CACHE_KEY`
bump. The `add-ui-string` skill has the full procedure. The application says
**"Operatore"**.

## 6. Verify

```
cd frontend && npm run lint -- --max-warnings=0 && npm run build
mvn -B -ntp test
```

Zero warnings is enforced in CI. `UiTranslationCatalogTest` fails the Maven build if a
language is missing.

Then actually open it — `npm run dev` on :5173 with the backend on :8080. A page that
compiles is not a page that works; check it in at least one non-Italian language, where
a missing key shows up immediately.
