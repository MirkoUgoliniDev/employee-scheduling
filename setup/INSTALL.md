# Installazione su Linux — guida operativa

Wizard di installazione di Employee Scheduling come servizio su una macchina
sempre accesa: Raspberry Pi, mini-PC o macchina virtuale.

Usa **soltanto la libreria standard di Python**: non c'è niente da installare
prima di poterlo lanciare, che su una macchina appena preparata è proprio il
momento in cui farlo è più scomodo.

---

## In breve

```bash
# 1. entra nel Raspberry
ssh pi@raspberrypi.local

# 2. scarica soltanto il piccolo installer Raspberry della Release
curl -fLO https://github.com/MirkoUgoliniDev/employee-scheduling/releases/latest/download/employee-scheduling-raspberry-installer.tar.gz
mkdir employee-scheduling-installer
tar -xzf employee-scheduling-raspberry-installer.tar.gz -C employee-scheduling-installer
cd employee-scheduling-installer

# 3. installa PostgreSQL e l'applicazione; il JAR viene scaricato automaticamente
sudo ./scripts/install-linux.sh --engine postgresql
```

Per un'installazione di test, gli stessi dati dimostrativi portabili possono
essere caricati sia su PostgreSQL sia su SQLite aggiungendo `--demo-data`:

```bash
sudo ./scripts/install-linux.sh --engine postgresql --demo-data
```

L'opzione crea sedi, operatori, specialisti, competenze e turni non assegnati,
ma non crea utenti, password o configurazioni SMTP. È idempotente e nelle
installazioni di produzione resta disattivata per impostazione predefinita.

L'archivio contiene soltanto gli script di installazione e disinstallazione. Lo
script scarica automaticamente da GitHub Releases il JAR compilato per il motore
selezionato. Non servono il repository sorgente, Windows, `scp`, Maven o Node.js
sul Raspberry.

Per usare il wizard grafico/testuale o un pacchetto compilato manualmente resta
disponibile la modalità avanzata:

```bash
sudo python3 setup/wizard.py --tui --jar ~/employee-scheduling-1.2.2-SNAPSHOT-runner.jar
```

> Il wizard manuale richiede l'intera cartella `setup/`, non il solo
> `wizard.py`. L'installazione consigliata usa invece `install-linux.sh`.

> ### `-Dquarkus.profile` non è opzionale
>
> Il motore dati (`quarkus.datasource.db-kind`) e le cartelle delle migrazioni
> Flyway sono fissati quando il jar viene **compilato**: nessuna variabile
> d'ambiente può cambiarli dopo. Le conseguenze sono concrete:
>
> - compilando per SQLite e installando con `--engine postgresql`, il servizio
>   parte e muore con *"Driver does not support the provided URL"*;
> - compilando **senza** profilo il guasto è peggiore, perché è muto: il profilo
>   di default ha `quarkus.flyway.active=false`, quindi le migrazioni **non
>   girano affatto**. Su un'installazione nuova le tabelle non vengono create e
>   l'applicazione si comporta in modo inspiegabile, senza un solo errore che
>   punti alla causa.
>
> Il wizard legge il motore cablato dentro il jar e **rifiuta prima di toccare
> la macchina** sia il motore sbagliato sia il pacchetto compilato senza profilo.
> Usa `-Dquarkus.profile=sqlite` se installerai con `--engine sqlite`.
>
> Attenzione a un dettaglio che inganna: **Quarkus legge anche il file `.env`
> in fase di compilazione**. Se nel progetto c'è un `.env` con
> `QUARKUS_PROFILE=…`, il profilo viene applicato anche senza passarlo a Maven —
> e su un clone pulito, dove quel file non c'è, lo stesso comando produce un jar
> diverso. Passalo sempre esplicitamente.

---

## Le modalità

| Comando | Quando serve |
|---|---|
| `sudo python3 setup/wizard.py --dry-run --jar …` | Mostra ogni comando che eseguirebbe **senza modificare niente**. Da qui conviene sempre partire. |
| `sudo python3 setup/wizard.py --tui --jar …` | Installazione da terminale. È la modalità predefinita quando non si passa `--web`. |
| `sudo python3 setup/wizard.py --web --jar …` | Interfaccia da browser, con i passi che avanzano in tempo reale. |

### La modalità web richiede un tunnel SSH

Il wizard ascolta **solo sulla macchina stessa**, sulla porta `8899`. Il terminale
stampa il comando esatto; dal tuo PC:

```bash
ssh -L 8899:localhost:8899 pi@raspberrypi.local
# poi apri http://localhost:8899
```

Non è una complicazione gratuita. Quella pagina esegue comandi **come root e non
ha password**: esposta sulla rete, chiunque — compreso chi è collegato al Wi-Fi
ospiti — potrebbe reinstallare o riconfigurare la macchina. Al Raspberry ci si
arriva già via SSH, quindi il tunnel non aggiunge un passaggio, lo sposta.

Nella pagina il form raccoglie motore dati, porta, percorso del pacchetto,
cartella dati e le tre voci SMTP principali (server, utente, password). Le
opzioni `--smtp-port` e `--smtp-from` esistono **solo** da riga di comando: dal
browser valgono i predefiniti, cioè porta 587 e mittente uguale all'utente SMTP.

Lanciando `--web` insieme a `--dry-run`, la pagina **non può** avviare
un'installazione vera: entrambi i pulsanti restano in simulazione.

---

## Prerequisiti

Molto pochi, ed è voluto:

- **Linux con systemd** — Raspberry Pi OS, Debian, Ubuntu (ramo `apt`) oppure
  Fedora, RHEL, Rocky, AlmaLinux (ramo `dnf`)
- **Python 3** — già presente su tutte le distribuzioni citate
- **Privilegi di root** (`sudo`)
- **Il pacchetto `.jar`**, compilato altrove

Java e PostgreSQL **non** sono prerequisiti: li installa il wizard.

Il ramo `dnf` è il meno collaudato dei due, e su Fedora e RHEL c'è SELinux attivo
di serie: la combinazione con `ProtectSystem=strict` potrebbe richiedere un
aggiustamento delle etichette.

### Perché il jar si compila sul PC e non qui

Compilare richiede Maven, Node.js 20 o superiore e parecchi minuti di CPU. Su
Debian bookworm — la base di Raspberry Pi OS — Node si ferma alla 18, che non
basta per questo frontend.

---

## Le opzioni

| Opzione | Predefinito | Cosa fa |
|---|---|---|
| `--jar PERCORSO` | — | Il pacchetto da installare. **Obbligatorio.** |
| `--engine postgresql\|sqlite` | `postgresql` | Motore dati. |
| `--port N` | `8080` | Porta dell'applicazione. |
| `--data-dir PERCORSO` | `/var/lib/employee-scheduling` | Backup, impostazioni e — solo con SQLite — il database. Deve essere assoluto e senza spazi. |
| `--web` | — | Interfaccia da browser. |
| `--tui` | — | Interfaccia da terminale (predefinita). |
| `--web-port N` | `8899` | Porta del wizard, non dell'applicazione. |
| `--dry-run` | — | Simula soltanto. |
| `--yes`, `-y` | — | Non chiede conferma, per l'automazione. |
| `--smtp-host`, `--smtp-user`, `--smtp-pass` | vuoti | Invio email. |
| `--smtp-port` | `587` | Solo da riga di comando. |
| `--smtp-from` | utente SMTP | Solo da riga di comando. |

Senza SMTP i codici di registrazione compaiono soltanto nel registro del
servizio (`journalctl -u employee-scheduling`).

### Quale motore scegliere

**SQLite** è un file solo, non ha servizi da mantenere e su un Raspberry consuma
molto meno. Va bene per una struttura con poche persone che non modificano gli
stessi turni nello stesso momento.

**PostgreSQL** serve quando più persone lavorano insieme sulla pianificazione, ed
è l'unico dei due che regge davvero l'accesso contemporaneo. È anche l'unico su
cui funziona la registrazione con codice via email.

---

## Cosa fa il wizard, passo per passo

1. **Controllo del sistema** — privilegi, gestore pacchetti, systemd, spazio
   libero, porta libera. Qui vengono validati anche **il pacchetto e la cartella
   dati**: se qualcosa non va, il wizard si ferma *prima* di modificare
   qualunque cosa, non a metà strada.
2. **Java** — installa la 21, oppure la 17 dove la 21 non c'è. L'applicazione è
   compilata per la 17, quindi la 17 basta. Saltato solo se è già presente Java
   **17 o superiore**: con una versione più vecchia (8 o 11) il passo installa
   comunque, senza rimuovere quella esistente.
3. **Database** — solo con `--engine postgresql`: installa il servizio, crea
   ruolo e database, e **prova davvero a connettersi** con le credenziali. Con
   SQLite viene saltato.
4. **Utente e cartelle** — utente di sistema senza shell, con la cartella dati
   come home (non ne viene creata una propria). Crea la cartella dati e la
   sottocartella `backups/`, entrambe con permessi `750`.
5. **Applicazione** — copia il jar in `/opt/employee-scheduling`, di proprietà
   di root e in sola lettura per il servizio.
6. **Configurazione** — genera chiave di sessione e token di backup, scrive
   `/etc/employee-scheduling.env` con permessi `640 root:employee-scheduling`.
7. **Servizio** — unità systemd con avvio automatico, riavvio in caso di errore
   e contenimento (`ProtectSystem=strict`, `ReadWritePaths`, `PrivateTmp`).
8. **Verifica** — attende fino a tre minuti che l'applicazione risponda. Se il
   servizio **muore**, il passo fallisce e stampa le ultime righe del registro.
   Se invece è ancora vivo ma non ha finito di avviarsi, il passo viene
   segnalato come *saltato*, non come errore: su hardware lento il primo avvio
   può richiedere di più, e non c'è niente da correggere.

Al primo fallimento il wizard si ferma: i passi dipendono l'uno dall'altro, e
proseguire lascerebbe la macchina in uno stato peggiore di quello di partenza.

---

## Dopo l'installazione

```bash
systemctl status employee-scheduling      # stato
journalctl -u employee-scheduling -f      # registro in tempo reale
systemctl restart employee-scheduling     # riavvio
```

L'applicazione risponde su `http://<indirizzo-del-server>:8080`, o sulla porta
scelta con `--port`. **Il primo account che si registra diventa amministratore.**

### Dove stanno davvero i dati

Dipende dal motore, ed è la differenza che conta di più se un giorno devi
spostare o salvare l'installazione.

| | SQLite | PostgreSQL |
|---|---|---|
| Database | `/var/lib/employee-scheduling/large_data.db` | nel cluster PostgreSQL (`/var/lib/postgresql/…`) |
| Backup | `/var/lib/employee-scheduling/backups/` | idem (file `.dump`) |
| Impostazioni backup | `/var/lib/employee-scheduling/` | idem |
| Registro del servizio | journald | journald |

Con PostgreSQL, **copiare la cartella dati non porta via il database**: si porta
via solo i backup. Il registro non viene scritto su file: si legge con
`journalctl -u employee-scheduling`.

Se un servizio va in ciclo di riavvii, `systemctl status` può mostrarlo comunque
come attivo per qualche istante: l'unità ha `Restart=on-failure` con dieci
secondi di attesa. Il journal è l'unico posto dove il ciclo si vede.

### Il token dei backup

L'amministrazione dei backup dall'interfaccia richiede un token, che il wizard
genera ma **non mostra**. Si legge così:

```bash
sudo grep BACKUP_ADMIN_TOKEN /etc/employee-scheduling.env
```

Da un indirizzo diverso da `localhost` le chiamate di backup rispondono **426**
finché il traffico non passa da HTTPS. Su un server headless in rete locale la
via più semplice è lo stesso tunnel SSH che si usa per il wizard: dal punto di
vista dell'applicazione la richiesta arriva da `localhost`, quindi passa.

```bash
ssh -L 8080:localhost:8080 pi@raspberrypi.local
# poi apri http://localhost:8080 e la sezione backup funziona
```

Le alternative sono un reverse proxy con certificato davanti all'applicazione,
oppure — solo su una rete di cui ti fidi davvero — disattivare il requisito con
`backup.admin.require-tls-for-remote=false`.

Con PostgreSQL i backup richiedono `pg_dump` e `pg_restore`. Il wizard avvisa se
mancano, e in quel caso l'applicazione disattiva da sola backup e ripristino.

---

## Aggiornare

Si rilancia lo stesso wizard con il pacchetto nuovo:

```bash
sudo python3 setup/wizard.py --tui --yes --jar ~/employee-scheduling-1.3.0-runner.jar
```

Il wizard riconosce che la porta è occupata dal **proprio** servizio e procede
come aggiornamento. Non serve ripetere le opzioni: **motore, porta e cartella
dati vengono ripresi dall'installazione esistente** e il wizard lo dichiara
prima di partire. Indicarli esplicitamente li cambia.

Vengono riusate anche **la chiave di sessione e la password del database**:
nessuno viene disconnesso e nulla si disallinea.

Il pacchetto precedente viene rimosso da `/opt/employee-scheduling` se ha un
nome diverso da quello nuovo, per non lasciare due jar in cartella.

Le migrazioni dello schema si applicano da sole al primo avvio. Come per ogni
cambio di schema, conviene avere un backup recente.

---

## Se qualcosa non va

Il wizard scrive tutto in `/var/log/employee-scheduling-setup.log`: ogni comando
eseguito e il suo esito. In modalità web è l'unica traccia che resta dopo aver
chiuso il browser.

**"Un'altra installazione è già in corso"** — un wizard è rimasto aperto, magari
in una sessione SSH caduta. Il lock è `/var/run/employee-scheduling-setup.lock`
e contiene il PID: se quel processo non esiste più, il wizard lo recupera da
solo al rilancio.

**"Il pacchetto è compilato per X ma stai installando con motore Y"** — ricompila
il jar con `-Dquarkus.profile=Y`. Nulla è stato modificato sulla macchina.

**Il servizio non parte** — la diagnosi è quasi sempre nelle ultime righe:
```bash
journalctl -u employee-scheduling -n 60 --no-pager
```

**Connessione al database rifiutata** — manca la riga per le connessioni locali
in `pg_hba.conf`:
```
host all all 127.0.0.1/32 scram-sha-256
```

**La porta 8080 è occupata da un altro programma** — installa su un'altra porta
con `--port 8090`. Sotto la 1024 non si può andare: il servizio gira senza
privilegi e non potrebbe occuparla.

**"Un'installazione di pacchetti precedente è rimasta a metà"** — è successo che
un `apt` sia stato interrotto. Non si risolve rilanciando il wizard:
```bash
sudo dpkg --configure -a
```

**"PostgreSQL risulta avviato ma il server non risponde"** — su Debian
`postgresql.service` è solo un contenitore: il server vero è il cluster.
```bash
pg_lsclusters                       # vedi lo stato reale
sudo pg_ctlcluster 15 main start    # avvialo
```

**Cartella dati su un disco esterno** — il wizard rifiuta di installare se il
disco non risulta montato, perché altrimenti i dati finirebbero sulla scheda e
sparirebbero dietro il mount al primo riavvio. L'unità systemd generata attende
il montaggio prima di avviare il servizio.

---

## Disinstallare

```bash
sudo ./scripts/uninstall-linux.sh            # rimuove servizio e applicazione, TIENE i dati
sudo ./scripts/uninstall-linux.sh --purge    # rimuove anche dati, backup e database
```

Senza `--purge` restano la cartella dati, l'utente di servizio e il file di
configurazione — quest'ultimo perché contiene ancora la password del database in
uso. Reinstallando, l'applicazione ritrova tutto com'era.

`--purge` chiede di scrivere `DELETE` per esteso: non è reversibile. Aggiungendo
`--yes` non chiede nulla.

---

## Sicurezza

Il traffico è in **HTTP, non cifrato**: va bene su una rete locale fidata. Se
l'applicazione deve essere raggiungibile da fuori, mettila dietro un reverse
proxy con certificato — nginx o Caddy — e non esporre la porta direttamente.

Il file `/etc/employee-scheduling.env` contiene la password del database, la
chiave di sessione e il token dei backup. È `640 root:employee-scheduling` e non
va copiato altrove né messo sotto controllo di versione.

---

## Installazione consigliata: script shell

Per un'installazione rapida o automatizzata resta disponibile
`scripts/install-linux.sh`, che fa le stesse cose in una riga sola:

```bash
sudo ./scripts/install-linux.sh --engine postgresql
```

Senza `--jar` scarica automaticamente l'ultima Release compilata per il motore
scelto. Accetta anche `--from-source` (compila sul posto: lento su un Raspberry
e richiede Node 20+) e `--no-service` (non registra il servizio systemd).

Il wizard resta disponibile per installazioni manuali con un JAR locale.
