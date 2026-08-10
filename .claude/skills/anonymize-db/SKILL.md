---
name: anonymize-db
description: Strip personal data and credentials from a database before it leaves the machine — committing databases/employee_scheduling.db, attaching a database to a bug report, or handing one to a colleague. Covers both SQLite and PostgreSQL, the exact tables and columns, the name-collision trap, and how to verify on the binary rather than on the tables.
---

# Anonymizing a database

`databases/employee_scheduling.db` sits in a **public** repository. Any database that
leaves this machine carries the same constraint: **no real personal data**. What is done
on SQLite must be done identically on PostgreSQL.

## Before anything: a dated backup outside the repo

Anonymization is not reversible. Copy the database somewhere outside the working tree,
with the date in the name, before touching it. You will also need it later to filter
invented names against real ones.

## What to clear

| Table | Columns |
|---|---|
| `employees`, `specialists` | `first_name`, `last_name`, `email` |
| `structures` | `name`, `address`, `phone` |
| `email_log` | `sent_to`, `filename` — the PDF file name contains first and last name |
| `email_settings` | `host`, `username`, `password`, `mail_from` — **a working SMTP credential** |
| `app_users` | **empty it completely** |

`app_users` must end up with zero rows, for two reasons: `password_hash` is a
credential, and an existing ADMIN row makes first-boot admin creation skip itself,
locking whoever installs the application out of their own instance.

## Rules learned the hard way

1. **Filter invented names against the real ones.** The database already held common
   Italian names; drawing from the usual pool (Rossi, Bianchi, Ferrari) recreates dozens
   of names identical to the originals. Use a pool of uncommon names and discard, **in
   SQL against the pre-anonymization backup**, anything that appears there. Do not trust
   a visual scan.
2. **Disjoint pools for first and last names**, so a first name can never equal a last
   name.
3. **Derive emails from the new name**, domain `example.com` — reserved by RFC 2606, so
   it cannot exist and cannot be delivered to by accident.
4. **`VACUUM` at the end, always.** Without it the old strings stay readable in free
   pages and a `grep` on the committed `.db` finds them anyway.
5. **Verify on the binary**, not only on the tables.
6. Anything else you changed by hand, re-check — the file is rewritten at runtime.

## Verification

Every known real string must return 0:

```
grep -a -c -i "<string>" databases/employee_scheduling.db
```

And no domain other than `example.com` may appear:

```
grep -a -o -E "[A-Za-z0-9._%-]+@[A-Za-z0-9.-]+" databases/employee_scheduling.db | sort -u
```

`esempio.it`, `exemple.fr`, `ejemplo.es` and `beispiel.de` are UI translation
placeholders, not data — they are expected.

Finally confirm `app_users` is empty:

```
sqlite3 databases/employee_scheduling.db "select count(*) from app_users;"
```

## What may be committed

Only `databases/employee_scheduling.db`. `.gitignore` excludes `*.db` and then re-admits
this one with `!`. Every other `.db` — `_pre-*` snapshots, `standalone-test.db`, backups
— stays out. Check with `git status --porcelain --untracked-files=all` before committing.
