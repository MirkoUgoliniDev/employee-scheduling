---
name: installer-change
description: Change anything under setup/ or scripts/ — the Python wizard, an installation step, the systemd unit, or the Windows/Linux installer scripts. Use it because this is the one area no compiler checks: it carries its own verification procedure, and skipping it means the error surfaces on a user's machine.
---

# Changing the installer or the wizard

`mvn test` and `tsc -b` **do not look at `setup/` or `scripts/`**. Python is not compiled
and not imported by either build; PowerShell and shell are not checked at all. A broken
f-string, a renamed constant or a typo in a `.ps1` passes every normal check in silence.

## 1. Know which file owns the change

```
setup/wizard.py     entry point: --web | --tui | --dry-run
setup/webui.py      web wizard (Raspberry)
setup/steps/        one class per step: system_check, packages, database, app_user,
                    env_config, install_app, systemd_service, verify
setup/lib/          constants, runner, step_base, lock, sysinfo
scripts/*.ps1       Windows install / uninstall / restart / kill-port / release
scripts/*.sh        Linux install / uninstall / web setup
```

## 2. Respect the four invariants

- **Standard library only** in `setup/`. A fresh Raspberry Pi has nothing installed, and
  that is the point of the tool.
- **Everything that touches the machine goes through `Runner`.** Calling `subprocess`
  from a step silently breaks `--dry-run` and skips the log.
- **Paths and names come from `lib/constants.py`** — `SERVICE_NAME`, `INSTALL_DIR`,
  `DATA_DIR`, `ENV_FILE`, `UNIT_FILE`, `LOG_FILE`, `LOCK_FILE`, `DB_NAME`. Install,
  uninstall, verify and recover must agree exactly. Never inline a literal path.
- **One installation at a time**, via `lib/lock.py`. Do not bypass the lock.

Adding a step: subclass `Step`, implement `execute()`, return `True` on success, register
it in `steps/build_steps`. Not applicable is not failure — `skip()` then return `True`.

## 3. English output

Every message a user sees: `print`, `echo`, `Write-Host`, `die`, `info`, `warn`,
`runner.log()`, step names and descriptions, the web page, the `argparse` help. Comments
too. Rule since 9 August 2026.

## 4. Verify — this is the whole point of the skill

```
python3 -m compileall -q setup
python3 setup/wizard.py --help
```

Exactly what CI runs in `.github/workflows/release.yml`. On Windows the interpreter is
usually `python`. `compileall` catches syntax errors; `--help` catches import errors and
a broken argparse — together they cover the failures that otherwise reach a user.

For behavioural changes, add the simulation run, which touches nothing:

```
sudo python3 setup/wizard.py --dry-run --jar path/to/package.jar
```

For PowerShell, at minimum parse the script without executing it:

```
powershell -NoProfile -Command "[void][System.Management.Automation.Language.Parser]::ParseFile('scripts/install-windows.ps1',[ref]$null,[ref]$null)"
```

**Never test an installer by actually installing on the development machine.** Use
`--dry-run`, or a VM.

## 5. If you changed paths, names or the uninstall path

Check the counterpart. An install that writes somewhere the uninstall does not clean, or
a renamed service the verify step still looks for under the old name, is the classic
failure here — and it only shows up on a real machine.
