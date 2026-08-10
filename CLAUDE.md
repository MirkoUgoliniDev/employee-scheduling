# Istruzioni di progetto — Employee Scheduling

Questo file è la **memoria locale del progetto** (versionata in git, condivisa): contiene
regole e riferimenti che Claude deve seguire lavorando su questo repository.

## Stack

- **Backend**: Java 21 + Quarkus 3.37.4 + Timefold Solver **1.33.0**, dati via Hibernate ORM/Panache
- **Database**: doppio motore supportato — **SQLite** (`databases/employee_scheduling.db`, profilo di default)
  e **PostgreSQL** (profilo `postgresql`). Schema creato da Flyway, migrazioni separate per motore
  in `src/main/resources/db/migration/{sqlite,postgresql}`.
- **Frontend**: React 19 + TypeScript 5.9 + Vite 8 (`frontend/`), build servita da Quarkus su :8080
- **Nota compilatore**: il pom fissa `maven.compiler.release=17` anche se si gira su JDK 21. Le
  API introdotte in 18-21 (per esempio `Thread.ofVirtual()`) **non compilano**: verificarlo prima
  di usarle, l'errore arriva solo in fase di test se l'IDE ha già prodotto i `.class`.

## Backup: prerequisito operativo su PostgreSQL

Sul profilo `postgresql` il backup è eseguito dall'applicazione con **`pg_dump`** (formato custom
`-Fc`, un file `.dump`). Servono quindi i **PostgreSQL client tool installati**, con major
**maggiore o uguale** a quella del server: `pg_dump` rifiuta di leggere un server più recente di
sé, e in quel caso la funzione si disattiva da sola con un messaggio esplicito.

I binari **non sono nel PATH** su Windows: vengono cercati in
`C:\Program Files\PostgreSQL\<major>\bin` scegliendo la major più alta, oppure dove indica
`backup.postgresql.bin-dir`. La password viaggia in `PGPASSWORD` nell'ambiente del processo
figlio — **mai** nell'URL di connessione, che su Windows è leggibile da altri processi.

Il **ripristino PostgreSQL** richiede anche `pg_restore` accanto a `pg_dump`; in sua assenza
`getSettings()` espone `restoreSupported=false` e la UI nasconde il pulsante. Prima di toccare il
database il servizio copia e valida il dump, confronta l'intero TOC filtrato dello schema `public`
e crea un dump `prerestore` validato. `pg_restore --clean --if-exists --single-transaction` limita
il restore a `public`: o applica tutto oppure PostgreSQL lascia il database invariato. Gli altri
schemi non vengono inclusi né modificati. L'API `/backup/*` richiede sempre
`BACKUP_ADMIN_TOKEN`; se esposta fuori da localhost deve transitare esclusivamente tramite TLS.
Per un restore operativo va mantenuta una sola istanza applicativa attiva, su entrambi i motori.

## Riferimenti di progettazione (Timefold)

Per ogni problema di **progettazione** del solver o del dominio — modellazione di planning
entity/variable, vincoli (constraints/score), pattern del solver, tuning — **fare riferimento
alla documentazione ufficiale Timefold** (leggerla, non procedere solo a memoria: API e pattern
cambiano tra versioni e siamo su **1.33**):

- **Punto di partenza (introduzione + indice)**: https://docs.timefold.ai/timefold-solver/latest/introduction
- Common patterns (domain modeling): https://docs.timefold.ai/timefold-solver/latest/domain-modeling/common-patterns

## Lingua: dove l'inglese, dove l'italiano

Il progetto deriva dal quickstart `employee-scheduling` di Timefold ed è pubblico:
chi ci arriva da lì non legge l'italiano. Ma non tutto va tradotto, e la distinzione
è netta.

**In inglese:**

- **Tutti i commenti nel codice** — Java, TypeScript, TSX, Python, SQL, shell,
  PowerShell, `.properties`. Regola tassativa dall'8 agosto 2026. Unica eccezione:
  le migrazioni Flyway versionate già pubblicate sono immutabili, commenti compresi,
  perché Flyway include i commenti nel checksum e modificarli impedisce l'avvio sui
  database esistenti. I commenti delle nuove migrazioni devono nascere in inglese.
- `README.md`, `LICENSE`, `NOTICE` — la vetrina pubblica.
- **I messaggi degli installer e del wizard** — `echo`, `Write-Host`, `print()`,
  `die`, `info`, `warn`, `runner.log()`, i nomi e le descrizioni degli step, la
  pagina web del wizard Raspberry, l'help di `argparse` — **e tutta la
  documentazione distribuita**: `setup/INSTALL.md`, `docs/INSTALLATION.md`,
  `docs/PACKAGING-WINDOWS-MSI.md`. Regola cambiata il 9 agosto 2026: fino a quel
  giorno erano in italiano, ma l'installer è la prima cosa che vede chi arriva
  dal quickstart pubblico, e un wizard italiano lo blocca prima ancora di vedere
  l'applicazione. Lo stesso giorno `docs/` ha smesso di essere un archivio
  interno: handoff, diari e rapporti datati sono stati cancellati (restano nella
  history git) e ci sono rimasti solo due documenti manutenuti, entrambi linkati
  dal README. Quello che si pubblica si scrive in inglese.

**In italiano:**

- **Tutto ciò che l'utente legge nell'applicazione**: testo dell'interfaccia,
  messaggi di errore, toast, e il secondo argomento di
  `t('chiave', 'testo di ripiego')`.
- **Questo file**, che è memoria di lavoro condivisa e non documentazione
  distribuita.

Tradurre un commento **non** significa accorciarlo. I commenti di questo progetto
spiegano il *perché*, spesso citando il difetto concreto che hanno evitato e i
numeri misurati: quella sostanza va conservata per intero. Un commento ridotto a
una frase generica ha perso il suo unico motivo di esistere.

Dopo una traduzione massiva, l'unica prova che non è stato toccato del codice per
sbaglio è che `mvn -B test` e `npx tsc -b` restino verdi.

Attenzione: quei due comandi **non guardano `setup/`**. Il wizard è Python e non
viene né compilato né importato da Maven o da tsc, quindi un f-string rotto lì
passa entrambi i controlli senza un rumore. Per le modifiche sotto `setup/` la
prova equivalente è quella che gira anche in CI
(`.github/workflows/release.yml`):

```
python3 -m compileall -q setup
python3 setup/wizard.py --help
```

## Regole di lavoro

- **Localizzare SEMPRE il testo UI**: ogni stringa aggiunta va con `t()` + traduzione in tutte
  e 5 le lingue (it/en/fr/es/de). Regola tassativa.
- **Le localizzazioni devono restare allineate su ENTRAMBI i database** (SQLite e PostgreSQL).
  Regola tassativa, vedi la sezione dedicata qui sotto.
- **Nome del database, allineato fra i due motori** (dal 9 agosto 2026): il file SQLite è
  `databases/employee_scheduling.db`, lo stesso nome del database e del ruolo PostgreSQL. Prima
  si chiamava `large_data.db`, un nome ereditato dal quickstart che non diceva niente.
  `LegacyDatabaseName`, chiamata da `AppMain.main()` **prima che Quarkus parta**, rinomina il
  vecchio file dove lo trova: senza quella migrazione un'installazione esistente si troverebbe
  Flyway che crea un database nuovo e vuoto, con i dati veri ancora sul disco e nessun errore.
  Se sposti quel codice, deve restare prima di Flyway.
- **File `.db`: committato solo quello di prova.** `databases/employee_scheduling.db` è
  tracciato apposta (`.gitignore` lo esclude e poi lo riammette con `!`): è il database
  dimostrativo pubblicato, con nomi ed email di fantasia, `app_users` **vuota** perché il primo
  avvio possa creare l'amministratore, e nessuna credenziale SMTP. Tutti gli altri `.db` —
  snapshot `_pre-*`, `standalone-test.db`, backup — restano fuori. Prima di committarlo dopo
  averci lavorato sopra, ripassare la checklist qui sotto: il file viene riscritto a runtime e
  può essersi ripopolato di dati tuoi.

## Anonimizzazione: nessun dato personale nel database pubblicato

`databases/employee_scheduling.db` è in un repository pubblico, e qualunque database che esca
dalla macchina — allegato a una segnalazione, passato a un collega — ha lo stesso vincolo:
**nessun dato personale reale**. Vale per **entrambi i motori**: quello che si fa su SQLite va
fatto identico su PostgreSQL.

Da anonimizzare, in ogni database:

| Tabella | Colonne |
|---|---|
| `employees`, `specialists` | `first_name`, `last_name`, `email` |
| `structures` | `name`, `address`, `phone` |
| `email_log` | `sent_to`, `filename` (contiene nome e cognome nel nome del PDF) |
| `email_settings` | `host`, `username`, `password`, `mail_from` — **è una credenziale SMTP valida** |
| `app_users` | **svuotarla del tutto**: `password_hash` è una credenziale, e un ADMIN già presente impedisce al primo avvio di creare l'amministratore, lasciando chi installa fuori dalla propria applicazione |

Regole apprese sul campo:

1. **Filtrare i nomi inventati contro quelli reali.** Il database conteneva già nomi italiani
   comuni: pescando dai soliti (Rossi, Bianchi, Ferrari) se ne ricreano decine identici agli
   originali. Usare un pool di nomi poco frequenti e scartare via SQL tutto ciò che compare nel
   backup pre-anonimizzazione — non fidarsi dell'occhio.
2. **Pool di nomi e cognomi disgiunti**, così nome e cognome non possono mai coincidere.
3. **Email derivate dal nuovo nome**, dominio `example.com` (riservato dalla RFC 2606: non
   esiste e non può essere consegnato per errore).
4. **`VACUUM` alla fine**, sempre. Senza, le stringhe vecchie restano leggibili nelle pagine
   libere e un `grep` sul `.db` committato le ritrova comunque.
5. **Verificare sul binario**, non solo sulle tabelle:
   `grep -a -c -i "<stringa>" databases/employee_scheduling.db` deve dare 0 per ogni dato reale noto, e
   `grep -a -o -E "[A-Za-z0-9._%-]+@[A-Za-z0-9.-]+"` non deve restituire domini diversi da
   `example.com` (i segnaposto `esempio.it`/`exemple.fr`/`ejemplo.es`/`beispiel.de` sono testo
   delle traduzioni UI, non dati).
6. **Backup datato fuori dal repo** prima di iniziare: l'anonimizzazione non è reversibile.

## Localizzazioni: un solo posto, due database

Le etichette UI vivono nelle tabelle `labels` + `localizzazioni` di **ogni** database. Il seed
storico in `DemoDataRepository.seedLabelTranslations*` usa SQL SQLite-only (`INSERT OR IGNORE`)
ed è eseguito **solo** con `app.sqlite.legacy-bootstrap=true`: sul profilo `postgresql` non gira
mai. Una chiave aggiunta lì compare su SQLite e manca su PostgreSQL, dove l'utente vede il
fallback italiano in tutte le lingue.

**Sorgente di verità unica**: `src/main/resources/i18n/ui-translations.tsv`.

- Formato TSV a 7 campi: `key`, descrizione, `it`, `en`, `fr`, `es`, `de`. Tutte e 5 le lingue
  obbligatorie e non vuote.
- `UiTranslationSyncService` lo applica allo startup **via JPA**, quindi identico su SQLite e
  PostgreSQL. È **additivo**: non sovrascrive mai un valore già presente, così le modifiche
  fatte dalla pagina Etichette sopravvivono ai riavvii.
- Essendo una risorsa e non codice, non ha il limite JVM di 64KB per metodo che aveva costretto
  a spezzare il seed storico in più metodi.

**Procedura per ogni nuova stringa UI**:

1. Nel frontend usare `t('chiave', 'fallback italiano')`.
2. Aggiungere **una riga** a `ui-translations.tsv` con le 5 lingue. **Mai** aggiungere chiavi ai
   metodi `seedLabelTranslations*`: sono SQLite-only e restano solo per compatibilità storica.
3. Incrementare `CACHE_KEY` in `frontend/src/i18n/index.ts` (cache-bust dei client).
4. `mvn test` — `UiTranslationCatalogTest` fallisce la build se una lingua manca, se una chiave
   è duplicata o se una chiave esiste nel seed SQLite ma non nel catalogo portabile.

Verifica su entrambi i motori (`UiTranslationSyncTest` gira in entrambi i profili):

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```
