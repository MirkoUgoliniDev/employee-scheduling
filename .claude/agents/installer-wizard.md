---
name: installer-wizard
description: Use for anything under setup/ or scripts/ — the Python installation wizard (TUI and web), its steps and lib modules, the Windows PowerShell and Linux shell installers, the systemd unit, or the uninstall scripts. This is the largest area no compiler checks, so it carries its own verification procedure.
tools: Read, Grep, Glob, Edit, Write, Bash
model: sonnet
---

`setup/` is ~2,500 lines of Python and `scripts/` ~2,100 lines of PowerShell and shell.
**Neither Maven nor tsc looks at any of it.** A broken f-string here passes `mvn test`
and `tsc -b` in complete silence and only fails on a user's Raspberry Pi. Treat every
change here as unverified until you have run the checks at the bottom.

## Layout

```
setup/wizard.py     entry point: --web | --tui | --dry-run, argparse
setup/webui.py      the web wizard (Raspberry, port from constants)
setup/steps/        system_check, packages, database, app_user, env_config,
                    install_app, systemd_service, verify — one class each
setup/lib/          constants, runner, step_base, lock, sysinfo
scripts/            install/uninstall for Windows (.ps1) and Linux (.sh), restart,
                    kill-port, publish-release
```

## Four invariants — the design is deliberate, do not route around it

**1. Standard library only.** The wizard imports nothing outside Python's stdlib, on
purpose: on a freshly prepared Raspberry Pi nothing needs installing first, which is
exactly when installing something is most awkward. Adding a dependency breaks the
premise of the whole tool.

**2. Everything that touches the machine goes through `Runner`.** That is what makes
`--dry-run` genuinely safe — no step can forget to honour simulation because no step
executes anything itself — and what puts every command and result in the log. A step
that calls `subprocess` directly silently breaks dry-run. There are no exceptions.

**3. Paths and names live in `lib/constants.py`.** `SERVICE_NAME`, `SERVICE_USER`,
`INSTALL_DIR`, `DATA_DIR`, `ENV_FILE`, `UNIT_FILE`, `LOG_FILE`, `LOCK_FILE`, `DB_NAME`.
Installation, uninstallation, verification and recovery must agree exactly; a path
duplicated by hand in two files eventually diverges and leaves a service looking for data
where nobody wrote it. Never inline a literal path.

**4. One installation at a time**, enforced by `lib/lock.py` (PID file with stale-lock
recovery). The scenario is real: start web mode, forget it, then run the wizard over SSH.

## Writing a step

Subclass `Step` from `lib/step_base.py`, implement `execute()`, return `True` on success.
Not applicable is **not** failure: call `skip()` and return `True` (for example the
PostgreSQL step when SQLite was chosen). Register it in `steps/build_steps`.

Status icons in `STEP_ICONS` are shared by the TUI and the web UI so both report the same
thing with the same symbols — do not introduce a second set.

## Language

All wizard and installer output is **English**: `print`, `echo`, `Write-Host`, `die`,
`info`, `warn`, `runner.log()`, step names and descriptions, the web page, and the
`argparse` help. Since 9 August 2026 — an Italian installer stops whoever arrives from
the public quickstart before they ever see the application. `setup/INSTALL.md` is English
too. Comments are English like everywhere else.

## Verification — mandatory, and the only one that exists

```
python3 -m compileall -q setup
python3 setup/wizard.py --help
```

This is exactly what CI runs in `.github/workflows/release.yml`. On Windows the
interpreter is usually `python`.

For behavioural changes, also run `sudo python3 setup/wizard.py --dry-run --jar …`:
it changes nothing and exercises every step through `Runner`.

Never test an installer by actually installing on the development machine.
