# Verifica di portabilità SQLite/PostgreSQL

## Aggiornamento forense del 2 agosto 2026

Branch verificato: `backup-e-hardening`. La sezione storica sottostante documenta il lavoro
iniziato il 19 luglio sul branch `ORM`; questa sezione è la fotografia corrente e prevale in
caso di differenze di versione, CI o conteggi dei test.

### Esito corrente

- Schema: le migrazioni Flyway SQLite e PostgreSQL hanno lo stesso insieme di file e lo stesso
  contratto logico di tabelle, colonne, vincoli, indici e modifiche strutturali. Il nuovo
  `MigrationSchemaParityTest` rifiuta anche DDL strutturale futuro non compreso dal comparatore,
  evitando falsi verdi su view, trigger, sequence, type, function, schema, extension, domain,
  commenti e grant/revoke.
- Regressione completa SQLite precedente all'ultimo hardening: **117 test, 0 failure,
  0 error, 18 skipped**.
- Regressione completa PostgreSQL 18.4 precedente all'ultimo hardening: **117 test,
  0 failure, 0 error, 8 skipped**. I test reali di
  backup/restore PostgreSQL e i contratti condivisi sono verdi.
- Verifica mirata successiva all'hardening routing/parità/XSS: **8 test, 0 failure,
  0 error**, incluso `PUT` HTTP ostile e verifica della persistenza sanificata.
- Frontend: ESLint con `--max-warnings=0`, TypeScript e build Vite sono verdi. I precedenti
  22 errori lint e 13 warning sono stati eliminati senza soppressioni globali.
- Sicurezza UI/server: il corpo HTML dei template email viene sanificato prima
  dell'inserimento nel DOM, prima della persistenza REST e prima dell'invio; paste/drop sono
  intercettati prima di entrare nel DOM vivo. I link accettano solo schemi `http`, `https` e
  `mailto`. La timeline usa un'allow-list HTML/CSS mirata che conserva icone, azioni, SVG e
  proprietà visuali necessarie, rimuovendo handler, script e tag estranei.
- Concorrenza UI: caricamenti e mutazioni sono legati alla struttura/sessione che li ha
  avviati; al cambio struttura vengono invalidati modali, selezioni e target di eliminazione.
- Routing SPA: il fallback serve `index.html` esclusivamente per le undici rotte React note.
  Rotte sconosciute e API protette non vengono più trasformate in risposte HTML 200.
- CI: trigger su push di tutti i branch e pull request; frontend con installazione da lockfile,
  lint, build e controllo che gli asset hashed siano versionati; test SQLite su Windows e
  PostgreSQL 18 su Linux con client `pg_dump`/`pg_restore` 18 esplicito.

### Vincoli operativi e rischi residui

- `databases/large_data.db` è un file operativo modificato localmente (770.048 byte,
  SHA-256 `E0B8A7241AAFCA18ECDCA2FA1B251452C658E4D1D5A3DD12299C578CF3D58CBF`) e deve restare
  escluso dal commit finché l'utente non decide esplicitamente se conservarlo o ripristinarlo.
- Gli asset prodotti in `src/main/resources/META-INF/resources` devono essere committati
  atomicamente (nuovi hash, cancellazioni dei vecchi hash e `index.html`); il controllo CI
  introdotto impedisce una nuova pagina bianca dovuta ad asset mancanti.
- Il processo già in ascolto sulla porta 8080 deve essere riavviato dopo il merge per caricare
  il nuovo filtro SPA; un processo avviato prima della correzione conserva il vecchio fallback.
- Restano debiti non bloccanti già noti: date/orari testuali, assenza di optimistic locking
  generale, snapshot Timefold non atomica e native image non verificata.

---

Data verifica storica: 19 luglio 2026. Branch storico: `ORM`.

## A. Stato iniziale

| Componente | Stato rilevato |
|---|---|
| Quarkus | 3.37.4 |
| Java | release di compilazione 17; test eseguiti con JDK 21.0.5 |
| Hibernate ORM | classico, 7.4.3.Final; non Reactive |
| Panache | Active Record (`PanacheEntityBase`), 23 entità |
| Timefold | 1.33.0 |
| SQLite | Xerial `sqlite-jdbc` 3.53.2.1, Quarkiverse JDBC SQLite 3.0.11, `SQLiteDialect` community |
| PostgreSQL | assente inizialmente; aggiunti driver Quarkus e JDBC 42.7.11 |
| Flyway | assente inizialmente; aggiunto Flyway 12.0.0 |
| Schema iniziale | bootstrap JDBC/DDL SQLite eseguito da `DemoDataRepository` all'avvio; `databases/schema.sql` incompleto |
| Test DB | test SQLite reali; nessun test PostgreSQL, Testcontainers o Dev Services PostgreSQL |
| Native image | profilo Maven presente; compatibilità SQLite/PostgreSQL native non verificata |

Il database locale analizzato contiene 29 tabelle incluso `sqlite_sequence` e usa WAL. All'analisi iniziale erano presenti sette violazioni FK in `location_skills` (id 84–90 verso sedi mancanti). Dopo backup dedicato e autorizzazione esplicita, sono state eliminate con una riparazione transazionale strettamente limitata a quegli ID; lo stato finale è `PRAGMA integrity_check = ok` e `PRAGMA foreign_key_check` vuoto.

## B. Problemi trovati

| Severità | File/riga rilevata | Problema | SQLite | PostgreSQL | Correzione | Stato |
|---|---|---|---|---|---|---|
| Bloccante | `DemoDataRepository.java:149-2856` | Bootstrap, introspezione e riparazione schema SQLite sempre attivi | funziona ma mescola schema e business | tenta `jdbc:sqlite` anche con datasource PG | flag infrastrutturale `app.sqlite.legacy-bootstrap`; disattivato nei profili Flyway/PG | corretto per i nuovi profili; legacy conservato |
| Bloccante | `BackupService.java` | `VACUUM INTO` e accesso diretto al file SQLite | corretto per desktop | non applicabile | interfaccia `DatabaseBackupService` e implementazione PG che restituisce 501/backup esterno richiesto | corretto |
| Alta | `AffinityRepository.java`, `SpecialistRepository.java` | repository JDBC hardcoded SQLite | funzionante | non portabile | rimossi; REST usa entità/Panache | corretto |
| Alta | `DatabaseRequestGate.java` | serializzazione globale di ogni scrittura | utile per writer singolo | limita inutilmente la concorrenza | attivabile da configurazione; `true` SQLite, `false` PostgreSQL | corretto |
| Alta | configurazione | nessun driver/profilo PostgreSQL | n/a | avvio impossibile | quattro profili espliciti, driver PG, pool, health check | corretto; PostgreSQL 18.4 avviato nei test |
| Alta | schema | DDL runtime e nessuna cronologia | rischio drift | schema assente | Flyway V1 separata ma logicamente equivalente | verificato su entrambi i DB |
| Alta | schema legacy | sette FK orfane e molte FK fisiche mancanti | integrità parziale | n/a | backup, rimozione autorizzata dei soli ID 84–90; le nuove installazioni hanno FK complete | orfani corretti; schema legacy ancora documentato |
| Alta | entità temporali | date/orari persistiti come `String` (`ShiftEntity`, `EmployeeDateEntity`, template, log) | ordinamento ISO oggi funzionante | compatibile solo finché il formato resta ISO | nessun cambio massivo del dominio in questa modifica | aperto |
| Media | entità booleane | dieci booleani forzati a `INTEGER` con `@JdbcTypeCode` | naturale per schema storico | usa INTEGER invece di BOOLEAN | stesso contratto fisico 0/1 in V1 PG, con CHECK | portabile ma debito tecnico |
| Alta | concorrenza | nessun `@Version`; alcuni controlli preventivi di unicità | rischio basso monoutente | rischio lost update/TOCTOU | vincoli DB presenti e contratto duplicate insert aggiunto | optimistic locking ancora aperto |
| Media | query native | 21 `createNativeQuery`; SQL fisico nei resource | molte sono standard | sintassi corrente standard/`ON CONFLICT`, ma accoppiata al DDL | convertite le query sostituibili in Panache/JPQL; restano 2 punti di esecuzione per upsert atomici motivati | corretto salvo eccezioni motivate |
| Media | ordinamento | alcune letture figlie non hanno tie-break esplicito | ordine spesso stabile per caso | ordine non garantito | query principali Timefold già ordinate; audit completato | ulteriori tie-break raccomandati |
| Media | snapshot Timefold | più letture, non una singola snapshot transazionale | sufficiente monoutente | modifiche concorrenti possono produrre snapshot misto | solving resta fuori transazione e salvataggio è breve | snapshot atomica aperta |

## C. Query analizzate

L'audit ha cercato nell'intero `src/main`, nelle configurazioni e negli script tutti i costrutti richiesti. Inventario quantitativo iniziale:

- 21 chiamate `createNativeQuery`;
- 11 query JPQL/HQL esplicite;
- 83 creazioni di `PreparedStatement`;
- circa 739 esecuzioni JDBC, delle quali 625 sono righe seed `INSERT OR IGNORE` concentrate nel bootstrap legacy;
- 49 `list/listAll`, 28 `find/findById`, 32 `count`, 29 `delete` Panache.

Inventario iniziale delle query native e stato finale:

| File | Righe (dopo la modifica) | Uso | Portabilità |
|---|---:|---|---|
| `AffinityResource.java` | inizialmente 2 | proiezioni join operatori/specialisti | convertite in HQL/Panache, mantenendo un solo round-trip per operatore |
| `DemoDataRepository.java` | inizialmente 3, ora 2 | riepilogo date e upsert configurazioni/email log | riepilogo convertito in JPQL; `ON CONFLICT` mantenuto solo per upsert atomici concorrenti |
| `LabelResource.java` | inizialmente 4 | localizzazioni, nomi dinamici e delete | convertite in Panache con ordinamento esplicito |
| `StructureResource.java` | inizialmente 4 | cancellazione aggregati e conteggio riferimenti | convertite in Panache |
| `EmployeeScheduleDemoResource.java` | inizialmente 6 | conteggi, cleanup relazioni, unpin | convertite in Panache/aggiornamenti di entità managed |
| `SpecialistResource.java` | inizialmente 2 | cleanup affinità/sedi | convertite in Panache |

SQL specifico SQLite rimasto intenzionalmente isolato:

- `BackupService`: `PRAGMA busy_timeout`, `VACUUM INTO`;
- `DemoDataRepository` con `app.sqlite.legacy-bootstrap=true`: `PRAGMA`, `sqlite_master`, `sqlite_sequence`, DDL di upgrade, `datetime('now','localtime')`, seed `INSERT OR IGNORE`;
- test di migrazione legacy SQLite.

Costrutti comuni verificati:

- gli upsert runtime usano `INSERT ... ON CONFLICT ... DO UPDATE`, sintassi disponibile sia in SQLite sia in PostgreSQL;
- nessun `ILIKE`, `DISTINCT ON`, `RETURNING`, JSONB, array, cast `::type`, `date_trunc`, `generate_series` o sequence PostgreSQL è stato introdotto nel codice comune;
- le query dinamiche con nome tabella usano valori interni/allow-list; tutti i valori applicativi sono parametri;
- nessuna query comune usa `COLLATE NOCASE`; resta da testare la piena semantica Unicode/collation sui due motori.

## D. Modifiche effettuate

| Modifica | Prima | Dopo | Verifica |
|---|---|---|---|
| Dipendenze | solo SQLite | JDBC PostgreSQL, Flyway, modulo Flyway PG, health | `mvn clean compile` verde |
| Profili | SQLite implicito nel file comune | `sqlite`, `postgresql`, `test-sqlite`, `test-postgresql` | profilo SQLite avviato nei test |
| Schema | bootstrap Java | V1 SQLite e V1 PostgreSQL, 28 tabelle logiche, PK/FK/unique/indici/default equivalenti | Flyway SQLite migrate + validate + seconda migrate |
| Bootstrap legacy | sempre attivo | disattivato sui nuovi DB e su PG; mantenuto sul profilo desktop legacy | regressioni legacy isolate |
| Backup/restore | classe SQLite iniettata direttamente | interfaccia comune; staging e pubblicazione atomica; SQLite via Online Backup API; PostgreSQL `public` via `pg_dump`/`pg_restore --single-transaction`; API amministrativa protetta | restore HTTP e test avversariali reali su entrambi i motori |
| Writer gate | globale | configurabile per profilo | unit test esistente |
| Repository legacy | JDBC SQLite separato | rimossi; risorse condivise su Panache/EntityManager | compilazione e regressioni ORM |
| Contratto DB | inesistente | stessa classe su entrambi i profili: schema, identity, unique, FK, rollback, idempotenza Flyway | verde su SQLite e PostgreSQL 18.4 |
| Contratto REST condiviso | regressioni legate a fixture SQLite | stessa classe e fixture dinamiche per CRUD strutture, competenze, specialisti, affinità, sedi, dipendenti, date/assenze, turni diurni, localizzazioni, snapshot base e concorrenza | 13 test verdi su entrambi i DB |
| Adozione SQLite legacy | baseline automatica sul profilo `sqlite` | profilo `sqlite` solo per DB nuovi; `legacy-sqlite` conserva bootstrap e disattiva Flyway | test di configurazione; nessuna modifica al DB utente |
| CI | assente | job separati Windows/SQLite e Linux/PostgreSQL 16 reale | file pipeline aggiunto; esecuzione remota non ancora osservata |

Per abilitare l'API backup in qualunque profilo impostare `BACKUP_ADMIN_TOKEN` nell'ambiente del
processo. Se l'applicazione è pubblicata in rete, terminare TLS sul proxy e non inoltrare mai
`/backup/*` in chiaro. Durante un restore deve restare attiva una sola istanza applicativa.

## E. Differenze inevitabili

- Driver e dialect: Xerial/community SQLite contro JDBC/dialect PostgreSQL.
- `quarkus.datasource.db-kind` e `quarkus.flyway.locations` sono proprietà Quarkus fissate in build: un JAR server deve essere costruito con il profilo `postgresql`; non si può riutilizzare il JAR costruito come SQLite cambiando soltanto il profilo a runtime.
- Identity: `INTEGER PRIMARY KEY AUTOINCREMENT` contro `GENERATED BY DEFAULT AS IDENTITY`.
- Il tipo `REAL` SQLite corrisponde a `DOUBLE PRECISION` PostgreSQL.
- I booleani restano INTEGER 0/1 su entrambi per compatibilità con le entità e il DB storico; PostgreSQL aggiunge `CHECK`.
- Il backup SQLite usa `VACUUM INTO` e restore tramite Online Backup API; PostgreSQL usa `pg_dump -Fc` e ripristina il solo schema `public` con `pg_restore --single-transaction`, dopo staging, snapshot e validazione strutturale completa. Un errore PostgreSQL viene annullato dalla stessa transazione.
- SQLite è destinato a una sola istanza locale e conserva il writer gate; PostgreSQL usa il pool e scritture concorrenti.
- Il profilo `sqlite` non esegue più baseline automatica: gestisce soltanto database nuovi. Le installazioni storiche usano `legacy-sqlite`, con Flyway disattivato, finché non viene eseguita una procedura amministrata di adozione con backup e fingerprint.

## F. Matrice di compatibilità

`Sì` significa test eseguito realmente. La suite storica di regressione endpoint usa ancora fixture SQLite esplicite anche quando la suite principale parte con PostgreSQL: non viene quindi attribuita a PostgreSQL.

| Funzionalità | SQLite | PostgreSQL | Test |
|---|---|---|---|
| CRUD strutture | Sì | Sì | contratto REST condiviso create/read/update/delete |
| CRUD sedi | Sì | Sì | contratto REST condiviso; sostituzione e cleanup delle relazioni competenze inclusi |
| CRUD utenti/specialisti | Sì | Sì | contratto REST condiviso per specialisti, incluso isolamento per struttura |
| CRUD dipendenti | Sì | Sì | contratto REST condiviso; persistenza e cleanup delle relazioni competenze inclusi |
| Disponibilità/assenze | Sì | Sì | contratto CRUD condiviso su `employee_dates` e riepilogo aggregato |
| Turni diurni/notturni nello stesso giorno | Sì | Sì | CRUD turno condiviso, skill, snapshot e cleanup |
| Turni oltre mezzanotte | non supportato | non supportato | il contratto condiviso verifica l'attuale risposta 400; nessuna nuova funzione introdotta |
| Competenze | Sì | Sì | contratto CRUD REST condiviso |
| Impostazioni e cleanup struttura | Sì | Sì | contratto REST condiviso |
| Isolamento assegnazioni tra strutture | Sì | Sì | contratto REST condiviso |
| Localizzazioni e ownership | Sì | Sì | contratto REST condiviso |
| Filtri/paginazione/case insensitive | parziale | non verificato | manca dataset Unicode condiviso completo |
| Vincoli univoci | Sì | Sì | `DatabasePortabilityContractTest` |
| Foreign key | Sì | Sì | contratto DB; SQLite legacy finale con `foreign_key_check` vuoto |
| Transazioni/rollback | Sì | Sì | contratto DB condiviso |
| Timefold | Sì | Sì, snapshot base | `/demo-data/generate` verificato sullo stesso turno e finestra; dataset completo e solve comparativo restano fuori |
| Migrazioni | Sì | Sì | migrate/validate/idempotenza condivisi |
| Concorrenza | Sì, serializzata | Sì per upsert impostazioni | otto PUT simultanei, una riga finale; optimistic locking su altre entità non implementato |

## G. Rischi residui e verdetto

PostgreSQL 18.4 locale è stato configurato con due database separati, `employee_scheduling` e `employee_scheduling_test`. Il comando reale:

```powershell
mvn -B -ntp test "-Dtest=DatabasePortabilityContractTest" "-Dquarkus.test.profile=test-postgresql"
```

ha applicato Flyway V1, validato lo schema, verificato la seconda migrazione idempotente ed eseguito con successo identità del motore, identity, vincolo univoco, FK e rollback. La suite REST condivisa contiene tredici test e copre le funzioni persistenti esistenti migrate: strutture, competenze, specialisti e affinità, sedi, dipendenti, disponibilità/assenze, turni diurni, localizzazioni, snapshot base e concorrenza impostazioni. Il comportamento esistente che rifiuta turni oltre mezzanotte è uguale sui due database e non è stato ampliato.

Restano da chiudere prima di dichiarare la portabilità completa:

1. osservare verde anche il job PostgreSQL della CI remota;
2. ampliare, se richiesto in futuro, il dataset comparativo Timefold e il solve end-to-end;
3. valutare separatamente la conversione delle date testuali a tipi `java.time`, che richiede migrazione dati e cambia il modello fisico;
4. mantenere documentati i due upsert nativi atomici residui e i tie-break deterministici aggiunti;
5. valutare `@Version` soltanto come futura funzione multiutente, perché richiede nuove colonne e modifica il contratto di aggiornamento;
6. aggiungere validazioni Jakarta coerenti con lunghezze/nullability;
7. definire una procedura controllata di adozione Flyway per i DB SQLite legacy con fingerprint e backup;
8. verificare native image separatamente.

Risultati finali eseguiti localmente:

- `mvn -DskipTests clean compile`: **BUILD SUCCESS**;
- `mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"`: **46 test, 0 failure, 0 error, 0 skipped**;
- `mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"`: **48 test, 0 failure, 0 error, 2 test amministrativi opt-in skipped**; sedici contratti DB/REST sul PostgreSQL 18.4 reale, regressioni legacy isolate su SQLite.
- JAR costruito con `mvn -B -ntp clean package "-DskipTests" "-Dquarkus.profile=postgresql"`, avviato sul database operativo e verificato su `/q/health`: **HTTP 200**; processo arrestato dopo lo smoke test.

### Popolamento SQLite → PostgreSQL

È stato aggiunto un test amministrativo opt-in che apre il SQLite sorgente in sola lettura e, per impostazione predefinita, esegue soltanto un dry-run con rollback. Il commit richiede conferme esplicite separate per operazione distruttiva, database e URL target, hash del sorgente e hash del backup PostgreSQL. Prima del commit verifica inoltre schema `public`, Flyway V1, insieme esatto delle tabelle e delle colonne, advisory lock, zero scarti, conteggi, vincoli e riallineamento delle sequence.

Primo risultato sul database locale reale:

- 28 tabelle applicative elaborate;
- 5.485 righe SQLite lette;
- 5.478 righe accettate dallo schema PostgreSQL;
- 7 righe rifiutate, tutte e sole `location_skills.id` 84–90, già note come orfane verso `locations.id` 8–10;
- nessun'altra incompatibilità di dati o schema rilevata;
- SQLite sorgente invariato (SHA-256 verificato);
- PostgreSQL di test ripristinato dal rollback e successivamente validato con i 3 contratti DB verdi.

Dopo backup e rimozione esplicitamente autorizzata delle sette associazioni orfane, il dry-run è stato ripetuto:

- 5.478 righe SQLite lette;
- 5.478 righe inserite nella transazione PostgreSQL;
- 0 righe rifiutate;
- **BUILD SUCCESS**, seguito da rollback completo del PostgreSQL di test.

Il database PostgreSQL operativo `employee_scheduling` è stato quindi popolato in una singola transazione controllata:

- backup pre-import in formato custom `pg_dump`: `C:\tmp\employee_scheduling_before_population_20260719_172611.dump`;
- SHA-256 backup: `73B39E71E720341F45C83260D87B3DA8B65B140F861E1A3DE6D0B5F18CF75FDB`;
- SHA-256 sorgente SQLite verificato: `3C56D4B92D899F148E5DDAC1C08F78491A7C6B4C94C44C5760B82A7BED0F98E4`;
- 5.478 righe lette e 5.478 inserite, zero scarti;
- verifica read-only successiva: 28 tabelle applicative, totale 5.478 righe, Flyway V1;
- suite completa separata su `employee_scheduling_test`: 48 test, zero failure/error, 2 test amministrativi opt-in skipped.

## Comandi operativi

PowerShell richiede le proprietà Maven tra virgolette:

```powershell
# Suite SQLite reale (database isolato in target)
mvn -B -ntp clean verify "-Dquarkus.test.profile=test-sqlite"

# Suite PostgreSQL reale (richiede TEST_DATABASE_URL/USERNAME/PASSWORD)
mvn -B -ntp clean verify "-Dquarkus.test.profile=test-postgresql"

# Dry-run popolamento completo del PostgreSQL di test (rollback automatico)
mvn -B -ntp test "-Dtest=SqliteToPostgresqlPopulationTest" "-Dquarkus.test.profile=test-postgresql" "-Dpopulation.source=databases/large_data.db" "-Dpopulation.expected-rejections=0"

# Desktop SQLite
mvn quarkus:dev "-Dquarkus.profile=sqlite"

# Desktop SQLite esistente senza cronologia Flyway
mvn quarkus:dev "-Dquarkus.profile=legacy-sqlite"

# Server PostgreSQL
mvn quarkus:dev "-Dquarkus.profile=postgresql"

# Pacchetto desktop SQLite
mvn -B -ntp clean package "-DskipTests" "-Dquarkus.profile=sqlite"

# Pacchetto server PostgreSQL (il profilo è necessario già durante la build)
mvn -B -ntp clean package "-DskipTests" "-Dquarkus.profile=postgresql"
```

Non esiste un Maven wrapper nel repository; i comandi usano l'installazione `mvn` reale.

## Salvaguardia del database locale

Il DB non è incluso nello staging. Durante la verifica è stato creato un backup consistente automatico e il file locale è stato ripristinato da quello snapshot. Dopo la pulizia autorizzata degli orfani, lo stato finale è: `integrity_check=ok`, 29 tabelle e zero violazioni FK. Copie aggiuntive sono disponibili in:

- `C:\tmp\large_data-before-main-merge.db`;
- `C:\tmp\large_data-after-accidental-test-bootstrap.db` (solo diagnostica dello stato successivo);
- `C:\tmp\large_data-before-location-skill-orphan-cleanup.db` (ripristino completo precedente alla pulizia);
- `databases/backups/large_data_20260719_154225_auto.db` (snapshot ripristinato).
