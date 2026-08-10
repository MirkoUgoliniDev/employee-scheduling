# Installing on Linux

Everything needed to run Employee Scheduling as a service on a Linux machine: Raspberry Pi,
mini-PC, virtual machine, or a real server.

There are three ways in, and they differ in how much you want to decide yourself:

| Route | Who it is for | Where it is documented |
|---|---|---|
| **Browser wizard** | A headless Raspberry Pi. Start it over SSH, finish it from a browser, watch the steps advance | [`../setup/INSTALL.md`](../setup/INSTALL.md) — the complete guide |
| **One-line script** | Anyone who wants it installed and working without questions | § 2 below |
| **By hand** | Someone adapting the unit, the paths or the hardening to their own conventions | § 4 below |

The first two are the same installation from two different front doors. The wizard has its
own document because it is a program in its own right, with a preflight that refuses before
touching the machine; this page does not repeat it.

---

## 1. Prerequisites

| Component | Version | Notes |
|---|---|---|
| Linux with systemd | — | Raspberry Pi OS, Debian, Ubuntu (`apt`) or Fedora, RHEL, Rocky, AlmaLinux (`dnf`) |
| Python 3 | any | Only for the wizard; present on every distribution above |
| Root privileges | — | `sudo` |
| Java | 17+ | **Installed by the installer**, not a prerequisite |
| PostgreSQL | 14+ | **Installed by the installer** when the engine is PostgreSQL |

Nothing else. In particular the source repository, Maven and Node.js are **not** needed on the
target machine: the installers download the jar built for the chosen engine from GitHub
Releases.

```bash
# Only if you plan to build on the machine, which is not recommended on a Pi
sudo apt update
sudo apt install -y openjdk-21-jdk maven nodejs npm git curl
```

On distributions without a JDK 21 package:

```bash
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-21-jdk maven
```

### Why the jar is not built here

Building needs Maven, Node.js 20 or later and several minutes of CPU. On Debian bookworm —
the basis of Raspberry Pi OS — Node stops at 18, which is not enough for this frontend.

---

## 2. The script

```bash
git clone https://github.com/MirkoUgoliniDev/employee-scheduling.git
cd employee-scheduling
sudo ./scripts/install-linux.sh --engine postgresql
```

It installs Java and, on PostgreSQL, the database server; creates the role and the database;
copies the jar to `/opt/employee-scheduling`; writes the configuration and the systemd unit;
starts the service and waits for it to answer.

| Option | Default | What it does |
|---|---|---|
| `--engine postgresql\|sqlite` | `postgresql` | Data engine. Must match the engine the jar was built for — the script checks and refuses a mismatch |
| `--jar PATH` | — | Use a local package. Without it, a jar already in `target/` is used, otherwise the latest Release is downloaded |
| `--port N` | `8080` | Application port. Below 1024 is refused: the service runs unprivileged |
| `--data-dir PATH` | `/var/lib/employee-scheduling` | Backups, settings and, with SQLite, the database |
| `--db-password SECRET` | generated | PostgreSQL password. Letters and digits only — the script refuses anything else, because the value travels through a JDBC URL, an environment file and a `psql` command line, each with its own escaping |
| `--demo-data` | off | Loads the portable sample dataset — locations, employees, specialists, skills, unassigned shifts. No users, no passwords |
| `--from-source` | off | Builds on the machine. Slow on a Pi, and requires Node 20+ |
| `--no-service` | off | Installs without registering the systemd unit |
| `--smtp-host`, `--smtp-user`, `--smtp-pass` | empty | Email delivery |
| `--smtp-port` | `587` | |
| `--smtp-from` | the SMTP username | |
| `--yes`, `-y` | off | No questions, for automation |

**Re-running it is how you update.** Engine, port and data directory are read back from the
existing installation unless you pass them explicitly, and the script says which values it
reused. The session key, the database password, the whole SMTP block and the demo-data setting
are preserved too — so nobody is logged out, and an update does not silently stop e-mail.

> **`-Dquarkus.profile` is fixed when the jar is built.** The data engine and the Flyway
> migration directories are baked in; no environment variable changes them afterwards.
> Installing a SQLite-built jar with `--engine postgresql` makes the service start and die
> with *"Driver does not support the provided URL"*. A jar built with no profile at all is
> worse, because it is silent: migrations never run, tables are never created, and the
> application misbehaves with no error pointing at the cause. The script reads the engine baked
> into the jar and refuses before touching the machine.

---

## 3. Which engine

| | SQLite | PostgreSQL |
|---|---|---|
| What it is | One file in the data directory | A service on the machine |
| Concurrency | One writer at a time | Real concurrent access |
| Registration | Username and password, no email | Email with a one-time passcode |
| Backups | `VACUUM INTO`, consistent while running | `pg_dump -Fc`, restore with `pg_restore` |
| Choose it when | One person plans the shifts | Several people plan together |

The choice is not easily reversed: it is baked into the jar, and the data does not migrate
between engines by itself.

---

## 4. Installing by hand

Only worth it if you are adapting something. The script and the wizard generate an equivalent
unit and stay in sync with the code.

### 4.1 Service user and data directory

```bash
sudo useradd --system --no-create-home --home-dir /var/lib/employee-scheduling \
     --shell /usr/sbin/nologin employee-scheduling
sudo mkdir -p /var/lib/employee-scheduling
sudo chown -R employee-scheduling:employee-scheduling /var/lib/employee-scheduling
sudo chmod 750 /var/lib/employee-scheduling
```

A system user with no shell and no private home: if the service is ever compromised, it has
neither an environment to move through nor a way to log in.

### 4.2 PostgreSQL, when that is the engine

Section 2 installs the server; this route does not, so start there:

```bash
sudo apt install -y postgresql postgresql-client
sudo systemctl enable --now postgresql
```

```bash
sudo -u postgres psql -c "CREATE ROLE employee_scheduling LOGIN PASSWORD 'choose-a-strong-password';"
sudo -u postgres psql -c "CREATE DATABASE employee_scheduling OWNER employee_scheduling;"
sudo -u postgres psql -d employee_scheduling -c "ALTER SCHEMA public OWNER TO employee_scheduling;"
```

The third line is not optional on PostgreSQL 15 and later: a role that does not own the
`public` schema cannot write to it, and Flyway's first migration fails with a permissions
error that looks like a credentials problem and is not.

### 4.3 The environment file

`/etc/employee-scheduling.env`, mode `640 root:employee-scheduling` — the service reads it, no
other user can:

```ini
QUARKUS_PROFILE=sqlite
QUARKUS_HTTP_PORT=8080
APP_DATA_DIR=/var/lib/employee-scheduling
AUTH_SESSION_KEY=a-cryptographic-key-of-more-than-16-characters
BACKUP_ADMIN_TOKEN=a-long-random-token
APP_DEMO_DATA=false
QUARKUS_MAILER_MOCK=false
QUARKUS_MAILER_HOST="smtp.example.com"
QUARKUS_MAILER_PORT=587
QUARKUS_MAILER_USERNAME="no-reply@example.com"
QUARKUS_MAILER_PASSWORD="smtp-password"
QUARKUS_MAILER_FROM="no-reply@example.com"
QUARKUS_MAILER_START_TLS=REQUIRED
```

With PostgreSQL, replace the profile and add the connection:

```ini
QUARKUS_PROFILE=postgresql
DATABASE_URL=jdbc:postgresql://localhost:5432/employee_scheduling
DATABASE_USERNAME=employee_scheduling
DATABASE_PASSWORD=the-password-you-chose
```

Two values are worth understanding rather than copying:

- **`AUTH_SESSION_KEY` must not be shorter than 16 characters** (32+ recommended). Below
  that, Quarkus refuses to build the session manager and *every sign-in* fails with an opaque
  500 that never mentions the key.
- **`BACKUP_ADMIN_TOKEN` must not be empty, and not shorter than 32 bytes.** Either way the
  application starts normally and the backup API answers 503 while scheduled backups keep
  running — a failure nobody notices until they open the Backup page.

Quoting matters: systemd applies C-style escapes inside quotes, so a `"` or a `\` in an SMTP
password must be escaped, or Quarkus receives a truncated password and delivery fails with
"authentication failed" and nothing linking it to that character.

### 4.4 The systemd unit

`/etc/systemd/system/employee-scheduling.service`:

```ini
[Unit]
Description=Employee Scheduling — employee shift planning
Documentation=https://github.com/MirkoUgoliniDev/employee-scheduling
# network-online.target on BOTH lines: pulling it in with Wants while ordering
# After=network.target means never actually waiting for the network.
After=network-online.target
Wants=network-online.target
# Do not start before the data directory's filesystem is mounted. USB disk
# enumeration is slow on a Raspberry Pi: without this, after a reboot the service
# starts before the mount, finds an empty directory, and Flyway creates a new
# database that then disappears behind the mount.
RequiresMountsFor=/var/lib/employee-scheduling

[Service]
Type=simple
User=employee-scheduling
Group=employee-scheduling
WorkingDirectory=/var/lib/employee-scheduling
EnvironmentFile=/etc/employee-scheduling.env
# -Dapp.data.dir and APP_DATA_DIR are equivalent: AppDataDirectory reads the system
# property first, then the variable. What matters is that ONE of the two is present:
# it is what moves database, backups and settings together, instead of writing them
# relative to WorkingDirectory.
ExecStart=/usr/bin/java -Dapp.data.dir=/var/lib/employee-scheduling -jar /opt/employee-scheduling/employee-scheduling-<engine>-runner.jar
Restart=on-failure
RestartSec=10
TimeoutStopSec=30

# Confinement: the service writes only to its data directory.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictNamespaces=true
LockPersonality=true
ReadWritePaths=/var/lib/employee-scheduling

[Install]
WantedBy=multi-user.target
```

`ProtectSystem=strict` makes the whole filesystem read-only, so **`ReadWritePaths` is
mandatory beside it** or the service cannot write anything. And if the data directory is under
`/home` or `/root`, `ProtectHome` must be `false`: otherwise the service cannot see its own
directory and fails with an error that does not explain why.

With PostgreSQL, add ` postgresql.service` to both `After=` and `Wants=`.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now employee-scheduling
systemctl status employee-scheduling
journalctl -u employee-scheduling -f
```

### 4.5 Running without a service

```bash
sudo -u employee-scheduling java -Dapp.data.dir=/var/lib/employee-scheduling \
     -jar /opt/employee-scheduling/employee-scheduling-<engine>-runner.jar
```

Mind the file name: the asset downloaded from GitHub Releases is named after the **engine**
(`employee-scheduling-postgresql-runner.jar`), while a jar you build yourself carries the
version (`employee-scheduling-1.2.9-SNAPSHOT-runner.jar`). Use the name of the file you
actually copied into `/opt`.

Without `-Dapp.data.dir` the database is created in `./databases`, relative to the working
directory — which is rarely where you meant.

---

## 5. First startup

1. Open `http://<address>:8080`.
2. Register. **The first account becomes the active administrator.**
3. On PostgreSQL the registration asks for an email and a one-time passcode; on SQLite it asks
   for a username and a password and nothing else.
4. Later accounts are created as head nurses awaiting approval; an administrator activates
   them from the Users page.

Without SMTP configured, the registration code appears only in the service log
(`journalctl -u employee-scheduling`) — which is why standalone mode exists at all.

---

## 6. Where the data lives

| | SQLite | PostgreSQL |
|---|---|---|
| Database | `/var/lib/employee-scheduling/employee_scheduling.db` | In the PostgreSQL cluster (`/var/lib/postgresql/…`) |
| Backups | `/var/lib/employee-scheduling/backups/` | Same directory, `.dump` files |
| Backup settings | `/var/lib/employee-scheduling/` | Same |
| Service log | journald | journald |

With PostgreSQL, **copying the data directory does not take the database with it** — only the
backups. There is no log file: read it with `journalctl`.

> Installations predating August 2026 have the SQLite file under the old name
> `large_data.db`. It is renamed to `employee_scheduling.db` automatically at the first
> startup after the update, together with its `-wal` and `-shm` companions. Nothing to do by
> hand — but do not be surprised by the new name.

---

## 7. Updating

Re-run the same route you installed with; both preserve the data.

```bash
sudo ./scripts/install-linux.sh --jar ~/employee-scheduling-<version>-runner.jar
```

Schema migrations apply themselves at the first startup. As with any schema change, have a
recent backup — the application takes one before every destructive operation, but a copy you
control is a copy you control.

**With the Raspberry launcher, pass the engine again**: `start-web-setup.sh` always passes
`--engine`, so on an SQLite installation `sudo ./scripts/start-web-setup.sh --engine sqlite`
is required or it falls back to PostgreSQL.

---

## 8. Backups

Automatic backups run every 30 minutes by default, keeping the most recent 48 files, and one
is taken before every destructive operation. Both are configurable from Configuration →
Backup.

Restoring is not a file copy: the backup is staged, validated, its schema compared with the
live one, and a pre-restore snapshot is taken. It then either applies completely or leaves the
database untouched, and says which of the two happened.

The Backup section of the interface needs a token, generated by the installer and never
displayed:

```bash
sudo grep BACKUP_ADMIN_TOKEN /etc/employee-scheduling.env
```

From an address other than `localhost` the backup API answers **426** until the traffic goes
over HTTPS. On a headless server the simplest route is the same SSH tunnel used for the
wizard — from the application's point of view the request then comes from `localhost`:

```bash
ssh -L 8080:localhost:8080 pi@raspberrypi.local
# then open http://localhost:8080
```

The alternatives are a reverse proxy with a certificate, or — only on a network you genuinely
trust — adding `BACKUP_ADMIN_REQUIRE_TLS_FOR_REMOTE=false` to the environment file and
restarting.

On PostgreSQL, backups need `pg_dump` and restores need `pg_restore`. The installer warns when
they are missing, and the application disables both functions by itself rather than failing
later.

---

## 9. Uninstalling

```bash
sudo /opt/employee-scheduling/uninstall-linux.sh            # removes service and application, KEEPS the data
sudo /opt/employee-scheduling/uninstall-linux.sh --purge    # removes everything, see below
```

Both installers copy the uninstaller next to the application, so it is there even after the
extracted installer archive is gone. From a checkout, `sudo ./scripts/uninstall-linux.sh` is
equivalent.

Without `--purge`, the data directory, the service user and the configuration file remain —
the last one because it still holds the password of a database that still exists.

`--purge` also removes the data, the backups, the database **and its PostgreSQL role**, the
configuration file, the service user and the installer cache. It asks you to type `DELETE` in
full, and it refuses outright if the configured data directory looks like a system directory.

---

## 10. When something goes wrong

| Symptom | Likely cause | What to do |
|---|---|---|
| The service does not start | Almost always visible in the last lines | `journalctl -u employee-scheduling -n 60 --no-pager` |
| *"Driver does not support the provided URL"* | Jar built for the other engine | Reinstall with the jar matching `--engine`, or rebuild with `-Dquarkus.profile=<engine>` |
| Starts, then no tables and odd behaviour, no errors | Jar built with **no** profile: Flyway never ran | Rebuild with an explicit `-Dquarkus.profile` |
| 500 on every sign-in | `AUTH_SESSION_KEY` shorter than 16 characters | Set a longer one and restart |
| Backup page dead, `/backup` answers 503 | `BACKUP_ADMIN_TOKEN` empty or shorter than 32 bytes | Set a proper token in the environment file |
| *"PostgreSQL appears started but the server does not respond"* | On Debian the real service is the cluster, not `postgresql.service` | `pg_lsclusters`, then `sudo pg_ctlcluster <version> main start` |
| Connection refused with correct credentials | `pg_hba.conf` missing the local rule | Add `host all all 127.0.0.1/32 scram-sha-256` |
| *"A previous package installation was left halfway through"* | An interrupted `apt` | `sudo dpkg --configure -a` — re-running the installer does not fix it |
| Port 8080 already in use | Another program | Install on another port with `--port 8090`; below 1024 is not possible |
| Empty application after a reboot, data still on disk | Data directory on a disk mounted after the service started | Check `RequiresMountsFor` is in the unit; both installers add it |

The wizard writes everything it does to `/var/log/employee-scheduling-setup.log`: every
command and its outcome. In browser mode that file is the only trace left after closing the
tab.

---

## 11. Security

The traffic is **plain HTTP**. That is acceptable on a trusted local network and nowhere else:
to reach the application from outside, put it behind a reverse proxy with a certificate —
nginx or Caddy — and do not expose the port directly.

`/etc/employee-scheduling.env` holds the database password, the session key and the backup
token. It is `640 root:employee-scheduling`; it does not belong anywhere else and certainly
not under version control.

---

## Related documents

| Document | Contents |
|---|---|
| [`../setup/INSTALL.md`](../setup/INSTALL.md) | The browser wizard for Raspberry Pi, step by step |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Why there are two engines, how the schema is migrated, what the solver does |
| [`INSTALLATION-WINDOWS.md`](INSTALLATION-WINDOWS.md) | Windows installation and packaging |
