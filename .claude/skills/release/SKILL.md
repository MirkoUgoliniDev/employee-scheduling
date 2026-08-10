---
name: release
description: Publish a new version — version bump, tag, GitHub release and Windows MSI. Use when asked to cut, publish or tag a release, or to prepare release notes. Covers the preconditions publish-release.ps1 enforces, what to verify before running it, and the version numbers that are easy to leave stale.
---

# Publishing a release

## Before running anything

1. **The full suite is green.** See the `verify-all` skill — all four checks, including
   the Python checks for `setup/` that Maven and tsc do not cover.
2. **The demo database is clean.** `databases/employee_scheduling.db` is public and gets
   rewritten at runtime. See the `anonymize-db` skill: no real names or emails, no SMTP
   credential, `app_users` empty.
3. **No secrets tracked.** `git ls-files | grep -Ei '\.(env|cfg|dump|db|sqlite|pem|key)$'`
   — the only `.db` that may appear is the demo one.
4. **Published files are English** — README, LICENSE, NOTICE, all of `docs/`,
   `setup/INSTALL.md`, code comments, installer and wizard output.
5. **Version numbers agree.** `pom.xml`, `frontend/package.json`, the installers under
   `scripts/` and `setup/`, and the System Info screen — which carries **hardcoded
   fallback versions** for its dependency list that go stale silently after a dependency
   bump. Check them against `pom.xml` and `package.json`.

The `release-auditor` agent runs items 2-5 as a read-only audit and reports findings.

## Cutting the release

```
scripts\publish-release.ps1 -Version 1.2.3
```

It refuses to proceed unless: the version is three plain numbers, the current branch is
`main`, the working tree is completely clean (untracked files included), and local `main`
is not behind `origin/main`. Those refusals are the safety net — do not work around them
by committing throwaway changes or forcing the branch.

It sets the pom version, tags `vX.Y.Z`, pushes, and moves the pom to
`X.Y.Z-SNAPSHOT` afterwards.

## What CI does

`.github/workflows/release.yml` builds on the tag and runs the `setup/` checks.
`.github/workflows/database-portability.yml` runs the frontend lint/build, the SQLite
tests on Windows and the PostgreSQL tests on Ubuntu against a real server. Wait for it;
a red run on a pushed tag is a release people can already download.

## Windows MSI

`docs/PACKAGING-WINDOWS-MSI.md` is the full procedure. Built artifacts land in `dist/`
alongside `RELEASE-NOTES-<version>.md`.

## Release notes

Write them from the actual commit range, not from memory:

```
git log --oneline v1.2.2..v1.2.3
```

English, grouped by what a user would notice: new features, fixes, and anything
requiring action on upgrade — a new configuration key, a migration that rewrites data,
a new prerequisite such as the PostgreSQL client tools.
