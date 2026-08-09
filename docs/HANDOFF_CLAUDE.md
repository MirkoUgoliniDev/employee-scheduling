# HANDOFF — EmployeeScheduling (Windows MSI / login + “Server non raggiungibile”)

Data: 2026-08-04
Repo: `C:\Lavori\VSCode\employee-scheduling`
Branch corrente: `backup-e-hardening`
Obiettivo immediato: rendere affidabile il login e l’esperienza MSI Windows; eliminare lo stato “Server non raggiungibile” e lo “non entro” dopo login.

## Stato attuale (high level)
- È stato rigenerato l’MSI Windows (jpackage + WiX) e si sta testando il comportamento da utente finale.
- È stato individuato un bug backend gravissimo lato Quarkus form-auth: errori 500 su `/j_security_check` e `/auth/me` causati da encryption key non valida.
- Dopo fix “server non raggiungibile”, rimane un problema UX/SPA: backend risponde `authenticated:true` ma UI resta su `/login` (“non entro”).

## Repro / Sintomi
### Sintomo A — “Server non raggiungibile. Riprova fra qualche istante.”
- Visto su Desktop app (MSI) nel browser.
- Da CLI, `GET http://localhost:8080/` spesso risponde 200, ma endpoint JSON come `/auth/me` fallivano.
- In PowerShell `Invoke-WebRequest http://localhost:8080/auth/me` restituiva JSON tipo `{"details":"Error id <...>","stack":""}` (500).
- Nel log installato (vedi sotto) compariva eccezione Quarkus.

### Sintomo B — “non entro” dopo login
- DevTools Network mostra `me` che ritorna:
  - `{"authenticated": true, "username":"admin", "roles":["ADMIN"], "admin": true}`
- Nonostante ciò, la UI rimane sulla pagina `/login` (manca redirect o state refresh client).

## Root cause identificata (Sintomo A)
Nel log MSI: eccezione Quarkus form auth/persistent login cookie:
- `Shared keys for persistent logins must be more than 16 characters long`

Questo manda in errore la creazione del `PersistentLoginManager` e quindi falliscono `/j_security_check` e `/auth/me` con 500.

### Evidenza log
Percorso (install MSI in `C:\EmployeeScheduling`):
- `C:\EmployeeScheduling\app\data\app.log` (attenzione: `data` sta sotto `app\` con jpackage)

Nel tail log si vede stacktrace con:
- `PersistentLoginManager.<init>` e messaggio chiave troppo corta.

## Fix applicato (ma serve conferma end-to-end)
File modificato:
- `install-windows.ps1`

Cambio:
- Invece di passare solo `-DAUTH_SESSION_KEY=...`, il packaging deve passare esplicitamente:
  - `-Dquarkus.http.auth.session.encryption-key=<chiave lunga (>=16, meglio 32/64)>`

Nel patch locale è stato introdotto:
- generazione `sessionKey64 = New-CryptoString 64`
- aggiunta a jpackage options:
  - `-Dquarkus.http.auth.session.encryption-key=$sessionKey64`

Nota: `application.properties` ha già:
- `quarkus.http.auth.session.encryption-key=${AUTH_SESSION_KEY:dev-only-change-me-32-chars-min!!}`
ma nel pacchetto MSI l’ambiente non garantisce che `AUTH_SESSION_KEY` arrivi; quindi va bene forzare la system property Quarkus direttamente.

## Layout jpackage/APPDIR (importantissimo)
In app-image e MSI:
- `$APPDIR` in `EmployeeScheduling.cfg` risolve a `<install>\app` (non alla root)

Quindi i percorsi runtime devono essere:
- DB: `$APPDIR\data\large_data.db` => `<install>\app\data\large_data.db`
- log: `$APPDIR\data\app.log` => `<install>\app\data\app.log`
- backup: `$APPDIR\data\backups`

Conferma da install:
- esiste `C:\EmployeeScheduling\app\data\large_data.db`
- esistono `app.log`, `app.log.1`, ecc in `C:\EmployeeScheduling\app\data\`

## Process / Porta 8080 / multi-instance
- È stata osservata la presenza di due processi `EmployeeScheduling.exe` contemporaneamente (due PID).
- In alcuni momenti `netstat -ano | findstr :8080` mostrava listener su 8080.
- È stata eseguita una kill forzata di tutti i processi:
  - `Get-Process -Name EmployeeScheduling | Stop-Process -Force`

Nota: esiste un meccanismo di single-instance (`app.single-instance-lock=true`), ma in pratica si sono visti 2 PID. Da verificare se:
- guard crea un processo “launcher” + uno “server”
- oppure il lock file è in path sbagliato per MSI/per-user.

## Frontend/Auth state (fix parziali già fatti)
Sono presenti modifiche FE che riguardano “first admin creation” e refresh:
- `frontend/src/auth/AuthContext.tsx`
  - `refresh()` ora usa il valore di `me` appena letto, non lo state precedente (fix bug di stale state).
  - espone `refresh` nel context.
- `frontend/src/pages/RegisterPage.tsx`
  - dopo `registerApi.complete(...)` fa `await refresh()` per evitare redirect loop (`needsFirstAdmin` false).

## Issue aperta principale (Sintomo B): login ok ma UI non entra
- Backend conferma sessione valida (`/auth/me authenticated:true`) ma UI non naviga.
- Probabile causa: `LoginPage.tsx` non fa redirect su successo, oppure route-guard non reagisce allo state.

Azione richiesta: patchare FE in modo che:
- dopo `login()` (POST `/j_security_check`) si faccia `await refresh()` e `navigate('/')`
- se `session.authenticated===true`, `/login` deve auto-redirectare a home (o pagina default).

File da cercare:
- `frontend/src/pages/LoginPage.tsx` (o equivalente)
- routing in `frontend/src/App.tsx`

## Comandi utili
### Diagnosi rete/porta
- `netstat -ano | findstr ":8080"`

### Probe HTTP (nota SPA Accept header)
- `Invoke-WebRequest -UseBasicParsing -Headers @{Accept='text/html'} http://localhost:8080/login -TimeoutSec 5`

### Log MSI
- `Get-Content C:\EmployeeScheduling\app\data\app.log -Tail 200`

## Packaging MSI (script)
- Script: `install-windows.ps1`
- WiX auto-download/extract:
  - `C:\tools\wix314\candle.exe` (verificato presente)
- Dist:
  - `dist\EmployeeScheduling-1.1.0.msi` (timestamp aggiornato durante rigenerazione)

## DB / utenti
DB dev nel repo:
- `databases/large_data.db` contiene user `admin` (ADMIN attivo).
- Password in chiaro non è leggibile: è bcrypt.

Nota: per MSI il DB iniziale viene creato al primo avvio; non fare affidamento sul DB dev.

## Git worktree
- C’erano log runtime accidentalmente tracciati: `databases/app.log.{1,2,3,5}`
- Cleanup fatto:
  - `.gitignore` aggiunto `databases/app.log*`
  - `git rm` dei log tracciati
  - rebuilt FE assets in `src/main/resources/META-INF/resources/assets/*` (rinomi hash + update `index.html`)

## Next actions (per Claude Code)
1. Fix definitivo UI post-login:
   - individuare LoginPage / submit handler
   - dopo successo, fare `await auth.refresh(); navigate('/')` (o `window.location.assign('/')` per evitare edge state)
   - aggiungere redirect automatico se session già autenticata.
2. Verificare fix Quarkus encryption-key nel pacchetto MSI:
   - reinstall MSI rigenerato e testare login/`/auth/me` senza 500.
   - controllare `EmployeeScheduling.cfg` generato dentro install: deve contenere `-Dquarkus.http.auth.session.encryption-key=...` con length >=16.
3. Verificare single-instance su MSI:
   - capire perché appaiono 2 PID; assicurare che sia un solo server su 8080 e che un secondo avvio non crei “mezze istanze”.
4. (Opzionale) Ripulire duplicati in `application.properties`:
   - `quarkus.http.auth.form.cookie-name` è duplicato due volte.

## File coinvolti principali
- `install-windows.ps1`
- `src/main/resources/application.properties`
- `frontend/src/auth/AuthContext.tsx`
- `frontend/src/pages/RegisterPage.tsx`
- (da trovare) `frontend/src/pages/LoginPage.tsx` / routing `frontend/src/App.tsx`
