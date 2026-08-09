# Perizia tecnica avversariale — backup e restore SQLite/PostgreSQL

**Progetto:** Employee Scheduling

**Branch:** `backup-e-hardening`

**Data:** 1 agosto 2026
**Stato:** rapporto conclusivo; verifiche automatiche finali indicate nella sezione 7

## 1. Verdetto esecutivo

Il sottosistema è **affidabile per backup e restore operativi** nel perimetro dichiarato:
una sola istanza applicativa, backup conservati in una directory fidata e protetta, restore con
la stessa versione/schema dell'applicazione e requisiti TLS/credenziali rispettati.

Non sono rimasti blocker noti dopo quattro passaggi avversariali indipendenti e la correzione dei
finding riprodotti. Il giudizio non equivale però a una certificazione assoluta né a una catena di
custodia forense: i file non sono firmati/HMAC, non sono immutabili e non attestano da soli origine,
autore o assenza di manomissione da parte dell'amministratore della macchina.

## 2. Perimetro e metodo

Sono stati esaminati codice backend, API `/backup/*`, frontend, configurazione, filesystem,
concorrenza, processi `pg_dump`/`pg_restore`, migrazioni Flyway, test e artefatti generati. Tre
revisori indipendenti hanno operato in parallelo su:

1. transazioni, crash window, rollback e compatibilità semantica;
2. autenticazione, TLS, segreti, ACL, symlink/TOCTOU e audit;
3. evidenze, test, frontend e coerenza documentale.

I finding sono stati corretti e sottoposti a ulteriori passaggi avversariali. Il database runtime
dell'utente `databases/large_data.db` è stato escluso da ogni modifica intenzionale. SHA-256 di
riferimento e finale:

`F495E9B6841C34F2EE430927121BEBB932D7FFCA8C3B40F29D2F61FB3D69C0E6`

## 3. Garanzie implementate

### SQLite

- Snapshot consistente con `VACUUM INTO`, staging nascosto, verifica `integrity_check` e
  `foreign_key_check`, fsync del file e pubblicazione atomica nello stesso filesystem.
- Sorgente di restore copiata tramite handle `NOFOLLOW_LINKS`; staging non visibile alla lista.
- Preflight prima del DB vivo: confronto del DDL completo e dell'intera storia Flyway, inclusi
  checksum e stato.
- Snapshot `prerestore` obbligatorio e verificato; Online Backup API per il restore; controllo
  integrità/schema successivo e recupero dal prerestore in caso di failure.
- Il test di rifiuto usa un sentinel e dimostra che un backup incompatibile non modifica il DB.

### PostgreSQL

- `pg_dump -Fc --schema=public` su staging privato; archivio custom verificato e pubblicato solo
  dopo sync e rename atomico.
- `pg_restore --list` controlla il TOC; una decompressione completa del payload rileva troncamenti
  prima della promozione.
- Un `prerestore` fresco viene confrontato col dump sorgente prima di toccare produzione. Il
  confronto include TOC completo e SHA-256 del DDL `schema-only`; sono neutralizzati soltanto i
  due token casuali esterni `\\restrict`/`\\unrestrict`, mentre il resto del DDL è hashato
  integralmente.
- Il preflight rileva differenze con identità TOC uguale, provate su definizione di view, tabella
  `UNLOGGED/LOGGED` e funzione dollar-quoted multilinea contenente righe simili a commenti.
- La promozione usa `--clean --if-exists --single-transaction --schema=public`: il singolo comando
  PostgreSQL committa tutto oppure annulla tutto. Gli schemi non `public` non sono ripristinati.
- Un fingerprint post-restore indipendente controlla colonne, vincoli, indici, sequence,
  funzioni/procedure, view/materialized view/rule, trigger, tipi, RLS policy e storia Flyway.
- Dopo kill/timeout viene attesa la quiescenza del rollback server prima di tentare recovery.
- `lock_timeout` limita `pg_restore`; il runner impone anche una deadline al processo. Il
  `statement_timeout` è applicato alle query di fingerprint, non a `pg_restore`.

### Concorrenza e operazioni distruttive

- Il restore prende esclusione su richieste REST e writer esterni all'HTTP anche quando PostgreSQL
  non serializza normalmente gli writer.
- Backup e restore usano lock con timeout; la sorgente viene staged sotto lock.
- Le tre operazioni applicative distruttive sono fail-closed: se il backup `preop` fallisce,
  l'operazione risponde `SAFETY_BACKUP_FAILED` e non procede.
- Flush del pool e invalidazione delle cache vengono tentati separatamente, così il secondo non
  viene saltato se il primo fallisce.

### Sicurezza API, filesystem e processi

- Token amministrativo sempre obbligatorio, minimo 32 byte, confronto constant-time; nessun
  bypass configurabile. Il token non viene scritto nei log.
- Rate limit per peer con finestra TTL e memoria bounded; la credenziale valida viene verificata
  prima del blocco e può sempre azzerare il bucket. Testato `10 errori → 429 → token valido 200`.
- Peer remoti diretti senza TLS ricevono 426. Tutte le risposte hanno `Cache-Control: no-store,
  private` e `Vary: X-Backup-Admin-Token`.
- Nomi, directory e file sono riconvalidati; traversal e leaf symlink sono rifiutati. POSIX usa
  `0700/0600`; Windows applica una DACL owner-only anche ai backup legacy all'avvio.
- Download da `FileChannel` con `NOFOLLOW_LINKS`; il successo viene auditato dopo il trasferimento
  allo stack HTTP e gli errori vengono auditati come failure.
- I tool PostgreSQL sono risolti a percorso assoluto; niente shell. L'ambiente `PG*` ereditato è
  rimosso e ricostruito; la password non è in argv ed è fornita solo ai processi che contattano il
  DB. Per host non-loopback è accettato soltanto `sslmode=verify-full` con CA attendibile.
- Output dei client PostgreSQL limitato e sanificato da CR/LF/escape prima dei log.

## 4. Finding avversariali corretti

| Finding riprodotto | Correzione | Prova |
|---|---|---|
| Writer esterno poteva attraversare il gate restore | writer gate sempre acquisito | test concorrenza dedicato |
| Fingerprint PostgreSQL incompleto e solo post-commit | DDL schema-only preflight + fingerprint ampliato | view, UNLOGGED e funzione multilinea |
| Dump troncato verificato troppo tardi | decompressione payload completa pre-promozione | sentinel DB invariato |
| Rate limiter poteva bloccare anche il token valido | confronto credenziale prima del rate limit; cache bounded | 10×401, 429, poi 200 |
| Snapshot pre-operazione best-effort | fail-closed sui tre endpoint distruttivi | contratto HTTP dedicato |
| Audit download anticipato | evento success solo dopo `transferTo` | code review e build |
| Proxy Vite non inoltrava `/backup` | route proxy aggiunta | build frontend |
| Restore SQLite verificava solo integrità, non schema atteso | confronto fingerprint completo | backup alterato + sentinel |
| Rotazione poteva eliminare tutti i file scaduti | preservato almeno il più recente per tag | code review |

## 5. Requisiti operativi obbligatori

1. Eseguire **una sola istanza applicativa** durante backup/restore. I gate sono JVM-locali e non
   costituiscono un lock distribuito.
2. Generare `BACKUP_ADMIN_TOKEN` con CSPRNG, almeno 256 bit effettivi; ruotarlo tramite restart e
   proteggere la sessione browser.
3. Usare TLS end-to-end verso Quarkus, oppure un reverse proxy locale fidato con backend non
   raggiungibile dalla rete. Non assumere che il solo header `X-Forwarded-Proto` sia attendibile.
4. Per PostgreSQL remoto configurare `backup.postgresql.sslmode=verify-full` e
   `backup.postgresql.sslrootcert` con la CA corretta.
5. Usare client PostgreSQL di major almeno pari al server e una directory tool non scrivibile da
   utenti non fidati.
6. Conservare copie cifrate, versionate e off-host; provare periodicamente il restore su ambiente
   isolato.
7. Eseguire il restore automatico con la stessa build/schema. Un backup precedente a una nuova
   migrazione viene rifiutato in sicurezza e richiede una procedura di migrazione isolata.

## 6. Limiti e rischio residuo

- Nessuna firma, HMAC, timestamp authority o storage WORM: non è una catena di custodia probatoria.
- Coordinamento JVM-local; un deployment multi-istanza richiede un lock distribuito non presente.
- Directory fsync è best-effort e dipende da OS/filesystem/storage; non è stata simulata una vera
  perdita di alimentazione.
- Non sono stati fault-iniettati tutti i punti di crash: in particolare power loss, kill in ogni
  istruzione, rollback SQLite realmente fallito e stato `INCONSISTENT` reale.
- Dopo un commit DB verificato, un raro errore di flush/cache è loggato; il DB resta ripristinato,
  ma può essere necessario riavviare l'istanza.
- `PGPASSWORD` resta per breve tempo nell'ambiente del processo figlio ed è potenzialmente leggibile
  da processi con lo stesso account/amministratore; usare un service account dedicato.
- La protezione segue il leaf con `NOFOLLOW_LINKS`; un attaccante locale già capace di manipolare
  gli ancestor della directory o i binari del servizio resta fuori dal threat model affidabile.
- Il token è condiviso e l'audit identifica peer/azione/esito, non una persona; non è attribution-grade.
- Il fallback browser usa un `Blob`: dump multi-gigabyte possono consumare molta RAM lato client.
- Il lint globale del frontend contiene errori preesistenti in componenti estranei al backup; il
  lint dei file backup modificati è invece pulito.

## 7. Evidenze finali

| Comando/prova | Esito |
|---|---:|
| Suite completa `test-sqlite` | 116 test, 0 failure, 0 errori, 18 skip di profilo |
| Suite completa `test-postgresql` reale | 116 test, 0 failure, 0 errori, 8 skip di profilo |
| `PostgresqlBackupServiceTest` mirato | 16 test, 0 failure, 0 errori |
| SQLite/auth/gate mirati | 12 test, 0 failure, 0 errori |
| Lint file frontend modificati | 0 errori, 0 warning |
| Build TypeScript/Vite | riuscita |
| `git diff --check` | pulito |
| SHA-256 `databases/large_data.db` | invariato rispetto al riferimento |

Ambiente PostgreSQL della prova: server 18.4, `pg_dump`/`pg_restore` major 18. I test reali hanno
esercitato restore riuscito, pool riutilizzabile, dump troncato, incompatibilità TOC/DDL, preflight
semantico, preservazione di schema ausiliario, sentinel no-touch, token/rate limit e concorrenza.

## 8. Conclusione

Nel perimetro operativo della sezione 5, il sistema può essere messo in esercizio con un livello di
fiducia alto. La formulazione corretta è **backup/restore operativo robusto e fail-closed**, non
“impossibile da corrompere” e non “evidenza forense autenticata”. I rischi residui sono espliciti e
non risultano blocker per l'uso previsto.
