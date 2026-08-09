# Handoff — Sessione 3 agosto 2026 (sera): packaging, stabilità e installazione

Documento di memoria per riprendere domani senza perdere il contesto.
**Stato: NON committato** — SingleInstanceGuard + proprietà in working tree.

---

## 1. Stato in una riga

L'app è **installata e funzionante** in `C:\EmployeeScheduling` (app-image copiata a mano,
non MSI). Login `admin/admin123` verificato. Due lavori in corso NON committati:
**SingleInstanceGuard** (blocco doppia istanza) e la proprietà `app.single-instance-lock`.

---

## 2. Cronologia della sessione (packaging Windows)

### 2.1 Cosa funziona ORA (verificato dal vivo)

| Elemento | Stato |
|---|---|
| App installata | `C:\EmployeeScheduling` (app-image copiata manualmente) |
| Avvio | `EmployeeScheduling.exe` — una sola istanza |
| Porta | 8080 |
| Login | `admin` / `admin123` — **VERIFICATO** (HTTP 200, `authenticated:true`) |
| DB | `C:\EmployeeScheduling\app\data\large_data.db` (utente admin presente, attivo) |
| Browser automatico | `BrowserLauncher` apre `http://localhost:8080` all'avvio |
| Log su file | `C:\EmployeeScheduling\app\data\app.log` |
| Struttura | exe + `app\` + `runtime\` + `data\` (tutto in una cartella) |

### 2.2 Bug trovati e risolti durante la sessione (IMPORTANTE)

1. **`quarkus.config.locations` NON funziona in Quarkus 3.37** con URI `file:///`:
   il valore viene cercato come classe (`ClassNotFoundException: /config/locations=...`).
   **Soluzione**: configurazione via **system properties** cablate nel pacchetto
   (`--java-options "-Ddemo.db.name=..."` ecc.), NON file .env esterno.
2. **Backslash nel `.env` = escape**: `APP_DATABASE_PATH=C:\...` diventava
   `C:employee-scheduling-...` (path rotto). Usare SEMPRE forward slash.
3. **Profilo Quarkus build-time** (`quarkus.flyway.locations`): la build deve usare
   `-Dquarkus.profile=sqlite` (o `postgresql`), altrimenti migrazioni duplicate.
4. **DB legacy senza `flyway_schema_history`** → Flyway rifiuta l'avvio. Per installazioni
   nuove serve DB vuoto.
5. **PS 5.1 + stderr nativi**: i warning di Vite su stderr diventano `NativeCommandError`
   con EAP=Stop. Soluzione: `Invoke-Native` (abbassa EAP, decide da `$LASTEXITCODE`).
6. **`icacls "(R,W)"` fragile da PS 5.1** → passare via `cmd /c` con parsing nativo.
7. **Due istanze simultanee**: la seconda "ruba" la porta 8080, il browser mostra quella
   sbagliata, e la registrazione finisce nel DB sbagliato. → **SingleInstanceGuard** (in corso).

### 2.3 Decisioni di design (come da richiesta utente)

- **Tutto in una cartella**: exe + `data\` (DB, backup, log) insieme. Niente cartella dati
  esterna, niente `%APPDATA%` separato.
- **MSI con scelta cartella** (`--win-dir-chooser`): l'utente sceglie dove installare.
  AVVERTENZA: evitare Program Files (data\ non scrivibile).
- **Browser aperto automaticamente** all'avvio (solo modalità NORMAL).
- **Single-instance lock** (in corso): la seconda istanza mostra avviso ed esce.

---

## 3. LAVORO NON COMMITTATO (riprendere da qui)

### 3.1 File modificati/nuovi nel working tree

```
M  src/main/resources/application.properties        ← +app.single-instance-lock
?? src/main/java/org/acme/employeescheduling/config/SingleInstanceGuard.java
```

### 3.2 SingleInstanceGuard — cosa fa

- `@Observes StartupEvent`, SOLO in `LaunchMode.NORMAL` (non dev/test)
- Acquisisce `FileLock` esclusivo su:
  - Windows: `%USERPROFILE%\AppData\Local\EmployeeScheduling\app.lock`
  - Linux: `~/.employee-scheduling/app.lock`
- Se il lock è già tenuto: log warning + `JOptionPane` avviso + `System.exit(1)`
- Best-effort: se il lock non è acquisibile per errori filesystem, l'avvio prosegue
- Proprietà: `app.single-instance-lock=true` (default), disattivabile

### 3.3 Prossimi passi (domani)

1. **Committare e pushare**: `SingleInstanceGuard.java` + `application.properties`
2. **Rigenerare il pacchetto definitivo** con il wizard:
   ```
   powershell -ExecutionPolicy Bypass -File .\install-windows.ps1
   ```
   Scelta: `1` (SQLite) → porta → `2` (MSI) — oppure `1` per app-image.
3. **Testare il SingleInstanceGuard end-to-end**: avviare 2 istanze, la seconda deve uscire.
   (Test parziale fatto: la 2ª istanza usciva, ma restavano processi residui da pulire —
   verificare che il launcher jpackage non lasci processi orfani.)
4. **Verificare l'MSI silenzioso**: `msiexec /i ... /qn INSTALLDIR=C:\EmployeeScheduling`
   NON ha installato correttamente (cartella vuota). Da indagare — per ora l'app-image
   copiata a mano funziona.
5. **MSI definitivo da distribuire**: rigenerare dopo il commit del guard.

---

## 4. Stato ambiente utente (al momento della chiusura)

- App in esecuzione su porta 8080 (istanza da `C:\EmployeeScheduling`)
- Login funzionante: `admin` / `admin123`
- DB: `C:\EmployeeScheduling\app\data\large_data.db` (admin presente, attivo)
- Browser: se mostra "Server non raggiungibile" → l'app era in riavvio, premere F5

---

## 5. Comandi utili

```powershell
# Rigenerare il pacchetto (MSI con scelta cartella)
powershell -ExecutionPolicy Bypass -File .\install-windows.ps1

# Test SQLite
mvn -B test "-Dquarkus.test.profile=test-sqlite"

# Build eseguibile (profilo OBBLIGATORIO, build-time)
mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=sqlite

# Avvio dev
mvn quarkus:dev
```

---

## 6. Trappole ricorrenti (controllare PRIMA di perdere tempo)

1. **Porta 8080 occupata** da un'altra istanza → il browser mostra l'istanza sbagliata.
   Verificare: `Get-NetTCPConnection -LocalPort 8080` e `Get-Process EmployeeScheduling`.
2. **Non usare `quarkus.config.locations`** per il pacchetto (rotto in 3.37): usare
   system properties `-D` nel cfg di jpackage.
3. **Forward slash** in tutti i path del `.env` e delle system properties.
4. **`-Dquarkus.profile` alla build**, non solo nel .env.
5. L'**app-image** (`dist\EmployeeScheduling`) viene sovrascritta da ogni run del wizard:
   per installare a mano, rigenerarla e copiarla in `C:\EmployeeScheduling`.

---

## 7. Dove sono i file chiave

| File | Ruolo |
|---|---|
| `install-windows.ps1` | Wizard: build + jpackage (app-image/MSI) + WiX auto |
| `install-linux.sh` | Wizard Linux (systemd opzionale) |
| `src/main/java/.../config/BrowserLauncher.java` | Apre il browser all'avvio |
| `src/main/java/.../config/DataDirInitializer.java` | Crea `data\` al primo avvio |
| `src/main/java/.../config/SingleInstanceGuard.java` | **NON COMMITTATO** — blocco doppia istanza |
| `docs/INSTALLATION.md` | Guida installazione |
| `docs/handoff-2026-08-03.md` | Handoff precedente (feature registrazione OTP) |
