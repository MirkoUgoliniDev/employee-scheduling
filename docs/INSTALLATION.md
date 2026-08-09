# Installazione — Employee Scheduling

Guida di installazione **dettagliata** per Windows 11 e Linux, con wizard automatici.

---

## 0. Modalità di installazione

L'applicazione supporta due motori database e tre forme di distribuzione:

| Modalità | Database | Dove | Ideale per |
|---|---|---|---|
| **Desktop** | SQLite (file singolo) | Macchina locale | Uso singolo (Windows o Linux) |
| **Server** | PostgreSQL | Macchina centralizzata | Multiutente, accesso da più PC |

Forme di distribuzione:

| Forma | Windows | Linux |
|---|---|---|
| **Sviluppo** | `mvn quarkus:dev` + `npm run dev` | idem |
| **Servizio** | `install-windows.ps1` (jpackage: app nativa con JRE incluso + scorciatoia) | `install-linux.sh` + systemd |
| **Standalone** | `java -jar` con JRE installato | `java -jar` o systemd |

> **Windows "come una normale app"**: il wizard usa **jpackage** (strumento ufficiale Java) che
> produce un installer `.msi` con JRE incluso, icona, voce nel menu Start e disinstallazione.
> L'utente finale **non deve installare Java**: tutto è nel pacchetto.

---

## 0.1 Consegnare l'applicazione a qualcun altro

Chi installa **non ha bisogno del repository né di alcuno strumento di sviluppo**: niente JDK,
Maven, Node o WiX. Riceve un solo file, l'MSI, e configura ciò che cambia da installazione a
installazione con un file di testo.

### Lato tuo — una volta per versione

```powershell
.\scripts\install-windows.ps1        # scegli 2 = installer MSI
```

Produce `dist\EmployeeScheduling-1.1.0.msi` (~125 MB, JRE incluso). Pubblicalo dove preferisci:
GitHub Releases, cartella condivisa, chiavetta. **Lo stesso file vale per tutte le installazioni**:
porta, SMTP e modalità di registrazione non sono più cablati nel pacchetto.

### Lato di chi installa

1. Doppio clic sull'MSI e scelta della cartella (va bene anche `C:\Program Files`).
2. L'applicazione parte e apre il browser da sola.
3. La prima persona che si registra diventa l'amministratore.

### Configurazione locale, senza ricompilare nulla

Al primo avvio l'applicazione crea:

```
%LOCALAPPDATA%\EmployeeScheduling\config.properties
```

Contiene **tutte le voci modificabili, già commentate e spiegate**: porta, server SMTP,
modalità di registrazione, chiave di sessione, token di backup, livello del log. Si toglie il
`#` dalla riga che interessa, si salva e si riavvia l'applicazione.

Quel file **vince sulle impostazioni scelte in fase di pacchettizzazione** (ordinale 450 contro
400 delle system properties): è pensato apposta perché chi installa possa correggere, per
esempio, una porta 8080 già occupata da un altro programma senza dipendere da te.

L'unica voce che non si può cambiare da lì è `app.data.dir`: quando il file viene letto la
cartella dati è già stata risolta.

### Dove stanno i dati

Tutto in `%LOCALAPPDATA%\EmployeeScheduling`: `large_data.db`, `backups\`, `app.log`,
`config.properties`. **Fuori dalla cartella di installazione**, quindi aggiornamenti e
disinstallazione non li toccano.

Per spostare un'installazione su un altro PC basta copiare quella cartella dopo aver
installato l'MSI.

### Disinstallazione

Doppio clic su `uninstall.cmd` nella cartella `app\` dell'installazione (per esempio
`C:\Program Files\EmployeeScheduling\app\uninstall.cmd`). Chiude l'applicazione, rimuove il
programma e **conserva i dati**; con `-RemoveData` toglie anche quelli, chiedendo conferma.

> Il `.cmd` è un lanciatore: i file `.ps1` non partono con un doppio clic, Windows li apre in
> un editor. Funziona anche `Impostazioni > App > EmployeeScheduling > Disinstalla`, purché
> l'applicazione sia chiusa.

---

## 1. Prerequisiti

### Windows 11

| Componente | Versione | Download |
|---|---|---|
| **JDK (Temurin)** | 21+ | https://adoptium.net → `.msi` x64 |
| **Maven** | 3.9+ | https://maven.apache.org/download.cgi → `apache-maven-3.9.x-bin.zip` |
| **Node.js** | 20+ LTS | https://nodejs.org |
| **Git** | qualsiasi | https://git-scm.com/download/win |
| **WiX Toolset** (solo installer MSI) | 3.14 | https://wixtoolset.org (installer `wix314.exe`) |
| **PostgreSQL** (solo modalità server) | 14+ | https://www.postgresql.org/download/windows/ |

Dopo l'installazione di JDK e Maven, aprire un terminale e verificare:

```powershell
java -version        # deve mostrare 21.x
mvn -version         # deve mostrare 3.9.x
git --version
```

Se `mvn` non è riconosciuto: aggiungere la cartella `bin` di Maven al PATH di sistema
(Pannello di controllo → Variabili d'ambiente → Path → Nuovo).

### Linux (Debian/Ubuntu/Raspberry Pi OS)

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven nodejs npm git curl
java -version
mvn -version
```

Su distribuzioni senza pacchetto JDK 21 (es. vecchie versioni di Ubuntu):

```bash
# Installazione manuale di Temurin 21
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-21-jdk maven
```

PostgreSQL (solo modalità server):

```bash
sudo apt install -y postgresql postgresql-client
sudo systemctl enable --now postgresql
```

---

## 2. Esecuzione dei wizard

### Windows 11

```powershell
cd C:\Lavori\VSCode\employee-scheduling
powershell -ExecutionPolicy Bypass -File .\scripts\install-windows.ps1
```

Il wizard chiede, in ordine:

1. **Modalità database** — `1` = SQLite desktop, `2` = PostgreSQL server (con URL JDBC, utente
   e password);
2. **Porta HTTP** — default `8080`;
3. **SMTP** — se usare il **mock** (email scritte nel log, nessun invio reale) oppure host,
   porta, utente, password e mittente;
4. **Packaging** — `1` = app-image (cartella con JRE inclusa, veloce), `2` = installer MSI
   (menu Start + disinstallazione; **WiX viene scaricato automaticamente** la prima volta,
   ~39 MB in `C:\tools\wix314`), `3` = solo build.

Chiave di sessione e token di backup sono **generati automaticamente**, non vengono chiesti.

La **cartella dati non viene più chiesta**: nel pacchetto i dati vanno sempre in
`%LOCALAPPDATA%\EmployeeScheduling` (vedi § 0.1), mentre in sviluppo restano in `databases\`.

Al termine crea `.env` (che serve solo a `mvn quarkus:dev`), compila e genera il pacchetto in
`dist\`.

> **Per il confezionamento Windows esiste un documento dedicato e più dettagliato**:
> [`docs/Consolidati/PACKAGING-WINDOWS-MSI.md`](Consolidati/PACKAGING-WINDOWS-MSI.md) — procedura
> manuale, ordine delle sorgenti di configurazione, trappole incontrate con sintomo e rimedio,
> lista di verifica prima della consegna. In caso di contraddizione, vale quello.

### Linux

```bash
git clone https://github.com/MirkoUgoliniDev/employee-scheduling.git
cd employee-scheduling
sudo ./scripts/install-linux.sh --engine postgresql
```

Lo script scarica automaticamente il JAR PostgreSQL più recente da GitHub
Releases, installa Java e PostgreSQL e registra il servizio systemd. Non servono
compilazione o trasferimenti manuali dal PC.

Opzioni principali:

- **Servizio systemd** — se rispondi `s`, viene creato e avviato
  `employee-scheduling.service` (riavvio automatico al boot, log in journald);
- **Dati** — default `/var/lib/employee-scheduling` (serve sudo per scriverci).

---

## 2.1 Modalità di registrazione (differenziata)

L'applicazione distingue due modalità di registrazione, selezionate da `app.registration.mode`
nel `.env` (default `auto`):

| Modalità | Database tipico | Flusso registrazione | Email/OTP |
|---|---|---|---|
| **standalone** | SQLite (desktop Windows/Linux) | username+password diretti | **Nessuna** — nessun server email richiesto |
| **server** | PostgreSQL (multiutente) | email → OTP 6 cifre → token → profilo | **Obbligatoria** per verifica e notifiche |
| **auto** (default) | derivata dal database | sqlite → standalone, postgresql → server | — |

In **standalone**:
- il **primo utente** crea l'ADMIN attivo con solo username+password;
- gli utenti successivi nascono CAPOSALA **in attesa di approvazione** (l'ADMIN li attiva
  da Utenti, senza email);
- gli endpoint OTP rispondono `OTP_NOT_REQUIRED` (la UI li nasconde automaticamente).

In **server**:
- registrazione con verifica email via OTP (come da sezione 6);
- i CAPOSALA nascono in attesa con **notifica email** agli ADMIN attivi.

Il valore si imposta nel `.env` (`APP_REGISTRATION_MODE=standalone|server|auto`) o nei
profilo application-*.properties. I wizard scelgono automaticamente: SQLite → standalone,
PostgreSQL → server.

---

## 3. Installazione manuale (senza wizard)

### 3.1 Clonare e compilare

```bash
git clone https://github.com/MirkoUgoliniDev/employee-scheduling.git
cd employee-scheduling
```

### 3.2 Frontend (necessario prima della prima build)

```bash
cd frontend
npm install
npm run build      # produce gli asset statici serviti da Quarkus
cd ..
```

### 3.3 Backend — modalità SQLite desktop

La build va fatta con l'opzione **uber-jar** (il fast-jar di default di Quarkus 3 non
produce il file eseguibile standalone):

```bash
mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=sqlite
```

**`-Dquarkus.profile` non è opzionale.** Il motore dati (`quarkus.datasource.db-kind`)
e le cartelle delle migrazioni Flyway sono fissati alla **compilazione**: nessuna
variabile d'ambiente può cambiarli dopo. Compilando senza profilo, Flyway trova la
stessa migrazione in `db/migration/sqlite` e in `db/migration/postgresql` e si ferma
con *"Found more than one migration with version 1"*. Per un server PostgreSQL usare
`-Dquarkus.profile=postgresql`.

Il JAR eseguibile è `target/employee-scheduling-1.1-SNAPSHOT-runner.jar`.

**Windows** — eseguire:

```powershell
set AUTH_SESSION_KEY=una-chiave-criptografica-lunga-almeno-32-char!!
java -jar target\employee-scheduling-1.1-SNAPSHOT-runner.jar
```

**Linux** — eseguire:

```bash
export AUTH_SESSION_KEY=una-chiave-criptografica-lunga-almeno-32-char!!
java -jar target/employee-scheduling-1.1-SNAPSHOT-runner.jar
```

Aprire il browser su `http://localhost:8080` → **prima registrazione** = crea l'ADMIN
(email + OTP; in modalità mock il codice è nel log del terminale).

### 3.4 Backend — modalità PostgreSQL

Creare il database (una volta sola):

```bash
# PostgreSQL locale
sudo -u postgres psql -c "CREATE ROLE employee_scheduling LOGIN PASSWORD 'scegli-una-password-forte';"
sudo -u postgres psql -c "CREATE DATABASE employee_scheduling OWNER employee_scheduling;"
```

Eseguire con il profilo esplicito:

```bash
export QUARKUS_PROFILE=postgresql
export DATABASE_URL=jdbc:postgresql://localhost:5432/employee_scheduling
export DATABASE_USERNAME=employee_scheduling
export DATABASE_PASSWORD=la-password-scelta
export AUTH_SESSION_KEY=una-chiave-criptografica-lunga-almeno-32-char!!
export BACKUP_ADMIN_TOKEN=un-token-lungo-e-casuale
java -jar target/employee-scheduling-1.1-SNAPSHOT-runner.jar
```

Le migrazioni Flyway creano lo schema automaticamente al primo avvio.

### 3.5 Configurazione email (OTP)

L'app invia OTP e notifiche via SMTP. Tre modi:

**A. File `.env`** (accanto al JAR, nella working directory del processo):

```ini
QUARKUS_MAILER_HOST=smtp.esempio.com
QUARKUS_MAILER_PORT=587
QUARKUS_MAILER_USERNAME=no-reply@esempio.com
QUARKUS_MAILER_PASSWORD=password-smtp
QUARKUS_MAILER_FROM=no-reply@esempio.com
QUARKUS_MAILER_MOCK=false
```

**B. Dall'interfaccia** — Configurazione → Parametri Email (effetto immediato, nessun riavvio).

**C. Mock (solo sviluppo/test)** — `QUARKUS_MAILER_MOCK=true` o assente in dev: le email
finiscono nei **log**, mai inviate.

> **Attenzione**: con `QUARKUS_MAILER_MOCK=true` gli OTP sono leggibili nel log. Va bene in
> sviluppo; in produzione va sempre `false` con SMTP reale.

---

## 4. Applicazione Windows nativa (jpackage)

> Sezione di sintesi. La versione completa — con le trappole incontrate, i loro sintomi e la
> lista di verifica prima della consegna — è in
> [`docs/Consolidati/PACKAGING-WINDOWS-MSI.md`](Consolidati/PACKAGING-WINDOWS-MSI.md).

Il wizard automatizza tutto; i passi manuali, **in quest'ordine**, sono:

```powershell
# 1. Frontend PRIMA del jar: la build finisce in src\main\resources\META-INF\resources
cd frontend; npm install; npm run build; cd ..

# 2. Uber-jar. Due cose obbligatorie:
#    - uber-jar: il fast-jar predefinito NON produce *-runner.jar
#    - profilo in fase di BUILD: quarkus.flyway.locations e' build-time, senza questo
#      il pacchetto include le migrazioni di entrambi i motori e non parte
mvn -B -ntp package -DskipTests "-Dquarkus.package.jar.type=uber-jar" "-Dquarkus.profile=sqlite"

# 3. Staging: jpackage copia TUTTA la cartella --input dentro l'applicazione
New-Item -ItemType Directory -Force -Path target\jpackage-input | Out-Null
Copy-Item target\employee-scheduling-1.1-SNAPSHOT-runner.jar target\jpackage-input\
Copy-Item uninstall-windows.ps1, uninstall.cmd target\jpackage-input\

# 4. Chiave di sessione casuale: sotto i 16 caratteri l'applicazione risponde 500 a ogni accesso
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$key = -join ($bytes | ForEach-Object { $_.ToString('x2') })

# 5. Installer MSI (WiX 3.14 in C:\tools\wix314, scaricato dal wizard la prima volta)
$env:Path = "C:\tools\wix314;" + $env:Path
jpackage --type msi --name "EmployeeScheduling" --app-version 1.1.0 `
  --input target\jpackage-input `
  --main-jar employee-scheduling-1.1-SNAPSHOT-runner.jar `
  --dest dist --win-menu --win-dir-chooser `
  --java-options "-Dapp.data.dir=auto" `
  --java-options "-Dquarkus.http.port=8080" `
  --java-options "-Dquarkus.mailer.mock=true" `
  --java-options "-Dquarkus.http.auth.session.encryption-key=$key" `
  --java-options "-Dquarkus.log.file.enable=true" `
  --java-options "-Dquarkus.log.file.level=INFO" `
  --java-options "-Dapp.open-browser-on-start=true"
```

Per la cartella portabile: identico con `--type app-image`, senza `--win-menu` e
`--win-dir-chooser`.

Risultato:

- `dist\EmployeeScheduling-1.1.0.msi` (installer) oppure `dist\EmployeeScheduling\EmployeeScheduling.exe` (portabile);
- voce "EmployeeScheduling" nel menu Start;
- disinstallazione con `<install>\app\uninstall.cmd` o da Impostazioni → App.

**Configurazione dell'app installata.** Si passa con `-D` singole, che jpackage scrive nel file
`<install>\app\EmployeeScheduling.cfg`. Chi installa può poi correggere qualunque valore in
`%LOCALAPPDATA%\EmployeeScheduling\config.properties`, che **ha la precedenza** su quelle `-D`
(vedi § 0.1).

> **Non usare `-Dquarkus.config.locations=...\.env`**: in Quarkus 3.37 quella proprietà non
> accetta URI `file:///` e il valore viene cercato come nome di classe
> (`ClassNotFoundException`). Le versioni precedenti di questa guida lo suggerivano: era
> sbagliato.

**Dove finiscono i dati**: `%LOCALAPPDATA%\EmployeeScheduling` (database, backup, log,
`config.properties`), **mai** nella cartella di installazione. Per questo l'installazione può
stare tranquillamente in `C:\Program Files` e la disinstallazione non porta via nulla di tuo.

---

## 5. Installazione come servizio Linux (systemd)

Il wizard genera il file, ma a mano:

```bash
# Cartella dati
sudo mkdir -p /var/lib/employee-scheduling
sudo chown "$USER":"$USER" /var/lib/employee-scheduling
```

`/etc/systemd/system/employee-scheduling.service`:

```ini
[Unit]
Description=Employee Scheduling (turni del personale)
After=network.target
Wants=network-online.target

[Service]
Type=simple
User=employee
Group=employee
WorkingDirectory=/opt/employee-scheduling
EnvironmentFile=/etc/employee-scheduling.env
ExecStart=/usr/bin/java -jar /opt/employee-scheduling/employee-scheduling-1.1-SNAPSHOT-runner.jar
Restart=on-failure
RestartSec=5
# Hardening (opzionale ma consigliato)
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

`/etc/employee-scheduling.env` (permessi 600, solo root):

```ini
AUTH_SESSION_KEY=una-chiave-criptografica-lunga-almeno-32-char!!
QUARKUS_PROFILE=sqlite
APP_DATABASE_PATH=/var/lib/employee-scheduling/large_data.db
BACKUP_ADMIN_TOKEN=un-token-lungo-e-casuale
QUARKUS_MAILER_HOST=smtp.esempio.com
QUARKUS_MAILER_PORT=587
QUARKUS_MAILER_USERNAME=no-reply@esempio.com
QUARKUS_MAILER_PASSWORD=password-smtp
QUARKUS_MAILER_FROM=no-reply@esempio.com
QUARKUS_MAILER_MOCK=false
```

Abilitare e avviare:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now employee-scheduling
sudo systemctl status employee-scheduling
journalctl -u employee-scheduling -f     # log in tempo reale
```

> Se si usa PostgreSQL basta cambiare le variabili `QUARKUS_PROFILE=postgresql` +
> `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`.

---

## 6. Primo avvio e configurazione iniziale

1. Aprire `http://localhost:8080` (o l'indirizzo della macchina server);
2. Cliccare **Registrati**;
3. Inserire l'email → ricevere l'OTP (in mock: nel log del server);
4. Inserire il codice → scegliere username e password;
5. **Primo utente = ADMIN attivo** (la pagina lo dice); gli utenti successivi nascono
   CAPOSALA in attesa di approvazione (notifica via email agli ADMIN);
6. Da **Utenti** l'ADMIN approva i CAPOSALA e (in futuro) assegna la struttura.

---

## 7. Backup e aggiornamento

### Backup

- **SQLite**: backup automatico in `<dati>/backups` (default ogni 30 min, retention 48 file,
  configurabile da Configurazione → Backup); si può anche copiare il file `.db` a caldo
  (modalità WAL).
- **PostgreSQL**: `pg_dump` automatico nella stessa cartella; il ripristino dal pannello è
  protetto da token.

### Aggiornamento

```bash
git pull
cd frontend && npm install && npm run build && cd ..
# Stesso profilo dell'installazione esistente: sqlite oppure postgresql.
mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=sqlite
# poi: riavviare il servizio (systemd) o rigenerare il pacchetto jpackage
```

**I dati non vengono mai toccati**: database, backup e configurazione locale vivono fuori dalla
cartella dell'applicazione — su Windows in `%LOCALAPPDATA%\EmployeeScheduling`.

> Per un'installazione jpackage (app-image o MSI) non si "sostituisce un JAR": si rigenera
> l'intero pacchetto rieseguendo la sezione 4.

**Aggiornare un'installazione Windows esistente**:

1. `<install>\app\uninstall.cmd` — chiude l'applicazione, disinstalla e conserva i dati;
2. installare l'MSI nuovo;
3. l'applicazione ritrova database, backup e `config.properties` dove li aveva lasciati.

Disinstallare prima è necessario: `--app-version` è fisso a `1.1.0` e installare sopra la stessa
versione non è affidabile.

---

## 8. Risoluzione problemi

| Sintomo | Causa probabile | Soluzione |
|---|---|---|
| `Port 8080 already in use` | Altro processo sulla porta | **App installata**: `quarkus.http.port=8081` in `%LOCALAPPDATA%\EmployeeScheduling\config.properties` e riavvio. **Sviluppo**: `QUARKUS_HTTP_PORT=8081` nel `.env` |
| "Server non raggiungibile" al login, 500 su `/auth/me` | Chiave di sessione sotto i 16 caratteri | Rigenerare il pacchetto con una chiave lunga — [PACKAGING-WINDOWS-MSI § 7.1](Consolidati/PACKAGING-WINDOWS-MSI.md) |
| Disinstallazione bloccata (`app.log` in uso, o `GetLastError: 5`) | Applicazione aperta, o permessi riscritti da una versione vecchia | Usare `<install>\app\uninstall.cmd` — [§ 7.3 e § 7.4](Consolidati/PACKAGING-WINDOWS-MSI.md) |
| Interfaccia sempre in italiano, selettore lingua inerte | Quota di `localStorage` esaurita dalle cache vecchie | Console del browser: rimuovere le chiavi `i18n_cache*` e ricaricare — [§ 7.11](Consolidati/PACKAGING-WINDOWS-MSI.md) |
| Elenchi vuoti senza alcun errore dopo una reinstallazione | Struttura selezionata rimasta in `localStorage` e non più esistente | Corretto dal 5 agosto 2026; su versioni precedenti riselezionare la struttura dalla barra in alto |
| `.ps1` si apre nel Blocco note | I `.ps1` non partono con un doppio clic | Usare `uninstall.cmd`, non lo script direttamente |
| OTP non arriva | SMTP in mock o non configurato | Controllare il log; configurare SMTP (sezione 3.5) |
| `Unrecognized configuration key` | Profilo sbagliato | Usare `QUARKUS_PROFILE=sqlite` o `postgresql` espliciti |
| Login bloccato ("in attesa") | CAPOSALA non approvato | L'ADMIN lo approva da Utenti |
| Backup disattivato (PostgreSQL) | `pg_dump` non trovato | Installare i client PostgreSQL o impostare `backup.postgresql.bin-dir` |
| L'app non parte come servizio | File `.env` non leggibile | Verificare permessi 600 e percorsi assoluti |
