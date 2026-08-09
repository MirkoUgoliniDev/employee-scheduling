# Pacchetto Windows (MSI) — documento consolidato

**Stato: aggiornato al 6 agosto 2026.** Questo file è la fonte di verità sul confezionamento
Windows: se contraddice altri documenti, vale questo. Chi lo legge dall'inizio alla fine sa
produrre, verificare e consegnare un installer funzionante senza ripercorrere gli errori già
fatti — ognuno dei quali è documentato nella sezione 7 con **sintomo osservato**, causa reale e
rimedio applicato.

---

## 1. Cosa si produce, e per chi

Un solo file `dist\EmployeeScheduling-<version>.msi` (~125 MB) che vale per **tutte** le
installazioni. Chi lo riceve non ha bisogno di nulla: niente Java, niente repository, niente
strumenti di sviluppo. Il JRE è dentro il pacchetto.

Ciò che cambia da un'installazione all'altra — porta, SMTP, modalità di registrazione — **non è
cablato nel pacchetto**: si scrive in un file di testo accanto ai dati dell'utente (sezione 5).
Non si ricompila mai per cambiare una configurazione.

| Artefatto | Cos'è | Quando serve |
|---|---|---|
| `dist\EmployeeScheduling-<version>.msi` | Installer con menu Start e disinstallazione | È **questo** che si consegna |
| `dist\EmployeeScheduling-<version>-windows-x64.zip` | La stessa app, portabile, da scompattare | Chi non può o non vuole installare |
| `dist\EmployeeScheduling\` | L'app-image scompattata (contenuto dello zip) | Prove rapide senza installare |

---

## 2. Prerequisiti sulla macchina che compila

Solo su chi **produce** il pacchetto, mai su chi lo installa.

| Strumento | Versione | Note |
|---|---|---|
| JDK Temurin | **21** | Serve `jpackage`, incluso nel JDK. Percorso tipico: `C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot\bin\jpackage.exe` |
| Maven | 3.9.x | Tipico: `C:\Program Files\Maven\apache-maven-3.9.13\bin\mvn.cmd`. Può non essere nel PATH del processo `powershell -File`: lo script ripiega su `MAVEN_HOME` |
| Node.js | 24 | Per la build del frontend |
| WiX Toolset | **3.14** | Necessario **solo** per `--type msi`. `install-windows.ps1` lo scarica da solo in `C:\tools\wix314` (~39 MB). Verifica: `C:\tools\wix314\candle.exe` deve esistere |

> `jpackage --type msi` richiede WiX **3.x**: con WiX 4/5 fallisce. Non aggiornarlo "per igiene".

---

## 3. Procedura rapida — il wizard

```powershell
cd C:\Lavori\VSCode\employee-scheduling
powershell -ExecutionPolicy Bypass -File .\scripts\install-windows.ps1
```

Alla domanda finale sul packaging rispondere **`2` = installer MSI**. Il wizard esegue da sé la
catena della sezione 4 e scrive `dist\EmployeeScheduling-<version>.msi`.

### 3.1 Modalità non interattiva (task/pulsante)

Per automazioni o pulsanti VS Code:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-windows.ps1 -Package msi
```

In questa modalità lo script **non fa domande**: usa SQLite, porta 8080 e SMTP mock,
ricompila frontend+backend e genera l'MSI.

In VS Code c'è il task **Packaging: MSI (rebuild)**; se è configurato il pulsante nella
status bar (estensione Status Bar Commands), lo trovi come **"MSI"**.

Il wizard è **interattivo** (`Read-Host`) solo senza `-Package`. Con `-Package` è adatto
ad automazioni o task.

---

## 4. Procedura manuale, passo per passo

I quattro passi vanno eseguiti **in quest'ordine**. Saltarne uno produce un pacchetto che
sembra a posto e non lo è.

### 4.1 Frontend

```powershell
cd frontend
npm install
npm run build      # tsc -b && vite build
cd ..
```

L'output finisce in `src\main\resources\META-INF\resources\` (vedi `vite.config.ts`,
`build.outDir`), quindi **deve girare prima** del passo 4.2: il jar incorpora quella cartella
così com'è sul disco. Un jar costruito prima della build del frontend contiene l'interfaccia
**precedente**, e il difetto che credi di aver corretto è ancora lì.

### 4.2 Uber-jar

```powershell
mvn -B -ntp package -DskipTests "-Dquarkus.package.jar.type=uber-jar" "-Dquarkus.profile=sqlite"
```

Due dettagli non negoziabili:

- **`uber-jar`**: il formato predefinito (fast-jar) **non** produce `*-runner.jar`, e jpackage
  ha bisogno di un jar unico autoportante.
- **`-Dquarkus.profile=sqlite` in fase di build**: `quarkus.flyway.locations` è una proprietà
  **build-time**. Senza questo, il pacchetto si porta dietro le migrazioni di **entrambi** i
  motori e fallisce all'avvio con migrazioni duplicate. Per un pacchetto PostgreSQL:
  `-Dquarkus.profile=postgresql`.

Risultato: `target\employee-scheduling-<version>-SNAPSHOT-runner.jar` (~77 MB).

### 4.3 Cartella di staging

```powershell
$staging = "target\jpackage-input"
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $staging | Out-Null
Copy-Item target\employee-scheduling-<version>-SNAPSHOT-runner.jar $staging
Copy-Item uninstall-windows.ps1 $staging
Copy-Item uninstall.cmd $staging
```

jpackage copia **l'intera cartella `--input`** dentro l'applicazione: ci deve stare solo ciò
che si vuole distribuire. Puntare `--input` su `target\` significa spedire l'intera cartella di
build. I due file di disinstallazione viaggiano qui, e finiranno in `<install>\app\`.

### 4.4 jpackage

```powershell
$env:Path = "C:\tools\wix314;" + $env:Path

# chiave casuale da 64 caratteri esadecimali: vedi 7.1, sotto i 16 l'app non parte
$bytes = New-Object byte[] 32
$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
$rng.GetBytes($bytes)
$rng.Dispose()
$key = -join ($bytes | ForEach-Object { $_.ToString('x2') })

# token backup non vuoto: vedi 7.2 e 7.9-ter
$backupToken = -join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) })

# token di backup: mai vuoto (vedi 7.2 e 7.9-ter), altrimenti l'app non parte
# o la sezione Backup risponde 503 in silenzio.
$backupToken = -join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) })

# Immagini MSI (WiX UI): se presenti, sovrascrivono la UI standard.
# Dimensioni reali dei controlli (tabella Control dell'MSI prodotto da jpackage):
# banner 370x44 px, sfondo dialog 370x234 px (dialoghi 370x270; la fascia bassa
# ospita i pulsanti). Il testo è disegnato SOPRA le immagini a posizioni fisse:
#   - dialog: titolo+descrizione in x=135..355, y=20..140 → zona da tenere
#     scura/pulita; logo o grafica a sinistra (x<135) o sotto (y>140);
#   - banner: titolo x=15..215 y=6..21, descrizione x=25..305 y=23..38 → liberi
#     solo ~65px a destra (x>305) e le fasce da 6px sopra/sotto.
$resDir = "target\jpackage-resources"
Remove-Item $resDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $resDir | Out-Null
if (Test-Path "assets\app\installer\dialog.bmp") {
  ConvertTo-WixBitmap -SourcePath "assets\app\installer\dialog.bmp" `
    -DestPath "$resDir\WixUIDialog.bmp" -Width 370 -Height 234
}
if (Test-Path "assets\app\installer\banner.bmp") {
  ConvertTo-WixBitmap -SourcePath "assets\app\installer\banner.bmp" `
    -DestPath "$resDir\WixUIBanner.bmp" -Width 370 -Height 44
}
# Override main.wxs: le immagini vanno dichiarate come <WixVariable> (non come
# <?define ... ?>: main.wxs consulta da overrides.wxi solo JpProductLanguage e
# JpInstallerVersion). Attenzione alla XSD di WiX v3 (verificata con wix314):
#   - sotto <Wix> non sono ammesse (CNDL0005);
#   - in un <Fragment> in coda compilano (dopo Product, CNDL0107 altrimenti) ma light
#     SCARTA le sezioni non raggiungibili dal Product: i bitmap di default restano;
#   - dentro <Product> sono valide (posizione fissata dalla sequenza di Product:
#     subito dopo <Package>, prima di <Media>) e funzionano.
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = (Get-Command java.exe -ErrorAction SilentlyContinue).Source | Split-Path -Parent | Split-Path -Parent }
$jmod = Join-Path $javaHome "jmods\jdk.jpackage.jmod"
$jmodExe = Join-Path $javaHome "bin\jmod.exe"
if (Test-Path $jmod -and (Test-Path $jmodExe)) {
  $extractDir = Join-Path $env:TEMP "jpackage-mainwxs-extract"
  Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue
  & $jmodExe extract --dir $extractDir $jmod | Out-Null
  $src = Join-Path $extractDir "classes\jdk\jpackage\internal\resources\main.wxs"
  $content = Get-Content $src -Raw
  $resDirAbs = (Resolve-Path $resDir).Path
  $wixVars = @(
    "    <WixVariable Id=\"WixUIDialogBmp\" Value=\"$resDirAbs\WixUIDialog.bmp\" />",
    "    <WixVariable Id=\"WixUIBannerBmp\" Value=\"$resDirAbs\WixUIBanner.bmp\" />"
  )
  $patched = $content.Replace("    <Media Id=\"1\"", ($wixVars -join "`r`n") + "`r`n    <Media Id=\"1\"")
  Set-Content "$resDir\main.wxs" -Value $patched -Encoding UTF8
}

$jopts = @(
  '--java-options','-Dapp.data.dir=auto',
  '--java-options','-Dquarkus.http.port=8080',
  '--java-options','-Dquarkus.mailer.mock=true',
  '--java-options',"-Dbackup.admin-token=$backupToken",
  '--java-options',"-Dquarkus.http.auth.session.encryption-key=$key",
  '--java-options','-Dquarkus.log.file.enable=true',
  '--java-options','-Dquarkus.log.file.level=INFO',
  '--java-options','-Dapp.open-browser-on-start=true',
  '--resource-dir', $resDir
)

jpackage --type msi --name "EmployeeScheduling" --app-version $appVersion `
  --input target\jpackage-input `
  --main-jar employee-scheduling-<version>-SNAPSHOT-runner.jar `
  --dest dist --win-menu --win-dir-chooser --win-shortcut --win-shortcut-prompt @jopts
```

> **Testo del benvenuto a sinistra**: lo fa automaticamente il wizard
> (`Invoke-MsiTextPositionPatch`, dopo jpackage). In questa procedura manuale, eseguire
> a mano lo script di 7.14: `cscript //nologo msi-move-text.js dist\EmployeeScheduling-<version>.msi`.

> **La versione viene dal `pom.xml`**, non si scrive a mano: `Get-AppVersion` la legge, toglie
> `-SNAPSHOT` e la porta a tre componenti (`1.2-SNAPSHOT` → `1.2.0`), perché jpackage le vuole
> numeriche. È la stessa versione che l'applicazione installata confronta con l'ultima
> pubblicata (sezione 9.1): due numeri scollegati renderebbero l'avviso falso in un senso o
> nell'altro.

Per l'app-image (cartella portabile) è identico con `--type app-image` e **senza** `--win-menu`
e `--win-dir-chooser`.

---

## 5. Configurazione: chi vince su chi

Questa tabella spiega il 90% dei comportamenti sorprendenti. Ordinale più alto = comanda.

| Ordinale | Sorgente | Dove | Chi la scrive |
|---:|---|---|---|
| 450 | `AppUserConfigSource` | `%LOCALAPPDATA%\EmployeeScheduling\config.properties` | **Chi installa**, a mano |
| 400 | System properties | `--java-options` nel `.cfg` di jpackage | Chi confeziona |
| 320 | `AppDataDirConfigSource` | calcolata all'avvio | Il codice |
| 300 | Variabili d'ambiente | ambiente del processo | — |
| 295 | File `.env` | cartella di lavoro | Il wizard (solo dev mode) |
| 250 | `application.properties` | dentro il jar | Il repository |

**Il file dell'utente sta sopra le system properties di proposito.** Le opzioni cablate nel
pacchetto sono *impostazioni predefinite*: chi installa deve poter correggere una porta 8080
già occupata da un altro programma senza dipendere da chi ha prodotto l'MSI. Verificato dal
vivo: pacchetto con `-Dquarkus.http.port=8099`, file con `quarkus.http.port=8123` →
l'applicazione risponde sulla **8123**.

Unica eccezione: **`app.data.dir` viene ignorata** se scritta nel file dell'utente. Quando quel
file viene letto la cartella dati è già stata risolta; accettarla darebbe una configurazione che
dice una cosa e un'applicazione che ne fa un'altra.

Il file `config.properties` viene **creato al primo avvio già commentato e spiegato**
(`AppUserConfigSource.writeTemplate`): porta, SMTP, modalità di registrazione, chiave di
sessione, token di backup, livello del log. Chi installa toglie il `#`, salva e riavvia.

### Perché non `quarkus.config.locations`

È la strada che sembra ovvia ed **è un vicolo cieco**: in Quarkus 3.37 quella proprietà non
accetta URI `file:///` e il valore viene cercato come nome di classe
(`ClassNotFoundException`). La configurazione va passata con `-D` singole, che jpackage scrive
nel `.cfg`. Ogni documento che suggerisce `-Dquarkus.config.locations=...\.env` è obsoleto.

---

## 6. Dove stanno i dati

```
%LOCALAPPDATA%\EmployeeScheduling\
├── large_data.db            database SQLite
├── large_data.db-shm/-wal   file di appoggio SQLite (WAL)
├── backups\                 backup automatici
├── app.log (+ app.log.N)    log ruotato
├── config.properties        configurazione modificabile (sezione 5)
└── app.lock                 lock di istanza singola
```

**Fuori dalla cartella di installazione**, e non è un dettaglio estetico: è la correzione di
tre guasti reali (7.3, 7.4, 7.5). Conseguenze utili:

- aggiornamenti e disinstallazione non toccano il database;
- l'installazione può stare in `C:\Program Files` senza problemi di permessi;
- per spostare un'installazione su un altro PC basta installare l'MSI e copiare quella cartella.

Il percorso non può essere scritto nel `.cfg`: **jpackage espande `$APPDIR` ma non le variabili
d'ambiente**. Per questo il pacchetto passa `-Dapp.data.dir=auto` e la risoluzione avviene a
runtime in `AppDataDirectory`: `%LOCALAPPDATA%\EmployeeScheduling` su Windows,
`~/.employee-scheduling` altrove.

> **`$APPDIR` risolve a `<install>\app`, non alla radice dell'installazione.** Costava mezz'ora
> di ricerca del database "sparito" ogni volta. Oggi non serve più a nessuno, ma se qualcuno
> reintroduce percorsi basati su `$APPDIR`, deve saperlo.

---

## 7. Trappole incontrate, e come sono state chiuse

Ognuna è stata osservata su una macchina vera, non dedotta.

### 7.1 Chiave di sessione corta → l'applicazione risponde 500 a ogni accesso

**Sintomo**: l'interfaccia mostra *"Server non raggiungibile. Riprova fra qualche istante."*;
`GET /auth/me` e `POST /j_security_check` rispondono **500**; nel log:
`RuntimeException: Shared keys for persistent logins must be more than 16 characters long`.

**Causa**: `PersistentLoginManager` rifiuta chiavi sotto i 16 caratteri. Essendo
`FormAuthenticationMechanism` un synthetic bean, l'eccezione nel costruttore diventa
`CreationException` e **ogni** richiesta autenticata fallisce.

**Rimedio**: si passa direttamente `-Dquarkus.http.auth.session.encryption-key=<64 esadecimali>`.
Non ci si affida più a `-DAUTH_SESSION_KEY`, che è solo il valore di default dell'espressione in
`application.properties` e aveva un percorso di risoluzione più fragile.

**Da controllare sempre** (sezione 8): in un'installazione era finita la chiave
`0123456789abcdef0123456789abcdef`. Prevedibile: chi la conosce può **forgiare un cookie di
sessione valido per qualunque utente**, perché il cookie cifra solo scadenza e nome utente.

### 7.2 `backup.admin-token` vuoto → Quarkus non parte affatto

**Sintomo**: all'avvio (e in `mvn test` dalla copia di lavoro)
`ConfigurationException: Failed to load config value of type class java.lang.String for:
backup.admin-token`, con `SRCFG00040: ... defined as the empty String ("") which the following
Converter considered to be null`.

**Causa**: `application.properties` dichiara `backup.admin-token=${BACKUP_ADMIN_TOKEN:}` e il
wizard scriveva quella variabile nel `.env` **solo** nel ramo PostgreSQL.

**Rimedio**: il wizard la scrive su entrambi i motori. Se si lancia la suite a mano da una copia
di lavoro con un `.env` vecchio:

```powershell
$env:BACKUP_ADMIN_TOKEN='qualsiasi-valore-non-vuoto'
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
```

### 7.3 La disinstallazione non riesce a rimuovere `app.log`

**Sintomo**: finestra *"Another application has exclusive access to the file
C:\...\app\data\app.log. Please shut down all other applications, then click Retry."*

**Causa**: il log stava dentro l'installazione e l'applicazione era ancora in esecuzione.

**Rimedio**: dati fuori dall'installazione (sezione 6). In più `uninstall-windows.ps1` chiude
l'applicazione **prima** di chiamare `msiexec`.

### 7.4 La disinstallazione fallisce con `GetLastError: 5`

**Sintomo**: *"Error getting file security: C:\...\app\data\backups\ GetLastError: 5"*.

**Causa** — è la più insidiosa di tutte: `BackupService.restrictPermissions` sostituiva
**l'intera ACL** della cartella backup con una sola voce (il proprietario), disattivando
l'ereditarietà. Così venivano rimossi **SYSTEM e Administrators**. Il disinstallatore MSI gira
proprio come SYSTEM: non riusciva nemmeno a leggere i permessi. Da notare che l'irrigidimento
*funzionava*: era il suo successo a rompere la disinstallazione.

**Rimedio**: `restrictPermissions` non riscrive più la DACL dentro le cartelle già private
dell'utente (`%LOCALAPPDATA%`, home) — dove sarebbe inutile — e non propaga più errori (prima
faceva fallire il backup automatico schedulato a ogni giro, in silenzio salvo il log).

**Riparazione di un'installazione già rovinata** (funziona senza privilegi di amministratore se
si è proprietari della cartella; i nomi dei gruppi sono localizzati, quindi si usano i SID):

```powershell
cmd /c "icacls `"C:\percorso\app\data`" /inheritance:e /grant *S-1-5-18:(OI)(CI)F /grant *S-1-5-32-544:(OI)(CI)F /T /C"
```

### 7.5 La disinstallazione porta via il database

**Causa**: i dati stavano in `<install>\app\data`.

**Rimedio**: sezione 6. Prima di disinstallare una versione **vecchia** (precedente al 5 agosto
2026), salvare a mano `<install>\app\data\large_data.db`.

### 7.6 Il file `.ps1` non parte con un doppio clic

**Sintomo**: Windows apre *"Selezionare un'app per aprire questo file .ps1"* e propone Blocco
note.

**Causa**: è il comportamento standard di Windows, i `.ps1` non sono eseguibili dal doppio clic.

**Rimedio**: `uninstall.cmd`, distribuito accanto allo script, che chiama PowerShell con
`-ExecutionPolicy Bypass` e inoltra gli argomenti.

### 7.7 Due processi `EmployeeScheduling.exe`

**Non è un difetto**: uno è il lanciatore nativo di jpackage, l'altro la JVM. Per chiudere
davvero l'applicazione servono entrambi:

```powershell
Get-Process -Name EmployeeScheduling -ErrorAction SilentlyContinue | Stop-Process -Force
```

Alternativa UI (consigliata): menu utente → **Chiudi applicazione**.

### 7.8 Una seconda istanza non parte, anche con dati e porta diversi

**Sintomo**: in fase di prova l'applicazione non risponde e nel log compare
*"Employee Scheduling è già in esecuzione"*.

**Causa**: `SingleInstanceGuard` usa un lock in `%LOCALAPPDATA%\EmployeeScheduling\app.lock`,
**indipendente** dalla cartella dati e dalla porta. Corretto per un'app desktop, fastidioso per
i test.

**Rimedio in prova**: `-Dapp.single-instance-lock=false`.

**Comportamento attuale**: la seconda istanza apre il browser sulla prima e si chiude subito.

### 7.9 Rigenerando l'app-image, l'exe resta bloccato

**Sintomo**: `Access to the path 'EmployeeScheduling.exe' is denied`, e subito dopo
`Error: Application destination directory ... already exists`. Se lo zip viene creato lo stesso,
esce **corrotto** (pochi centinaia di KB invece di 125 MB).

**Causa**: una finestra di Esplora risorse aperta su quella cartella, o l'antivirus che scansiona
l'eseguibile appena creato.

**Rimedio**: costruire l'app-image in `%TEMP%` e da lì creare lo zip, poi sostituire la cartella
in `dist` quando si libera. **Controllare sempre la dimensione dello zip** prima di consegnarlo.

### 7.9-bis `jpackage --type app-image` rifiuta di sovrascrivere

**Sintomo**: `Error: Application destination directory ...\dist\EmployeeScheduling already exists`,
exit 1, alla **seconda** generazione.

**Causa**: è il comportamento di jpackage, non un blocco del filesystem. Verificato che
`--type msi` **non** ha questo problema: riscrive sia l'`.msi` sia la cartella app-image
residua senza lamentarsi.

**Rimedio**: rimuovere `dist\EmployeeScheduling` prima di generare l'app-image (lo fa il
wizard). Se la rimozione fallisce, vale 7.9.

### 7.9-ter Token di backup assente nel pacchetto → sezione Backup morta

**Sintomo**: nell'interfaccia la pagina Backup non funziona; l'API `/backup` risponde **503
`BACKUP_ADMIN_TOKEN_NOT_CONFIGURED`**. I backup automatici però continuano a comparire nella
cartella: il guasto è silenzioso finché qualcuno non apre quella pagina.

**Causa**: `-Dbackup.admin-token` veniva passato a jpackage **solo** nel ramo PostgreSQL. Il
token è iniettato come `Optional<String>`, quindi l'applicazione parte lo stesso — a
differenza dei test, che lo iniettano come `String` e falliscono all'avvio (7.2).

**Rimedio**: il token viene passato su entrambi i motori.

### 7.9-quater SMTP raccolto e poi buttato via

**Sintomo**: pacchetto PostgreSQL, registrazione con verifica via email: il codice OTP non
arriva mai, resta scritto in `app.log`.

**Causa**: il wizard chiedeva i parametri SMTP ma il pacchetto veniva costruito comunque con
`-Dquarkus.mailer.mock=true`.

**Rimedio**: se è stato configurato un SMTP vero, le cinque opzioni `quarkus.mailer.*` finiscono
nel pacchetto con `mock=false`.

### 7.10 Reinstallare la stessa versione sopra sé stessa

`--app-version` ora viene dal `pom.xml`, ma finché la versione non cambia resta la stessa a ogni
ricostruzione. Installare l'MSI sopra un'installazione con lo stesso numero non è affidabile:
**disinstallare prima**. Ora è indolore, i dati non stanno lì.

### 7.11 Un'installazione nuova sembra rotta (interfaccia vuota o non tradotta)

Due cause **lato browser**, non del pacchetto. Si manifestano su chi ha usato a lungo
`localhost:8080` per lo sviluppo, cioè proprio su chi collauda:

- **Struttura fantasma**: `localStorage` conserva la struttura selezionata; se il suo id non
  esiste nel database nuovo, tutte le liste risultano vuote **senza alcun errore** (il backend
  risponde 200 con elenchi vuoti). Corretto dal 5 agosto 2026: la Navbar valida la selezione
  contro l'elenco reale.
- **Interfaccia sempre in italiano, selettore lingua inerte**: la cache delle traduzioni satura
  la quota di `localStorage` e la scrittura fallisce. Corretto. Sblocco immediato su
  un'installazione vecchia, dalla console del browser:
   ```js
   Object.keys(localStorage).filter(k => k.startsWith('i18n_cache')).forEach(k => localStorage.removeItem(k)); location.reload()
   ```

### 7.12 `jpackage --type msi` fallisce con exit 1 ("WiX installato?")

jpackage **nasconde l'errore di candle/light** (il wizard lo incapsula con `Invoke-Native`):
rilanciare jpackage a mano, senza sopprimere l'output, per vedere l'errore vero.

Causa reale incontrata (6 agosto 2026): `<WixVariable>` posizionato male in `main.wxs`.
Regole XSD di WiX v3 (wix314, verificate empiricamente con candle):

| Posizione di `<WixVariable>` | Esito |
|---|---|
| Direttamente sotto `<Wix>` (prima di `<Product>`) | **CNDL0005** "unexpected child element WixVariable" |
| In `<Fragment>` prima di `<Product>` | **CNDL0107** (la sequenza del `<Wix>` vuole Product prima dei Fragment) |
| In `<Fragment>` in coda, dopo `</Product>` | Compila, ma light **scarta** la sezione non raggiungibile: le WixVariable spariscono |
| **Dentro `<Product>`**, subito dopo `<Package>`, prima di `<Media>` | **Funziona** (WixVariable è figlio valido di Product; la sezione Product è l'entry point di light e viene sempre linkata) |

Il wizard fa già la quarta strada: `New-WixImageOverrideResource` estrae `main.wxs` da
`jdk.jpackage.jmod` (`jmod extract`) e inietta le `<WixVariable>` con ancora
`    <Media Id="1"` (sostituzione letterale `.Replace()`, non regex).

Quirk PowerShell 5.1: i define di candle tipo `-dJpAppVersion=1.2.0` vengono **spezzati**
(il valore diventa `.2.0` e candle cerca un file con quel nome) → quotare sempre:
`"-dJpAppVersion=1.2.0"`.

### 7.13 Le immagini custom non entrano nell'MSI (restano quelle di default)

Sintomo: l'MSI contiene i bitmap di default di WixUIExtension, non i nostri.
Verifica con dark (le dimensioni dei default sono **2746** banner e **68468** dialog):

```powershell
dark.exe -x dist\EmployeeScheduling-1.2.0.msi
Get-ChildItem dist\*.msi | ForEach-Object { dark.exe -x "$env:TEMP\chk" $_ }  # Binary\
# WixUI_Bmp_Banner / WixUI_Bmp_Dialog devono avere la dimensione delle nostre bitmap
# (es. 48982 / 260262 per 370x44 / 370x234)
```

Cause e rimedi: vedi 7.12 (posizione delle WixVariable).

**Dimensioni reali dei controlli Bitmap** (dalla tabella Control dell'MSI prodotto da
jpackage, NON i 493x58/493x312 creduti in passato): banner **370x44**, sfondo dialog
**370x234** (dialoghi 370x270; la fascia bassa ospita i pulsanti di navigazione).

### 7.14 Il testo dell'installer si sovrappone all'immagine / è illeggibile

Il titolo e la descrizione sono disegnati **sopra** le bitmap, a **coordinate fisse**
(definite nei dialoghi di WixUIExtension, che jpackage usa via `UIRef JpUI`):

| Dialogo | Controllo | Posizione (X,Y,W,H) |
|---|---|---|
| WelcomeDlg | Title | 135, 20, 220, 60 |
| WelcomeDlg | Description | 135, 80, 220, 60 |
| WelcomeDlg | PatchDescription | 135, 80, 220, 60 |
| Banner (tutti i dialoghi) | Title | 15, 6, 200, 15 |
| Banner (tutti i dialoghi) | Description | 25, 23, 280, 15 |

- Il testo è **bianco** (stile `{\WixUI_Font_Bigger}`): su immagini chiare è illeggibile
  ovunque. Le master attuali hanno luminosità media 232/255.
- **Non si può sovrascrivere il dialogo in WiX v3**: definire un `<Dialog Id="WelcomeDlg">`
  custom accanto a `UIRef JpUI` dà `LGHT0091 Duplicate symbol 'Dialog:WelcomeDlg'`.
  L'alternativa UI-custom completa (replicare JpUI = WixUI_InstallDir + ShortcutPromptDlg +
  InstallDirNotEmptyDlg + 5 eventi Publish, che jpackage genera a runtime in `ui.wxf` via
  `WixUiFragmentBuilder`) è possibile ma costosa e rischiosa.
- **Soluzione adottata (verificata)**: patch **post-build della tabella Control** dell'MSI.
  Il testo va nel blocco vuoto dell'immagine: X=20, W=175 → occupa x 20..195, dentro la
  zona vuota x 0..200 misurata sull'immagine attuale (analisi pixel: varianza per colonna,
  blocco vuoto = colonne con std < 12 nella fascia y=10..150). Il wizard la applica da
  solo su ogni build MSI (`Invoke-MsiTextPositionPatch`, che genera e lancia questo
  script via cscript); se l'immagine cambia, ricalcolare il blocco vuoto e ritoccare X/W
  nella funzione.

Script (quello che il wizard genera in `%TEMP%\msi-move-text.js`; da eseguire **dopo
ogni build jpackage** se si lavora a mano — cscript + WindowsInstaller COM;
`view.Close()`/`db.Close()` non esistono, **`db.Commit()` è quello che salva**):

```js
// msi-move-text.js  —  cscript //nologo msi-move-text.js dist\EmployeeScheduling-1.2.0.msi
var path = WScript.Arguments(0);
var msi = new ActiveXObject("WindowsInstaller.Installer");
var db = msi.OpenDatabase(path, 2); // 2 = transact (scrittura)
var view = db.OpenView("SELECT * FROM Control WHERE Dialog_ = 'WelcomeDlg' AND (Control = 'Title' OR Control = 'Description' OR Control = 'PatchDescription')");
view.Execute();
var rec = view.Fetch();
while (rec) {
  rec.StringData(4) = "20";   // X
  rec.StringData(6) = "175";  // Width
  view.Modify(3, rec);        // 3 = msiViewModifyUpdate
  rec = view.Fetch();
}
db.Commit();
```

Stato: il wizard lo applica da solo su ogni build (`Invoke-MsiTextPositionPatch`,
verificato l'8 agosto 2026 su `install-windows.ps1 -Package msi`). Se l'immagine cambia,
ricalcolare il blocco vuoto e ritoccare X/W.

### 7.15 Quirk di verifica MSI (per non impazzire)

- Il COM `WindowsInstaller` da PowerShell diretto fallisce (`OpenView`/`Fetch` con
  DISP_E_TYPEMISMATCH) → usare **cscript + JScript** (`ActiveXObject("WindowsInstaller.Installer")`).
- JScript: la clausola SQL `IN ('a','b')` fallisce con "OpenView,Sql" → usare `OR` espliciti.
- Servono `SELECT *` per fare Modify; il **testo** del controllo sta nel **campo 10**
  (il campo 9 è vuoto: 1=Dialog_, 2=Control, 3=Type, 4=X, 5=Y, 6=Width, 7=Height,
  8=Attributes, 10=Text).
- `db.Commit()` obbligatorio (modalità 2 = transact); senza Commit le modifiche si perdono.
- `dark.exe` emette warning **DARK1059** (foreign row Control "non trovata") sugli MSI
  jpackage: falsi allarmi, le tabelle esistono (verificato con `SELECT * FROM _Tables`).
- Il file `dist\EmployeeScheduling-1.2.0.msi` **può sparire** tra un comando e l'altro
  (attività dell'utente o processo che lo tiene aperto): ricostruire prima di verificare,
  con il comando jpackage diretto del §4.4 (staging `target\jpackage-input` e risorse
  `target\jpackage-resources` restano pronti tra una build e l'altra).

---

## 8. Lista di verifica prima di consegnare

Da eseguire **su ogni pacchetto**, prima di darlo a qualcuno.

1. **Il jar è più recente della build del frontend?**
   ```powershell
   Get-Item target\*runner.jar | Select-Object LastWriteTime
   Get-Item src\main\resources\META-INF\resources\index.html | Select-Object LastWriteTime
   ```
2. **Il `.cfg` contiene ciò che deve** — costruire l'app-image e leggerlo:
   ```powershell
   Get-Content dist\EmployeeScheduling\app\EmployeeScheduling.cfg
   ```
   Devono esserci `-Dapp.data.dir=auto` e una `encryption-key` **casuale di almeno 32
   caratteri**. Se leggi `0123456789abcdef...` o una chiave corta, rifai il pacchetto.
3. **I file di disinstallazione ci sono**: `dist\EmployeeScheduling\app\` deve contenere
   `uninstall-windows.ps1` e `uninstall.cmd`.
4. **Lo zip ha la dimensione giusta** (~125 MB, non poche centinaia di KB — vedi 7.9).
5. **Prova di primo avvio**: installare, verificare che si apra il browser, registrare
   l'amministratore iniziale e **entrare direttamente nell'applicazione**.
6. **Prova dei dati**: `%LOCALAPPDATA%\EmployeeScheduling` deve contenere `large_data.db`,
   `config.properties` e, dopo un paio di minuti, un file in `backups\`.
7. **Prova dei permessi** (chiude 7.4):
   ```powershell
   (Get-Acl "$env:LOCALAPPDATA\EmployeeScheduling\backups").AreAccessRulesProtected   # deve essere False
   ```
8. **Prova di disinstallazione**: `uninstall.cmd` con doppio clic; al termine
   `%LOCALAPPDATA%\EmployeeScheduling` deve **esistere ancora**.

---

## 9. Consegna e aggiornamento

**Consegna**: si pubblica il solo `.msi` (GitHub Releases, cartella condivisa, chiavetta). Chi
installa fa doppio clic, sceglie la cartella, e al primo avvio si registra: il primo utente
diventa amministratore attivo.

### 9.1 L'applicazione avvisa da sola che esiste una versione nuova

Chi ha installato non controlla il repository: va avvisato dall'applicazione. All'accesso,
**solo agli amministratori** — gli unici che possono aggiornare — compare una modale se la
versione pubblicata è maggiore di quella installata, con il link per scaricare e le istruzioni
(disinstalla, reinstalla, i dati restano). Si chiude una volta per versione e non torna finché
non ne esce un'altra.

Perché funzioni servono tre cose:

| Cosa | Dove |
|---|---|
| La versione installata | La dichiara jpackage nel `.cfg` (`-Djpackage.app-version`), e viene dal `pom.xml` |
| La versione pubblicata | Letta da `updates.app.releases-api`, per impostazione predefinita `releases/latest` del repository su GitHub |
| Il confronto | `SystemInfoResource.isNewerVersion`, numerico per componenti, coperto da `AppVersionComparisonTest` |

**Procedura di rilascio**, da rispettare o l'avviso non parte:

1. alzare la versione nel `pom.xml` (è la fonte, non lo script);
2. generare l'MSI;
3. pubblicare su GitHub una **release con il tag** della stessa versione (`v1.2.0` o `1.2.0`,
   la "v" viene ignorata) e **allegare l'MSI**;
4. le installazioni esistenti se ne accorgono al primo accesso successivo, con al massimo
   un'ora di ritardo per via della cache.

Il controllo **fallisce in silenzio**: senza rete, dietro proxy o con la quota dell'API GitHub
esaurita, non compare nulla — mai un messaggio d'errore per qualcosa che l'utente non ha
chiesto. Per spegnerlo del tutto (rete isolata, o semplicemente non lo si vuole) basta
svuotare la proprietà in `config.properties`:

```properties
updates.app.releases-api=
```

**Aggiornamento di un'installazione esistente**:

1. `uninstall.cmd` (i dati restano);
2. installare l'MSI nuovo;
3. l'applicazione ritrova database, backup e `config.properties` dove li aveva lasciati.

**Portare dentro un database già popolato** (per esempio quello di sviluppo): copiarlo su
`%LOCALAPPDATA%\EmployeeScheduling\large_data.db` **ad applicazione chiusa**. Due condizioni,
entrambe verificate sul campo:

- il database **deve contenere la tabella `flyway_schema_history`**. Il profilo SQLite ha
  `baseline-on-migrate=false`, quindi Flyway rifiuta di partire su uno schema popolato senza
  storico. Il database di sviluppo del repository **non ce l'ha**: va copiata da un database
  creato dall'applicazione;
- gli utenti del database d'origine hanno password **bcrypt sconosciute**: o si conoscono le
  credenziali, oppure si svuota `app_users` (l'applicazione riproporrà la creazione del primo
  amministratore) o si trapianta la propria riga utente da un database creato dall'app.

---

## 10. Disinstallazione

```
Doppio clic su  <install>\app\uninstall.cmd
```

Chiude l'applicazione, ripristina i permessi delle installazioni vecchie (7.4), chiama
`msiexec` e **conserva i dati**. Con `-RemoveData` rimuove anche `%LOCALAPPDATA%\EmployeeScheduling`,
chiedendo conferma.

Lo script si ricopia in `%TEMP%` e riparte da lì: uno script in esecuzione dentro la cartella da
rimuovere ne impedirebbe la cancellazione.

Funziona anche *Impostazioni → App → EmployeeScheduling → Disinstalla*, purché l'applicazione
sia chiusa.

---

## 11. Punti aperti

- **`app.sqlite.legacy-bootstrap`** vale `true` per impostazione predefinita in
  `application.properties` ed è `false` in tutti i profili espliciti. Nel pacchetto va verificato
  quale profilo sia attivo a runtime: se il bootstrap storico gira su un'installazione utente,
  può seminare dati di prova. Su un database creato dall'MSI il 5 agosto 2026 **non** è stato
  osservato alcun dato di test (una sola struttura, "Default"), ma la catena non è stata tracciata
  fino in fondo.
- **Nessuna firma digitale**: SmartScreen mostrerà l'avviso "editore sconosciuto". Serve un
  certificato di code signing.
- **Aggiornamento in-place**: non gestito, si disinstalla e si reinstalla (7.10).
- **Registrazioni successive alla prima**: in modalità standalone il commento in
  `application.properties` dice che sono chiuse, il codice le accetta creando utenti inattivi.
  Divergenza da risolvere in un senso o nell'altro.
- **Se l'immagine del dialogo cambia**: ricalcolare il blocco vuoto (analisi pixel,
  §7.14) e aggiornare X/W in `Invoke-MsiTextPositionPatch`.
