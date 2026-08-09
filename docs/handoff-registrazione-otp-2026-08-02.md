# Handoff — Registrazione CAPOSALA via OTP con approvazione ADMIN

**Data:** 2 agosto 2026, sessione serale (post-merge)
**Branch:** `backup-e-hardening` (avanti di 1 commit rispetto a origin al momento della scrittura)
**Documento scritto per:** chi riprende il lavoro senza aver seguito la sessione.

---

## 1. Stato in una riga

Implementata la **registrazione autonoma del CAPOSALA** via OTP su email, con account in
**attesa di approvazione** da parte di un ADMIN e notifica email automatica. Aggiunta la
colonna `email` alla tabella `app_users` (in 3 posti, come da convenzione del progetto).
**158 test SQLite verdi** (151 preesistenti + 7 nuovi), frontend build ok.

**Le modifiche NON sono ancora committate.** Git status: 13 file modificati + 4 nuovi file
sorgente + migrazioni V3 + 40 asset rebuildati.

---

## 2. Cosa è stato fatto

### 2.1 Flusso di registrazione (come richiesto dal committente)

| Passo | Endpoint | Dettagli |
|---|---|---|
| 1. Richiesta OTP | `POST /auth/register/otp` | Body `{email}`. Valida formato, rifiuta email già registrata (409 `EMAIL_ALREADY_REGISTERED`), rate-limit, genera OTP 6 cifre e lo invia via Mailer |
| 2. Verifica OTP | `POST /auth/register/verify` | Body `{email, otp}`. Confronto a tempo costante, max 5 tentativi, emette **token monouso** |
| 3. Creazione account | `POST /auth/register/complete` | Body `{token, username, password}`. Crea CAPOSALA con **`active=false`** (in attesa) e **notifica tutti gli ADMIN** con email |

### 2.2 Decisioni prese

- **Nessuna struttura assegnata** alla registrazione: l'ADMIN la gestirà dopo (scelta del committente). La tabella `app_users` NON ha una colonna struttura: se servirà, andrà aggiunta.
- **ADMIN mantiene la creazione manuale** in UsersPage (senza OTP), con nuovo campo email (scelta del committente).
- **Approvazione**: l'ADMIN attiva l'account dal nuovo pulsante "Approva / attiva" in UsersPage. Fino ad allora il login è bloccato.
- **OTP in memoria** (classe `OtpStore`, `@ApplicationScoped`): niente tabella dedicata → niente migrazione aggiuntiva. Compromesso accettato: un riavvio invalida le registrazioni in corso.
- **Rate limit**: 5 invii per email per finestra, 10 per IP. Scadenza OTP 5 minuti, 5 tentativi di verifica.
- **Notifica ADMIN**: best-effort via Mailer; se nessun ADMIN ha email, solo log.

### 2.3 File toccati

**Backend nuovi:**
| File | Ruolo |
|---|---|
| `rest/RegistrationResource.java` | I 3 endpoint pubblici (`@PermitAll`) |
| `rest/OtpStore.java` | Store in-memory: OTP hashato, scadenza, tentativi, rate-limit per email/IP, lookup token→email |

**Backend modificati:**
| File | Modifica |
|---|---|
| `persistence/AppUserEntity.java` | Campo `email` + `findByEmail()` |
| `dto/AppUser.java` | Campo `email` |
| `rest/UsersResource.java` | Email in create/update con validazione + `USER_EMAIL_DUPLICATE`; email nel DTO di risposta |
| `rest/AuthResource.java` | `/auth/me` risponde `authenticated=false` + `reason=INACTIVE` per account non attivi |
| `rest/ApiErrors.java` | Helper `tooManyRequests()` e `unauthorized()` |
| `rest/DemoDataRepository.java` | `ensureAppUsersTable()`: colonna email nel CREATE + `ALTER TABLE ADD COLUMN` idempotente (try/ignore) + indice unico |

**Migrazioni (V3, entrambi i motori — obbligatorio per MigrationSchemaParityTest):**
| File | Contenuto |
|---|---|
| `db/migration/sqlite/V3__add_user_email.sql` | `ALTER TABLE app_users ADD COLUMN email TEXT;` + `CREATE UNIQUE INDEX idx_app_users_email` |
| `db/migration/postgresql/V3__add_user_email.sql` | Idem (normalizzazione identica richiesta dal test) |

**Frontend nuovi:**
| File | Ruolo |
|---|---|
| `pages/RegisterPage.tsx` | 3 passi: email → OTP → profilo, poi schermata "attendi approvazione" |
| `api/register.ts` | Client dei 3 endpoint |

**Frontend modificati:**
| File | Modifica |
|---|---|
| `App.tsx` | **Ristrutturato**: route pubbliche `/login` e `/register` fuori dall'app protetta (prima il login era fuori da qualsiasi Routes) |
| `pages/LoginPage.tsx` | Link "Registrati", messaggio per account inattivo (`login.inactive`) |
| `api/auth.ts` | `SessionInfo.reason`, nuova eccezione `InactiveAccountError` |
| `pages/UsersPage.tsx` | Colonna email, campo email nel form, pulsante "Approva / attiva" (`faUserCheck`), badge "In attesa" |
| `api/users.ts` | `email` nei tipi AppUser/CreateUserPayload/UpdateUserPayload |
| `i18n/backendErrors.ts` | 8 nuovi codici: `EMAIL_INVALID`, `EMAIL_ALREADY_REGISTERED`, `OTP_INVALID`, `OTP_ALREADY_USED`, `OTP_TOO_MANY`, `OTP_SEND_FAILED`, `USER_EMAIL_DUPLICATE` |
| `i18n/ui-translations.tsv` | ~37 nuove chiavi in 5 lingue (register.*, login.noAccount/inactive/register, user.email/pending/activate*, btn.approve, toast.userActivated, msg.err.*) |

**Test:**
| File | Contenuto |
|---|---|
| `test/security/RegistrationFlowTest.java` | 7 test: email malformata, email già registrata, OTP inviato, OTP errato, flusso completo (crea pending), token sconosciuto, username duplicato |

### 2.4 Trappole pagate (importanti)

- **Schema app_users in 3 posti**: `V2__app_users.sql` (sqlite+pg), `DemoDataRepository.ensureAppUsersTable()`. Qualsiasi futura modifica alla tabella utenti deve toccare tutti e 3 + una nuova migrazione V(N) in entrambe le cartelle con DDL normalizzato identico, altrimenti `MigrationSchemaParityTest` fallisce la build.
- **`MigrationSchemaParityTest`** confronta SQLite vs PostgreSQL (nome file, DDL normalizzato, indici): le due V3 devono essere byte-identiche post-normalizzazione.
- **Route pubbliche**: prima di questa sessione `App.tsx` montava `LoginPage` fuori da `<Routes>` — non esisteva infrastruttura per una pagina pubblica navigabile. Ora `/login` e `/register` sono route reali.
- **Test diretti su entity richiedono transazione**: nei test, `persist()`/`delete()` diretti su `AppUserEntity` lanciano `TransactionRequiredException` — usare `QuarkusTransaction.requiringNew().run(...)` (pattern di `UsersResourceTest`).
- **Ordine validazioni in `complete()`**: il token va verificato PRIMA di username/password, altrimenti un payload con token falso e username corto rivelerebbe il check via codice diverso (`BAD_REQUEST` vs `OTP_INVALID`).
- **Rate-limit per email** (`OtpStore.isEmailRateLimited`): implementazione volutamente grezza (una entry recente = limitato); documentata nel codice.

---

## 3. Cosa rimane da fare (in ordine di priorità)

### 3.1 Da completare nella prossima sessione (urgente)

1. **Committare e pushere** le modifiche in working tree (13 modificati + 4 nuovi + V3 + test + 40 asset), poi merge in `main`. Niente è stato ancora committato.
2. **Verificare il flusso completo a mano** con `mvn quarkus:dev`:
   - `http://localhost:8080/register` → email → OTP (in dev le mail sono **loggate**, mock: il codice appare nei log di console)
   - Completare profilo → messaggio "attendi approvazione"
   - Login come admin → UsersPage → approvare
   - Login del CAPOSALA approvato → deve entrare
   - Login del CAPOSALA **non** approvato → messaggio "Account in attesa di approvazione"
3. **Notifica ADMIN**: con `quarkus.mailer.mock=true` (default dev) le mail di notifica sono loggate. Per test reale serve SMTP configurato (`.env` o Configurazione → Parametri Email).

### 3.2 Migliorie successive (bassa priorità)

4. **Colonna struttura per utente**: se i CAPOSALA devono vedere solo la loro struttura, serve `structure_id` su `app_users` (nuova migrazione V4 + 3 posti + UI). Oggi gli utenti non hanno alcun legame con le strutture (scelta del committente: "nessuna struttura alla registrazione").
5. **Test del rate-limit**: i 7 test coprono validazione e flusso, ma non il rate-limit (richiederebbe sleep o store finto).
6. **Recupero password via email** (password reset OTP): infrastruttura OTP già pronta, manca l'endpoint di reset per utenti esistenti.
7. **Test PostgreSQL**: `PostgresqlBackupServiceTest` ecc. restano skipped senza PG locale. I nuovi test OTP sono engine-agnostici ma non ancora eseguiti su `test-postgresql`.
8. **Email OTP in chiaro nei log dev**: in mock le mail OTP sono visibili nei log. Accettabile in dev, MAI in produzione (mock=false).

### 3.3 Debiti preesistenti (non toccati in questa sessione)

- 3 debiti tecnici documentati nel handoff-2026-08-02.md §7.5 (BackupFileManager dead code già ripulito; `saveLabelTranslations` solo INSERT; `saveSkills` upsert su ID orfano).
- ~203 warning/errori del Language Server VSCode, pre-esistenti e non errori reali di build (verificato: `mvn clean compile` BUILD SUCCESS, 87 file).

---

## 4. Comandi utili

```powershell
# Test completi SQLite
mvn -B test "-Dquarkus.test.profile=test-sqlite"

# Solo i test di registrazione
mvn -B test "-Dquarkus.test.profile=test-sqlite" -Dtest=RegistrationFlowTest

# Build frontend
cd frontend && npm run build

# Avvio dev
mvn quarkus:dev
```

Credenziali dev: bootstrap admin = `admin` / `admin123` (configurata in `application.properties`).

---

## 5. Stato del flusso approvazione

```
CAPOSALA                        SERVER                         ADMIN
   │  POST /auth/register/otp     │                              │
   │ ────────────────────────────►│  genera OTP 6 cifre          │
   │ ◄────────────────────────────│  invia email (mock=log)      │
   │  POST /auth/register/verify   │                              │
   │ ────────────────────────────►│  verifica hash, emette token │
   │ ◄────────────────────────────│                              │
   │  POST /auth/register/complete │                              │
   │ ────────────────────────────►│  crea utente active=false    │
   │ ◄────────────────────────────│  notifica ADMIN via email ───►│
   │  "attendi approvazione"      │                              │
   │                              │  [UsersPage: Approva] ───────►│
   │  login (bloccato: INACTIVE)  │  active=true                 │
   │  login (ok dopo approvazione)│                              │
```
