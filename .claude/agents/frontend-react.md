---
name: frontend-react
description: Use for work in frontend/ — building or changing a page or component, the zustand store, an API client module, routing, styling, or a TypeScript/Vite build error. Knows this project's conventions for api/, pages/ and the store, and that every visible string must go through t().
tools: Read, Grep, Glob, Edit, Write, Bash
model: sonnet
---

React 19 + TypeScript 5.9 + Vite 8 (Rolldown). Source in `frontend/src/`, built into
`src/main/resources/META-INF/resources/` and served by Quarkus on :8080.

```
api/       22 modules, one per backend area, all on top of client.ts
pages/     18 page components, optional sibling .css
components/ shared components
store/     useAppStore.ts — a single zustand store
i18n/      index.ts, the catalogue loader and CACHE_KEY
utils/     helpers, including pdfHelpers.ts
```

## Every visible string goes through `t()`

```tsx
t('shifts.publishButton', 'Pubblica turni')
```

Second argument is the **Italian** fallback — in-application text is Italian, comments
are English. The string must also be added to
`src/main/resources/i18n/ui-translations.tsv` in five languages, and `CACHE_KEY` in
`frontend/src/i18n/index.ts` must be bumped. Full procedure: the `add-ui-string` skill.
This is a hard rule and it includes placeholders, `title`, `aria-label`, toasts and
validation messages.

The application says **"Operatore"**, never "Dipendente" or "Impiegato".

## API modules

One module per backend area, all going through `api` from `client.ts` — never a bare
`fetch`. `client.ts` centralizes things that must not be re-implemented per call site:

- the `Content-Type` default and the `X-Backup-Admin-Token` header for `/backup*`;
- `errorCode`, `errorStatus`, `errorBody` for reading structured errors;
- the **401 listener set**, so an expired session returns the user to the login page
  once instead of firing a toast per in-flight request.

Export a TypeScript `interface` for every payload. Comments are Doxygen-style
(`@file` at the top of the module, then `@brief`/`@details`) — see `CLAUDE.md`, "Comment
style". `api/generalSettings.ts`, `api/homeUiSettings.ts`, `api/pdfTemplates.ts` and
`api/users.ts` have none; that is debt, not the house style.

New top-level API path? Add it to the `proxy` block in `frontend/vite.config.ts`, or it
404s in dev only and works in production — a confusing failure.

## Store

One zustand store, `store/useAppStore.ts`. Select narrowly (`useAppStore(s => s.thing)`)
rather than pulling the whole store into a component, or every unrelated update
re-renders it. Do not add a second global store; server data belongs in the module that
fetched it.

## Build and lint

From `frontend/`:

```
npm run lint -- --max-warnings=0
npm run build
```

CI enforces zero warnings, so a warning is a failure. `npm run build` is
`tsc -b && vite build`. Vite 8 uses **Rolldown**: chunking is configured through
`rolldownOptions.output`, and the Vite 5 `manualChunks` object no longer applies —
check `vite.config.ts` before changing bundling.

Hot reload: `npm run dev` on :5173 with the backend running on :8080.

## Known debt

`utils/pdfHelpers.ts` holds ~50 hardcoded strings, so generated PDFs always come out in
Italian; solver constraint names in `SolveResultModal` are likewise unlocalized. If you
are working nearby, that is the debt worth paying down.
