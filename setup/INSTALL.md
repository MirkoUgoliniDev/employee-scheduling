# Installing on Linux — operations guide

Wizard that installs Employee Scheduling as a service on an always-on machine:
Raspberry Pi, mini-PC, or virtual machine.

It uses **only Python's standard library**: there is nothing to install before
you can launch it, which on a freshly prepared machine is precisely when doing
so is most awkward.

---

## In short

```bash
# 1. log in to the Raspberry Pi
ssh pi@raspberrypi.local

# 2. download only the small Raspberry installer from the Release
curl -fLO https://github.com/MirkoUgoliniDev/employee-scheduling/releases/latest/download/employee-scheduling-raspberry-installer.tar.gz
mkdir employee-scheduling-installer
tar -xzf employee-scheduling-raspberry-installer.tar.gz -C employee-scheduling-installer
cd employee-scheduling-installer

# 3. start the temporary setup; the JAR is downloaded automatically
sudo ./scripts/start-web-setup.sh
```

The command prints a short address such as `http://192.168.1.151:8899` and a
temporary code: open the address from a PC on the same trusted local network and
enter the code on the page. The code changes at every start, and the privileged
server shuts down automatically once installation finishes. From the page you
can test SMTP delivery before installing and choose whether to load the sample
data.

The JAR is kept in `/var/cache/employee-scheduling-installer` under the Release
number: closing and reopening the wizard does not download it again. Use
`sudo ./scripts/start-web-setup.sh --refresh` only to force a fresh download.

**The engine is chosen at launch, not on the page.** The launcher downloads the
asset built for one engine and passes it to the wizard, so for SQLite start it
with `sudo ./scripts/start-web-setup.sh --engine sqlite`. Selecting the other
engine in the browser stops the installation at the System check, with the
baked-engine mismatch — before anything on the machine is touched.

For a test installation, the same portable sample data can be loaded on both
PostgreSQL and SQLite by adding `--demo-data`:

```bash
sudo ./scripts/install-linux.sh --engine postgresql --demo-data
```

The option creates locations, employees, specialists, skills, and unassigned
shifts, but it creates no users, passwords, or SMTP configuration. It is
idempotent and stays disabled by default in production installations.

The archive contains the scripts and the web wizard, but not the application
code. The launcher automatically downloads from GitHub Releases the JAR built
for the selected engine. The source repository, Windows, `scp`, Maven, and
Node.js are not needed on the Raspberry Pi.

To use the text wizard or a manually built package, the advanced mode remains
available:

```bash
sudo python3 setup/wizard.py --tui --jar ~/employee-scheduling-runner.jar
```

> The manual wizard requires the whole `setup/` directory, not just
> `wizard.py`. The Raspberry archive now already includes it.

> ### `-Dquarkus.profile` is not optional
>
> The data engine (`quarkus.datasource.db-kind`) and the Flyway migration
> directories are fixed when the jar is **built**: no environment variable can
> change them afterwards. The consequences are concrete:
>
> - building for SQLite and installing with `--engine postgresql` makes the
>   service start and die with *"Driver does not support the provided URL"*;
> - building **without** a profile is worse, because the failure is silent: the
>   default profile has `quarkus.flyway.active=false`, so the migrations **do not
>   run at all**. On a fresh installation the tables are never created and the
>   application behaves inexplicably, without a single error pointing at the
>   cause.
>
> The wizard reads the engine baked into the jar and **refuses before touching
> the machine** both the wrong engine and a package built without a profile.
> Use `-Dquarkus.profile=sqlite` if you will install with `--engine sqlite`.
>
> Watch out for a detail that misleads: **Quarkus also reads the `.env` file at
> build time**. If the project contains an `.env` with `QUARKUS_PROFILE=…`, the
> profile is applied even without passing it to Maven — and on a clean clone,
> where that file is absent, the same command produces a different jar. Always
> pass it explicitly.

---

## The modes

| Command | When it is useful |
|---|---|
| `sudo python3 setup/wizard.py --dry-run --jar …` | Shows every command it would run **without changing anything**. Always a good starting point. |
| `sudo python3 setup/wizard.py --tui --jar …` | Installation from the terminal. This is the default mode when `--web` is not passed. |
| `sudo python3 setup/wizard.py --web --jar …` | Browser interface, with steps advancing in real time. |

### Web mode: local network or SSH tunnel

The recommended launcher temporarily exposes port `8899` on the local network
and protects every request with a temporary code shown in the terminal. Use it
only on a trusted network and do not share the code. To keep the wizard
reachable **only on the machine itself**, start it with `--local-only`; then
from your PC:

```bash
ssh -L 8899:localhost:8899 pi@raspberrypi.local
# then open http://localhost:8899
```

The page runs commands **as root**. The temporary key blocks stray requests, but
the traffic is still HTTP: on an untrusted network always use the tunnel.

On the page, the form collects data engine, port, package path, data directory,
sample data, and the full SMTP configuration. The test button verifies DNS,
connection, STARTTLS, authentication, and delivery before the service is
modified.

Running `--web` together with `--dry-run`, the page **cannot** start a real
installation: both buttons stay in simulation.

---

## Prerequisites

Very few, and deliberately so:

- **Linux with systemd** — Raspberry Pi OS, Debian, Ubuntu (`apt` branch) or
  Fedora, RHEL, Rocky, AlmaLinux (`dnf` branch)
- **Python 3** — already present on all the distributions listed above
- **Root privileges** (`sudo`)
- **The `.jar` package** — only for the manual wizard: the launcher and
  `install-linux.sh` download it automatically

Java and PostgreSQL are **not** prerequisites: the wizard installs them.

The `dnf` branch is the less battle-tested of the two, and on Fedora and RHEL
SELinux is enabled out of the box: combined with `ProtectSystem=strict` it may
require adjusting the labels.

### Why the jar is built on the PC and not here

Building requires Maven, Node.js 20 or later, and several minutes of CPU. On
Debian bookworm — the basis of Raspberry Pi OS — Node stops at 18, which is not
enough for this frontend.

---

## The options

| Option | Default | What it does |
|---|---|---|
| `--jar PATH` | — | The package to install. **Mandatory in `--tui` mode**; in `--web` mode it can be typed on the page. |
| `--engine postgresql\|sqlite` | `postgresql` | Data engine. |
| `--port N` | `8080` | Application port. |
| `--data-dir PATH` | `/var/lib/employee-scheduling` | Backups, settings, and — only with SQLite — the database. Must be absolute and without spaces. |
| `--web` | — | Browser interface. |
| `--tui` | — | Terminal interface (default). |
| `--web-port N` | `8899` | Port of the wizard, not of the application. |
| `--web-host ADDR` | `127.0.0.1` | Address the wizard listens on. `0.0.0.0` exposes it on the local network — this is what the launcher does. |
| `--dry-run` | — | Simulate only. |
| `--yes`, `-y` | — | Do not ask for confirmation, for automation. |
| `--demo-data` | off | Loads the portable sample dataset on first startup. If omitted, the value is reused from the existing installation. |
| `--smtp-host`, `--smtp-user`, `--smtp-pass` | empty | Email delivery. |
| `--smtp-port` | `587` | Also settable on the web page. |
| `--smtp-from` | SMTP username | Also settable on the web page. |

Without SMTP, registration codes appear only in the service log
(`journalctl -u employee-scheduling`).

### Which engine to choose

**SQLite** is a single file, has no service to maintain, and consumes far less
on a Raspberry Pi. It is fine for a structure with few people who do not edit
the same shifts at the same time.

**PostgreSQL** is needed when several people work on the schedule together, and
it is the only one of the two that really handles concurrent access. It is also
the only one on which registration with an emailed code works.

---

## What the wizard does, step by step

1. **System check** — privileges, package manager, systemd, free space, free
   port. **The package and the data directory** are validated here too: if
   something is wrong, the wizard stops *before* changing anything, not halfway
   through.
2. **Java** — installs 21, or 17 where 21 is unavailable. The application is
   built for 17, so 17 is enough. Skipped only if Java **17 or later** is
   already present: with an older version (8 or 11) the step installs anyway,
   without removing the existing one.
3. **Database** — only with `--engine postgresql`: installs the service, creates
   the role and the database, and **actually tries to connect** with the
   credentials. With SQLite it is skipped.
4. **User and directories** — a system user without a shell, using the data
   directory as its home (no private home is created). Creates the data
   directory and the `backups/` subdirectory, both with `750` permissions.
5. **Application** — copies the jar to `/opt/employee-scheduling`, owned by root
   and read-only for the service.
6. **Configuration** — generates the session key and the backup token, writes
   `/etc/employee-scheduling.env` with `640 root:employee-scheduling`.
7. **Service** — systemd unit with automatic startup, restart on failure, and
   confinement (`ProtectSystem=strict`, `ReadWritePaths`, `PrivateTmp`).
8. **Verification** — waits up to three minutes for the application to respond.
   If the service **dies**, the step fails and prints the last lines of the log.
   If instead it is still alive but has not finished starting, the step is
   reported as *skipped*, not as an error: on slow hardware the first startup
   can take longer, and there is nothing to fix.

At the first failure the wizard stops: the steps depend on one another, and
continuing would leave the machine in a worse state than it started in.

---

## After installation

```bash
systemctl status employee-scheduling      # status
journalctl -u employee-scheduling -f      # live log
systemctl restart employee-scheduling     # restart
```

The application answers on `http://<server-address>:8080`, or on the port chosen
with `--port`. **The first account that registers becomes the administrator.**

### Where the data actually lives

It depends on the engine, and it is the difference that matters most if one day
you have to move or save the installation.

| | SQLite | PostgreSQL |
|---|---|---|
| Database | `/var/lib/employee-scheduling/employee_scheduling.db` | in the PostgreSQL cluster (`/var/lib/postgresql/…`) |
| Backups | `/var/lib/employee-scheduling/backups/` | same (`.dump` files) |
| Backup settings | `/var/lib/employee-scheduling/` | same |
| Service log | journald | journald |

With PostgreSQL, **copying the data directory does not take the database with
it**: it only takes the backups. The log is not written to a file: read it with
`journalctl -u employee-scheduling`.

If a service enters a restart loop, `systemctl status` may still show it as
active for a few moments: the unit has `Restart=on-failure` with a ten-second
delay. The journal is the only place where the loop is visible.

### The backup token

Administering backups from the interface requires a token, which the wizard
generates but **does not display**. Read it like this:

```bash
sudo grep BACKUP_ADMIN_TOKEN /etc/employee-scheduling.env
```

From an address other than `localhost`, backup calls answer **426** until the
traffic goes over HTTPS. On a headless server in a local network, the simplest
route is the same SSH tunnel used for the wizard: from the application's point
of view the request comes from `localhost`, so it passes.

```bash
ssh -L 8080:localhost:8080 pi@raspberrypi.local
# then open http://localhost:8080 and the backup section works
```

The alternatives are a reverse proxy with a certificate in front of the
application, or — only on a network you genuinely trust — disabling the
requirement by adding `BACKUP_ADMIN_REQUIRE_TLS_FOR_REMOTE=false` to
`/etc/employee-scheduling.env` and restarting the service. On an installed
service that env file is the only writable place: the corresponding property,
`backup.admin.require-tls-for-remote`, is baked into the jar.

With PostgreSQL, backups require `pg_dump` and `pg_restore`. The wizard warns if
they are missing, and in that case the application disables backup and restore
by itself.

---

## Updating

Run the same wizard again with the new package:

```bash
sudo python3 setup/wizard.py --tui --yes --jar ~/employee-scheduling-1.3.0-runner.jar
```

The wizard recognizes that the port is held by **its own** service and proceeds
as an update. There is no need to repeat the options: **engine, port, and data
directory are reused from the existing installation**, and the wizard says so
before starting. Specifying them explicitly changes them — and that is exactly
what `start-web-setup.sh` does with the engine, which it always passes: on an
SQLite installation, re-run it with `--engine sqlite` or it falls back to
PostgreSQL.

**The session key and the database password** are reused too: nobody is logged
out and nothing falls out of sync.

The previous package is removed from `/opt/employee-scheduling` if its name
differs from the new one, so two jars are not left in the directory.

Schema migrations apply themselves on the first startup. As with every schema
change, it is wise to have a recent backup.

---

## If something goes wrong

The wizard writes everything to `/var/log/employee-scheduling-setup.log`: every
command executed and its outcome. In web mode it is the only trace left after
closing the browser.

**"Another installation is already in progress"** — a wizard was left open,
perhaps in a dropped SSH session. The lock is
`/var/run/employee-scheduling-setup.lock` and contains the PID: if that process
no longer exists, the wizard recovers it by itself on the next run.

**"The package is built for X but you are installing with engine Y"** — rebuild
the jar with `-Dquarkus.profile=Y`. Nothing was changed on the machine.

**The service does not start** — the diagnosis is almost always in the last
lines:
```bash
journalctl -u employee-scheduling -n 60 --no-pager
```

**Database connection refused** — the line for local connections is missing from
`pg_hba.conf`:
```
host all all 127.0.0.1/32 scram-sha-256
```

**Port 8080 is used by another program** — install on another port with
`--port 8090`. Below 1024 is not possible: the service runs unprivileged and
could not bind there.

**"A previous package installation was left halfway through"** — an `apt` run
was interrupted. Running the wizard again does not fix it:
```bash
sudo dpkg --configure -a
```

**"PostgreSQL appears started but the server does not respond"** — on Debian
`postgresql.service` is only a wrapper: the real server is the cluster.
```bash
pg_lsclusters                       # see the real status
sudo pg_ctlcluster 15 main start    # start it
```

**Data directory on an external disk** — the wizard refuses to install if a data
directory **under `/mnt/` or `/media/`** is not mounted, because otherwise the
data would land on the SD card and disappear behind the mount at the first
reboot. A disk mounted elsewhere (for example `/srv/data`) is not checked. The
unit generated by the Python wizard waits for the mount before starting the
service (`RequiresMountsFor`), and so does the one written by `install-linux.sh` — the script
was missing that line until 9 August 2026.

---

## Uninstalling

```bash
sudo /opt/employee-scheduling/uninstall-linux.sh            # removes service and application, KEEPS the data
sudo /opt/employee-scheduling/uninstall-linux.sh --purge    # removes everything, see below
```

Both installers copy the uninstaller next to the application, so it stays
available even after the extracted installer archive is gone. From the
repository, `sudo ./scripts/uninstall-linux.sh` is equivalent.

`--purge` also removes the data directory, the backups, the database **and its
PostgreSQL role**, the configuration file, the service user, and the installer
cache in `/var/cache/employee-scheduling-installer`.

Without `--purge`, the data directory, the service user, and the configuration
file remain — the last one because it still contains the password of the
database in use. On reinstall, the application finds everything as it was.

`--purge` asks you to type `DELETE` in full: it is not reversible. Adding
`--yes` skips every question.

---

## Security

The traffic is **plain HTTP, not encrypted**: fine on a trusted local network.
If the application must be reachable from outside, put it behind a reverse proxy
with a certificate — nginx or Caddy — and do not expose the port directly.

The file `/etc/employee-scheduling.env` contains the database password, the
session key, and the backup token. It is `640 root:employee-scheduling` and must
not be copied elsewhere or placed under version control.

---

## Recommended installation: shell script

For a quick or automated installation, `scripts/install-linux.sh` remains
available and does the same things in a single line:

```bash
sudo ./scripts/install-linux.sh --engine postgresql
```

Without `--jar` it uses a jar already present in `target/`, and only if there is
none does it download the latest Release built for the chosen engine. It also
accepts `--from-source` (builds in place: slow on a
Raspberry Pi and requires Node 20+) and `--no-service` (does not register the
systemd service).

The wizard remains available for manual installations with a local JAR.
