package org.acme.employeescheduling.rest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * @brief PostgreSQL backup performed by the application through `pg_dump`.
 *
 * @details Replaces the historical stub that delegated backups to infrastructure. The contract
 *          is identical to SQLite, so the Configuration → Backup page works without changes:
 *          same fields (`filename`, `timestamp`, `tag`, `size`), same tags
 *          (`auto`/`manual`/`preop`), same rotation by count and by age.
 *
 *          Design choices:
 *          - **Custom format (`-Fc`)**: one compressed file, as the interface already models
 *          (`resolveBackup`/`delete`/`restore` take a `Path`); it is the only format that supports
 *          `--single-transaction` during restore.
 *          - **Binaries outside PATH**: on Windows, client tools live in
 *          `C:\Program Files\PostgreSQL\<major>\bin` and are not in PATH. Search
 *          `backup.postgresql.bin-dir` first, then PATH, then known installations, choosing the
 *          highest major version.
 *          - **Password**: passed through `PGPASSWORD` in the child process environment, never in
 *          the URI, which would be readable through `Win32_Process.CommandLine` on Windows.
 *          - **Compatibility**: `pg_dump` refuses to read a server with a newer major version.
 *          Probe the version at startup and disable the feature with a clear message instead of
 *          failing halfway through a scheduled backup.
 */
@ApplicationScoped
@IfBuildProperty(name = "app.database.kind", stringValue = "postgresql")
public class PostgresqlBackupService implements DatabaseBackupService {

    private static final Logger logger = Logger.getLogger(PostgresqlBackupService.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /**
     * @brief Backup filename: base_yyyyMMdd_HHmmss[_n]_tag.dump (no path traversal).
     */
    private static final Pattern BACKUP_NAME = Pattern
            .compile("^([A-Za-z0-9_-]+)_(\\d{8}_\\d{6})(?:_\\d+)?_([a-z]+)\\.dump$");
    /** @brief jdbc:postgresql://host[:port]/database[?parameters] */
    private static final Pattern JDBC_URL = Pattern.compile("^jdbc:postgresql://([^/:?]+)(?::(\\d+))?/([^?/]+)");
    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?");
    /**
     * @brief Allowed database names: prevents both --dbname injection and ghost backups.
     */
    private static final Pattern SAFE_DATABASE = Pattern.compile("^[A-Za-z0-9_-]{1,63}$");
    /**
     * @brief Typical Windows installations, searched only if binaries are not in PATH.
     */
    private static final String WINDOWS_INSTALL_ROOT = "C:\\Program Files\\PostgreSQL";
    /**
     * Maximum wait for a pre-operation backup lock: after this, give up and continue.
     */
    private static final int PREOP_LOCK_WAIT_SECONDS = 5;
    /** Exact limit on output collected from a client tool. */
    private static final int OUTPUT_CAP = 8192;
    /**
     * A dump index contains one row per object and requires a separate limit.
     */
    private static final int ARCHIVE_INDEX_OUTPUT_CAP = 1_048_576;
    /**
     * Maximum wait for the restore lock: after this, give up without touching the database.
     */
    private static final int LOCK_WAIT_SECONDS = 60;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    Optional<String> username;

    @ConfigProperty(name = "quarkus.datasource.password")
    Optional<String> password;

    @ConfigProperty(name = "backup.dir", defaultValue = "databases/backups")
    String backupDir;

    @ConfigProperty(name = "backup.auto.keep", defaultValue = "48")
    int defaultAutoKeep;

    @ConfigProperty(name = "backup.other.keep", defaultValue = "20")
    int defaultOtherKeep;

    @ConfigProperty(name = "backup.auto.retention-days", defaultValue = "30")
    int defaultAutoRetentionDays;

    @ConfigProperty(name = "backup.other.retention-days", defaultValue = "90")
    int defaultOtherRetentionDays;

    @ConfigProperty(name = "backup.settings.file", defaultValue = "databases/backup-settings.properties")
    String settingsFile;

    /**
     * @brief Client-tool directory if they are not in PATH or in the expected locations.
     */
    @ConfigProperty(name = "backup.postgresql.bin-dir")
    Optional<String> configuredBinDir;

    @ConfigProperty(name = "backup.postgresql.timeout-seconds", defaultValue = "600")
    int dumpTimeoutSeconds;

    @ConfigProperty(name = "backup.postgresql.sslmode")
    Optional<String> configuredSslMode;

    @ConfigProperty(name = "backup.postgresql.sslrootcert")
    Optional<String> configuredSslRootCert;

    /**
     * Lower limit for pre-operation backups: they run inside a user request.
     */
    @ConfigProperty(name = "backup.postgresql.preop-timeout-seconds", defaultValue = "120")
    int preopTimeoutSeconds;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    DemoDataRepository repo;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile long lastAutomaticRun = 0;
    private volatile int intervalMinutes = 30;
    private volatile int autoRetentionDays;
    private volatile int otherRetentionDays;
    private volatile int autoKeep;
    private volatile int otherKeep;

    /**
     * Resolved once at startup: the pg_dump path and the reason for any absence.
     */
    private volatile Path pgDump;
    private volatile Path pgRestore;
    private volatile String unavailableReason;
    private volatile String host;
    private volatile int port = 5432;
    private volatile String database;
    private volatile String sslMode;
    private volatile String sslRootCert;
    /**
     * Database name normalized for use in filenames (see BACKUP_NAME).
     */
    private volatile String fileBase;

    @PostConstruct
    void initialize() {
        loadSettings();
        hardenExistingBackups();
        parseJdbcUrl();
        locateClientTools();
    }

    // ------------------------------------------------------------------
    // Availability

    /**
     * @details Not a constant: without `pg_dump`, or with a client older than the server, backup
     *          cannot be performed, and the UI must say so instead of offering a button that fails.
     */
    @Override
    public boolean isAvailable() {
        return pgDump != null;
    }

    /** @brief Why backup is unavailable, for logging and diagnostics. */
    String unavailableReason() {
        return unavailableReason;
    }

    /**
     * @details The database name is strictly validated for two distinct reasons addressed by the
     *          same check:
     *          <ul>
     *          <li>{@code --dbname=} is not a name but a <b>connection string</b>: if it contains
     *          {@code =}, libpq expands it and overrides explicit {@code --host}/{@code --port}.
     *          Someone controlling {@code DATABASE_URL} could make the dump read another server.</li>
     *          <li>{@code BACKUP_NAME} allows only {@code [A-Za-z0-9_-]} in the base: a legitimate
     *          name containing a dot or space would produce files that are created and then
     *          invisible — not listed, not deletable, and <b>never rotated</b> — while the UI says
     *          "Backup completed".</li>
     *          </ul>
     *          Disabling the feature with an explicit reason is better than producing ghost backups.
     */
    void parseJdbcUrl() {
        Matcher matcher = JDBC_URL.matcher(jdbcUrl == null ? "" : jdbcUrl.trim());
        if (!matcher.find()) {
            // Put the reason in the log, not only the field: otherwise backups remain silently
            // disabled and the UI shows a generic "unavailable" message.
            unavailableReason = "URL datasource non riconosciuto (atteso jdbc:postgresql://host[:porta]/database)";
            logger.warning("Backup PostgreSQL non disponibile. " + unavailableReason);
            return;
        }
        String candidate = matcher.group(3);
        if (!SAFE_DATABASE.matcher(candidate).matches()) {
            unavailableReason = "Nome database non utilizzabile per i backup: ammessi lettere, "
                    + "cifre, underscore e trattino (max 63 caratteri)";
            logger.warning("Backup PostgreSQL non disponibile. " + unavailableReason);
            return;
        }
        if (username.isEmpty() || username.get().isBlank()) {
            // An empty --username= is not inert: pg_dump falls back to the operating-system user,
            // and the dump would run under an identity different from the application's.
            unavailableReason = "Utente del datasource non configurato";
            logger.warning("Backup PostgreSQL non disponibile. " + unavailableReason);
            return;
        }
        host = matcher.group(1);
        if (matcher.group(2) != null)
            port = Integer.parseInt(matcher.group(2));
        database = candidate;
        fileBase = candidate;
        Map<String, String> query = jdbcQueryParameters(jdbcUrl);
        sslMode = Optional.ofNullable(configuredSslMode).orElse(Optional.empty())
                .map(String::trim).filter(value -> !value.isEmpty())
                .orElse(query.get("sslmode"));
        sslRootCert = Optional.ofNullable(configuredSslRootCert).orElse(Optional.empty())
                .map(String::trim).filter(value -> !value.isEmpty())
                .orElse(query.get("sslrootcert"));
        if (isLoopback(host)) {
            if (sslMode == null || sslMode.isBlank())
                sslMode = "disable";
        } else if (!"verify-full".equalsIgnoreCase(sslMode)) {
            database = null;
            unavailableReason = "Connessione PostgreSQL remota non verificata: configurare "
                    + "backup.postgresql.sslmode=verify-full e la CA attendibile";
            logger.warning("Backup PostgreSQL non disponibile. " + unavailableReason);
        }
    }

    private static Map<String, String> jdbcQueryParameters(String url) {
        Map<String, String> result = new java.util.HashMap<>();
        if (url == null)
            return result;
        int question = url.indexOf('?');
        if (question < 0 || question == url.length() - 1)
            return result;
        for (String pair : url.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0)
                continue;
            try {
                String key = java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8)
                        .toLowerCase();
                String value = java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                result.put(key, value);
            } catch (IllegalArgumentException ignored) {
                // Malformed JDBC query: do not propagate the parameter to client tools.
            }
        }
        return result;
    }

    private static boolean isLoopback(String value) {
        try {
            return java.net.InetAddress.getByName(value).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String oneLine(String value) {
        if (value == null)
            return "";
        return value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replace('\t', ' ').trim();
    }

    private void locateClientTools() {
        if (database == null)
            return; // URL not parsed: the reason is already recorded.
        Path candidate = findPgDump();
        if (candidate == null) {
            unavailableReason = "pg_dump non trovato: installare i PostgreSQL client tools oppure "
                    + "indicarne la cartella con backup.postgresql.bin-dir";
            logger.warning("Backup PostgreSQL non disponibile. " + unavailableReason);
            return;
        }
        Integer clientMajor = majorVersion(runQuietly(candidate.toString(), "--version"));
        Integer serverMajor = serverMajorVersion();
        if (clientMajor != null && serverMajor != null && clientMajor < serverMajor) {
            // pg_dump refuses to read a server newer than its own major version: report it now
            // rather than discovering it halfway through the first automatic backup.
            unavailableReason = "pg_dump " + clientMajor + " non puo' leggere un server "
                    + serverMajor + ": aggiornare i client tool";
            logger.warning("Backup PostgreSQL non disponibile. " + unavailableReason);
            return;
        }
        // pg_restore sits beside pg_dump: if missing, backup remains usable but restore does not,
        // and the UI must know (restoreSupported).
        Path restoreTool = candidate.resolveSibling(isWindows() ? "pg_restore.exe" : "pg_restore");
        pgRestore = Files.isExecutable(restoreTool) ? restoreTool : null;
        pgDump = candidate;
        unavailableReason = null;
        logger.info("Backup PostgreSQL attivo con " + candidate + " (client " + clientMajor
                + ", server " + serverMajor + "), ripristino "
                + (pgRestore != null ? "disponibile" : "NON disponibile: pg_restore mancante"));
    }

    private Path findPgDump() {
        String executable = isWindows() ? "pg_dump.exe" : "pg_dump";
        if (configuredBinDir.isPresent() && !configuredBinDir.get().isBlank()) {
            Path explicit = Path.of(configuredBinDir.get().trim()).resolve(executable)
                    .toAbsolutePath().normalize();
            // Manually configured: if incorrect, report it rather than searching elsewhere.
            return Files.isExecutable(explicit) ? explicit : null;
        }
        // Never execute a bare name: on Windows, ProcessBuilder may search the working directory
        // first. Resolve an absolute path; only locateClientTools executes it.
        Path resolved = fromSystemPath(executable);
        if (resolved != null)
            return resolved;
        return highestInstalledMajor(executable);
    }

    /**
     * @brief First PATH entry containing the executable, as an absolute path.
     */
    private static Path fromSystemPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null)
            return null;
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank())
                continue;
            try {
                Path candidate = Path.of(entry.trim(), executable).toAbsolutePath().normalize();
                if (Files.isExecutable(candidate))
                    return candidate;
            } catch (Exception ignored) {
                // Malformed PATH entry: move to the next one.
            }
        }
        return null;
    }

    /**
     * @brief Among multiple side-by-side installations, the highest major version wins: it can
     *        read every server.
     */
    private Path highestInstalledMajor(String executable) {
        Path root = Path.of(WINDOWS_INSTALL_ROOT);
        if (!Files.isDirectory(root))
            return null;
        try (var children = Files.list(root)) {
            return children.map(dir -> dir.resolve("bin").resolve(executable))
                    .filter(Files::isExecutable)
                    .max(Comparator.comparingInt(p -> majorFromInstallPath(p)))
                    .orElse(null);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Scansione delle installazioni PostgreSQL non riuscita", e);
            return null;
        }
    }

    private static int majorFromInstallPath(Path pgDumpPath) {
        // .../PostgreSQL/<major>/bin/pg_dump.exe
        Path major = pgDumpPath.getParent() != null ? pgDumpPath.getParent().getParent() : null;
        Integer parsed = major == null ? null : majorVersion(major.getFileName().toString());
        return parsed == null ? 0 : parsed;
    }

    private Integer serverMajorVersion() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SHOW server_version")) {
            return rs.next() ? majorVersion(rs.getString(1)) : null;
        } catch (Exception e) {
            // Do not block: without the comparison, still try and let pg_dump report its error.
            logger.log(Level.FINE, "Versione del server PostgreSQL non leggibile", e);
            return null;
        }
    }

    static Integer majorVersion(String versionText) {
        if (versionText == null)
            return null;
        Matcher matcher = VERSION.matcher(versionText);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ------------------------------------------------------------------
    // Settings

    @Override
    public Map<String, Object> getSettings() {
        return Map.of("intervalMinutes", intervalMinutes,
                "autoRetentionDays", autoRetentionDays,
                "otherRetentionDays", otherRetentionDays,
                "autoKeep", autoKeep,
                "otherKeep", otherKeep,
                // Restore requires pg_restore beside pg_dump: without it, the UI must not offer
                // a button that cannot work.
                "restoreSupported", pgRestore != null);
    }

    @Override
    public void saveSettings(int interval, int autoDays, int otherDays, int autoKeepCount, int otherKeepCount)
            throws Exception {
        if (interval < 1 || interval > 1440 || autoDays < 1 || autoDays > 3650 || otherDays < 1 || otherDays > 3650
                || autoKeepCount < 1 || autoKeepCount > 100000 || otherKeepCount < 1 || otherKeepCount > 100000
                || (long) autoDays * 1440 < (long) interval * 2)
            throw new IllegalArgumentException("Valori impostazioni backup non validi");
        lock.lock();
        try {
            Properties p = new Properties();
            p.setProperty("intervalMinutes", String.valueOf(interval));
            p.setProperty("autoRetentionDays", String.valueOf(autoDays));
            p.setProperty("otherRetentionDays", String.valueOf(otherDays));
            p.setProperty("autoKeep", String.valueOf(autoKeepCount));
            p.setProperty("otherKeep", String.valueOf(otherKeepCount));
            writeSettingsAtomically(p);
            intervalMinutes = interval;
            autoRetentionDays = autoDays;
            otherRetentionDays = otherDays;
            autoKeep = autoKeepCount;
            otherKeep = otherKeepCount;
            rotate();
        } finally {
            lock.unlock();
        }
    }

    void loadSettings() {
        autoRetentionDays = defaultAutoRetentionDays;
        otherRetentionDays = defaultOtherRetentionDays;
        autoKeep = defaultAutoKeep;
        otherKeep = defaultOtherKeep;
        Path file = Path.of(settingsFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return;
        try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            Properties p = new Properties();
            p.load(in);
            intervalMinutes = bounded(p.getProperty("intervalMinutes"), intervalMinutes, 1, 1440);
            autoRetentionDays = bounded(p.getProperty("autoRetentionDays"), autoRetentionDays, 1, 3650);
            otherRetentionDays = bounded(p.getProperty("otherRetentionDays"), otherRetentionDays, 1, 3650);
            autoKeep = bounded(p.getProperty("autoKeep"), autoKeep, 1, 100000);
            otherKeep = bounded(p.getProperty("otherKeep"), otherKeep, 1, 100000);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Impostazioni backup non leggibili, restano i default", e);
        }
    }

    private void hardenExistingBackups() {
        try {
            Path dir = safeBackupDirectory();
            try (var files = Files.list(dir)) {
                for (Path file : files.filter(path -> BACKUP_NAME.matcher(path.getFileName().toString()).matches())
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
                    restrictPermissions(file, false);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Permessi directory backup PostgreSQL non bonificabili", failure);
        }
    }

    private static int bounded(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= min && value <= max ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ backup

    /**
     * @brief Performs a custom-format dump. @return information about the created file.
     * @details `--no-owner --no-acl` make the archive restorable under a different role;
     *          `--lock-wait-timeout` prevents another party's lock from blocking the dump forever.
     */
    @Override
    public Map<String, Object> performBackup(String tag) throws Exception {
        return performBackup(tag, dumpTimeoutSeconds);
    }

    private Map<String, Object> performBackup(String tag, int timeoutSeconds) throws Exception {
        if (!isAvailable())
            throw new IllegalStateException("Backup PostgreSQL non disponibile: " + unavailableReason);
        lock.lock();
        try {
            String safeTag = tag != null && tag.matches("[a-z]{1,20}") ? tag : "manual";
            Path dir = safeBackupDirectory();
            String ts = LocalDateTime.now().format(TS);
            Path target = dir.resolve(fileBase + "_" + ts + "_" + safeTag + ".dump");
            for (int n = 1; Files.exists(target); n++) {
                target = dir.resolve(fileBase + "_" + ts + "_" + n + "_" + safeTag + ".dump");
            }
            Path staging = Files.createTempFile(dir, ".pg-backup-", ".part");
            restrictPermissions(staging, false);
            List<String> command = List.of(pgDump.toString(),
                    "--format=custom", "--no-password", "--no-owner", "--no-acl",
                    "--lock-wait-timeout=30s", "--schema=public",
                    "--host=" + host, "--port=" + port,
                    "--username=" + username.orElseThrow(),
                    "--dbname=" + database,
                    "--file=" + staging);
            // An interrupted dump is worse than no dump. The dangerous case is not a nonzero exit
            // code (which leaves an obviously empty file), but TIMEOUT: the file is large,
            // truncated, and looks like a good backup. Remove it after any failure, not only those
            // anticipated.
            try {
                Execution execution = run(command, timeoutSeconds);
                if (execution.exitCode() != 0)
                    throw new IOException("pg_dump uscito con codice " + execution.exitCode()
                            + ": " + oneLine(execution.output()));
                if (!isPostgresqlArchive(staging))
                    throw new IOException("pg_dump non ha prodotto un archivio custom valido");
                try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                publishAtomically(staging, target);
            } catch (Exception failure) {
                Files.deleteIfExists(staging);
                throw failure;
            }
            rotate();
            logger.info("Backup DB creato: " + target.getFileName());
            return describe(target);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @brief Snapshot before an operation that rewrites shifts in bulk.
     *
     * @details On SQLite this is a millisecond-long `VACUUM INTO`; here it is a complete
     *          `pg_dump`, and all three callers run inside a user's REST request. Hence the
     *          limited lock wait (no queueing behind an automatic dump) and the lower duration
     *          limit.
     *
     *          <p>The operation is <b>blocked</b> when this does not return
     *          {@link SafetyBackupOutcome#OK} — the previous wording here claimed the opposite
     *          ("best effort; does not block"), while all three callers answered 503. Each of the
     *          four exits below is a distinct outcome because they demand opposite reactions from
     *          the user; see {@link SafetyBackupOutcome}.</p>
     */
    @Override
    public SafetyBackupOutcome safetyBackup(String tag) {
        if (!isAvailable()) {
            logger.severe("Backup di sicurezza impossibile: pg_dump assente o piu' vecchio del server."
                    + " L'operazione e' annullata: installare i client tool PostgreSQL.");
            return SafetyBackupOutcome.CLIENT_TOOLS_MISSING;
        }
        try {
            if (!lock.tryLock(PREOP_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Backup di sicurezza saltato: operazione annullata per backup in corso");
                return SafetyBackupOutcome.BUSY;
            }
            try {
                performBackup(tag, preopTimeoutSeconds);
                return SafetyBackupOutcome.OK;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warning("Backup di sicurezza interrotto: operazione annullata");
            return SafetyBackupOutcome.BUSY;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Backup di sicurezza non riuscito: operazione annullata", e);
            return SafetyBackupOutcome.FAILED;
        }
    }

    /**
     * @brief Automatic backup at a configurable interval.
     * @details Cron runs every minute and the actual interval is compared manually, so changing it
     *          from the UI takes effect without restarting the scheduler.
     */
    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledBackup() {
        if (!isAvailable())
            return;
        try {
            long now = System.currentTimeMillis();
            if (lastAutomaticRun > 0 && now - lastAutomaticRun < intervalMinutes * 60_000L)
                return;
            performBackup("auto");
            // Updated after backup: an error must not postpone retry for an entire interval.
            lastAutomaticRun = System.currentTimeMillis();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Backup automatico non riuscito", e);
        }
    }

    // ------------------------------------------------------------------ Listing and deletion

    @Override
    public List<Map<String, Object>> listBackups() {
        List<Map<String, Object>> result = new ArrayList<>();
        Path dir = Path.of(backupDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            return result;
        try (var files = Files.list(dir)) {
            files.filter(p -> BACKUP_NAME.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    // The timestamp format is text-sortable: descending name = newest first.
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(p -> result.add(describe(p)));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Elenco backup non leggibile", e);
        }
        return result;
    }

    @Override
    public Path resolveBackup(String filename) {
        if (filename == null || !BACKUP_NAME.matcher(filename).matches())
            return null;
        Path dir = Path.of(backupDir).toAbsolutePath().normalize();
        Path p = dir.resolve(filename).normalize();
        return p.getParent() != null && p.getParent().equals(dir)
                && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? p : null;
    }

    @Override
    public boolean delete(Path backupFile) throws Exception {
        lock.lock();
        try {
            if (backupFile == null)
                return false;
            Path dir = safeBackupDirectory();
            Path candidate = backupFile.toAbsolutePath().normalize();
            if (!dir.equals(candidate.getParent())
                    || !BACKUP_NAME.matcher(candidate.getFileName().toString()).matches()
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                return false;
            return Files.deleteIfExists(candidate);
        } finally {
            lock.unlock();
        }
    }

    private Path safeBackupDirectory() throws IOException {
        Path dir = Path.of(backupDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("La directory backup non e' una directory reale: " + dir);
        restrictPermissions(dir, true);
        return dir;
    }

    private static void restrictPermissions(Path path, boolean directory) throws IOException {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> permissions = directory
                    ? Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
            return;
        } catch (UnsupportedOperationException ignored) {
            // Windows: apply an explicit owner-only DACL below.
        }
        var view = Files.getFileAttributeView(path,
                java.nio.file.attribute.AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null)
            throw new IOException("Filesystem senza permessi POSIX o ACL: " + path);
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        var entry = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                .build();
        view.setAcl(List.of(entry));
    }

    private void writeSettingsAtomically(Properties properties) throws Exception {
        Path target = Path.of(settingsFile).toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null)
            throw new IOException("Percorso impostazioni backup non valido");
        Files.createDirectories(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Directory impostazioni backup non valida: " + parent);
        Path staging = Files.createTempFile(parent, ".backup-settings-", ".part");
        try {
            restrictPermissions(staging, false);
            try (var out = Files.newOutputStream(staging, StandardOpenOption.WRITE)) {
                properties.store(out, "Backup settings");
            }
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static boolean isPostgresqlArchive(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) <= 5)
            return false;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return "PGDMP".equals(new String(in.readNBytes(5), StandardCharsets.US_ASCII));
        }
    }

    private static void publishAtomically(Path staging, Path target) throws IOException {
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(target.getParent());
    }

    private static void syncDirectory(Path directory) {
        if (isWindows())
            return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Directory backup non sincronizzabile: " + directory, failure);
        }
    }

    // ------------------------------------------------------------------ Restore

    /**
     * @brief Restores the database reversibly from the specified archive.
     *
     * @details Stages, in order, with the exclusive REST boundary moved as late as possible:
     *          <ol>
     *          <li><b>Validation</b> — {@code pg_restore --list} reads the archive index and fails
     *          on a truncated or foreign file <b>without touching any database</b>. It runs
     *          outside the exclusive section: rejection must not incur a lock.</li>
     *          <li><b>Arming</b> — a validated {@code prerestore} dump of current state. Do not
     *          promote without a proven rollback source: that is the typical path to an
     *          unrecoverable state.</li>
     *          <li><b>Atomic promotion</b> —
     *          {@code pg_restore --clean --if-exists
     *                --single-transaction --schema=public} replaces only objects in
     *          {@code public}. The single transaction implies exit-on-error: either everything
     *          succeeds, or PostgreSQL leaves the previous state unchanged.</li>
     *          <li><b>Verification</b> — reread the structural fingerprint after commit. The
     *          {@code prerestore} dump remains the final safety net for an anomaly detected after
     *          commit, not the normal rollback mechanism.</li>
     *          </ol>
     */
    @Override
    public RestoreOutcome restore(Path backupFile) throws Exception {
        if (!isAvailable())
            return RestoreOutcome.rejected("BACKUP_TOOLS_UNAVAILABLE", unavailableReason);
        if (pgRestore == null)
            return RestoreOutcome.rejected("RESTORE_NOT_SUPPORTED",
                    "pg_restore non trovato accanto a pg_dump");

        Path staged;
        try {
            staged = stageRestoreSource(backupFile);
        } catch (BackupLockBusyException busy) {
            return RestoreOutcome.rejected("BACKUP_IN_PROGRESS", busy.getMessage());
        }
        if (staged == null)
            return RestoreOutcome.rejected(RestoreOutcome.NOT_A_DATABASE,
                    "Il file non esiste, e' un link simbolico o non e' leggibile");
        try {
            Matcher nameMatcher = BACKUP_NAME.matcher(backupFile.getFileName().toString());
            if (!nameMatcher.matches() || !fileBase.equals(nameMatcher.group(1)))
                return RestoreOutcome.rejected(RestoreOutcome.INCOMPATIBLE_DATABASE,
                        "Il backup non proviene da questo database");
            SchemaFingerprint expected = readSchemaFingerprint();
            ArchiveValidation validation = validateArchive(staged, expected.tables());
            if (!validation.readable())
                return RestoreOutcome.rejected(RestoreOutcome.NOT_A_DATABASE,
                        "Archivio non leggibile da pg_restore: " + validation.detail());
            if (!validation.compatible())
                return RestoreOutcome.rejected(RestoreOutcome.INCOMPATIBLE_DATABASE,
                        validation.detail());
            try {
                return DatabaseRequestGate.withExclusiveDatabaseAccess(
                        () -> promote(staged, expected, validation));
            } catch (DatabaseRequestGate.GateBusyException busy) {
                return RestoreOutcome.rejected("DATABASE_BUSY", busy.getMessage());
            }
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException cleanupFailure) {
                logger.log(Level.WARNING, "File temporaneo restore PostgreSQL non eliminato: " + staged,
                        cleanupFailure);
            }
        }
    }

    private Path stageRestoreSource(Path source) throws IOException, BackupLockBusyException {
        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS))
                throw new BackupLockBusyException("Backup in corso: lock non ottenuto entro "
                        + LOCK_WAIT_SECONDS + "s");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BackupLockBusyException("Attesa del backup interrotta");
        }
        try {
            if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                return null;
            Path staged = Files.createTempFile(safeBackupDirectory(), ".pg-restore-source-", ".part");
            restrictPermissions(staged, false);
            try {
                try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!isPostgresqlArchive(staged))
                    throw new IOException("Firma PGDMP assente");
                return staged;
            } catch (Exception failure) {
                Files.deleteIfExists(staged);
                if (failure instanceof IOException io)
                    throw io;
                throw new IOException(failure);
            }
        } finally {
            lock.unlock();
        }
    }

    private static class BackupLockBusyException extends Exception {
        BackupLockBusyException(String message) {
            super(message);
        }
    }

    private RestoreOutcome promote(Path staged, SchemaFingerprint expected,
            ArchiveValidation sourceValidation) throws Exception {
        if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS))
            return RestoreOutcome.rejected("BACKUP_IN_PROGRESS",
                    "Backup in corso: lock non ottenuto entro " + LOCK_WAIT_SECONDS + "s");
        // Database-level advisory lock: serializes restore even across multiple JVM instances.
        java.sql.Connection advisoryConn = null;
        try {
            advisoryConn = dataSource.getConnection();
            try (var stmt = advisoryConn.createStatement()) {
                try (var rs = stmt.executeQuery("SELECT pg_try_advisory_lock(9876543210)")) {
                    if (!(rs.next() && rs.getBoolean(1)))
                        return RestoreOutcome.rejected("DATABASE_BUSY",
                                "Ripristino già in corso su un'altra istanza.");
                }
            }
        } catch (Exception e) {
            try {
                if (advisoryConn != null)
                    advisoryConn.close();
            } catch (Exception ignored) {
            }
            return RestoreOutcome.rejected("DATABASE_BUSY",
                    "Impossibile acquisire il lock di ripristino: " + e.getMessage());
        }
        String snapshotName = null;
        Path snapshotFile = null;
        boolean restoreAttempted = false;
        try {
            try {
                snapshotName = (String) performBackup("prerestore", dumpTimeoutSeconds).get("filename");
                snapshotFile = resolveBackup(snapshotName);
                ArchiveValidation snapshotValidation = snapshotFile == null ? null
                        : validateArchive(snapshotFile, expected.tables());
                if (snapshotValidation == null || !snapshotValidation.compatible())
                    return RestoreOutcome.rejected(RestoreOutcome.NO_ROLLBACK_SNAPSHOT,
                            "Snapshot assente oppure indice strutturale differente: ripristino annullato");
                if (!sourceValidation.structure().equals(snapshotValidation.structure()))
                    return RestoreOutcome.rejected(RestoreOutcome.INCOMPATIBLE_DATABASE,
                            "Il DDL completo del dump differisce dallo schema applicativo corrente");
            } catch (Exception snapshotFailure) {
                return RestoreOutcome.rejected(RestoreOutcome.NO_ROLLBACK_SNAPSHOT,
                        "Snapshot di sicurezza non creato: " + snapshotFailure.getMessage());
            }

            dataSource.flush(AgroalDataSource.FlushMode.IDLE);
            restoreAttempted = true;
            Execution restored = restoreArchive(staged);
            if (restored.exitCode() != 0)
                return verifyOrRecoverRollback(expected, snapshotFile, snapshotName,
                        "pg_restore: " + oneLine(restored.output()));

            SchemaFingerprint actual = readSchemaFingerprint();
            if (!expected.equals(actual))
                return recoverSnapshot(expected, snapshotFile, snapshotName,
                        "Il dump non ricrea lo schema applicativo atteso: " + expected.difference(actual));

            settleAfterCommittedRestore();
            logger.info("DB ripristinato da: " + staged.getFileName());
            return RestoreOutcome.restored();
        } catch (Exception failure) {
            if (restoreAttempted)
                return verifyOrRecoverRollback(expected, snapshotFile, snapshotName,
                        String.valueOf(failure.getMessage()));
            return RestoreOutcome.rejected(RestoreOutcome.PROMOTION_IO_ERROR,
                    String.valueOf(failure.getMessage()));
        } finally {
            if (advisoryConn != null) {
                try (var stmt = advisoryConn.createStatement()) {
                    stmt.execute("SELECT pg_advisory_unlock(9876543210)");
                } catch (Exception ignored) {
                }
                try {
                    advisoryConn.close();
                } catch (Exception ignored) {
                }
            }
            lock.unlock();
        }
    }

    private Execution restoreArchive(Path archive) throws Exception {
        return run(List.of(pgRestore.toString(), "--clean", "--if-exists",
                "--single-transaction", "--no-owner", "--no-privileges", "--no-password",
                "--schema=public", "--strict-names",
                "--host=" + host, "--port=" + port,
                "--username=" + username.orElseThrow(), "--dbname=" + database,
                archive.toString()), dumpTimeoutSeconds, OUTPUT_CAP,
                Map.of("PGOPTIONS", "-c lock_timeout=5s"));
    }

    private RestoreOutcome verifyOrRecoverRollback(SchemaFingerprint expected, Path snapshotFile,
            String snapshotName, String cause) {
        try {
            if (waitForRollbackQuiescence(expected)) {
                settleAfterCommittedRestore();
                logger.warning("Ripristino annullato atomicamente, database invariato: " + cause);
                return RestoreOutcome.rolledBack(RestoreOutcome.PROMOTION_IO_ERROR, cause);
            }
        } catch (Exception verificationFailure) {
            cause += " | verifica rollback fallita: " + verificationFailure.getMessage();
        }
        return recoverSnapshot(expected, snapshotFile, snapshotName, cause);
    }

    private boolean waitForRollbackQuiescence(SchemaFingerprint expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Exception lastFailure = null;
        do {
            try {
                return expected.equals(readSchemaFingerprint());
            } catch (SQLException lockedOrRollingBack) {
                lastFailure = lockedOrRollingBack;
                if (System.nanoTime() >= deadline)
                    break;
                Thread.sleep(500);
            }
        } while (System.nanoTime() < deadline);
        throw lastFailure != null ? lastFailure
                : new SQLException("Verifica rollback PostgreSQL non conclusa entro 30s");
    }

    private RestoreOutcome recoverSnapshot(SchemaFingerprint expected, Path snapshotFile,
            String snapshotName, String cause) {
        try {
            if (snapshotFile == null)
                throw new IOException("Snapshot di recupero assente");
            Execution recovery = restoreArchive(snapshotFile);
            if (recovery.exitCode() != 0)
                throw new IOException("pg_restore snapshot: " + oneLine(recovery.output()));
            SchemaFingerprint recovered = readSchemaFingerprint();
            if (!expected.equals(recovered))
                throw new IOException("Snapshot ripristinato con " + expected.difference(recovered));
            settleAfterCommittedRestore();
            logger.warning("Ripristino annullato, snapshot precedente recuperato: " + cause);
            return RestoreOutcome.rolledBack(RestoreOutcome.PROMOTION_IO_ERROR, cause);
        } catch (Exception disaster) {
            logger.log(Level.SEVERE, "Recupero snapshot non riuscito: intervento manuale necessario", disaster);
            return RestoreOutcome.inconsistent(RestoreOutcome.PROMOTION_IO_ERROR,
                    cause + " | recupero fallito: " + disaster.getMessage(), snapshotName);
        }
    }

    private void settleAfterCommittedRestore() {
        try {
            dataSource.flush(AgroalDataSource.FlushMode.ALL);
        } catch (RuntimeException cacheFailure) {
            // PostgreSQL commit is complete: a cache error does not change the DB outcome.
            logger.log(Level.SEVERE, "Database ripristinato ma refresh pool/cache non riuscito", cacheFailure);
        }
        try {
            repo.invalidateRuntimeCaches();
        } catch (RuntimeException cacheFailure) {
            logger.log(Level.SEVERE, "Database ripristinato ma cache runtime non invalidata", cacheFailure);
        }
    }

    private ArchiveValidation validateArchive(Path archive, Set<String> expectedTables) throws Exception {
        Execution index = run(List.of(pgRestore.toString(), "--list", "--schema=public", archive.toString()),
                dumpTimeoutSeconds, ARCHIVE_INDEX_OUTPUT_CAP);
        if (index.exitCode() != 0 || index.truncated())
            return new ArchiveValidation(false, false,
                    index.truncated() ? "Indice oltre il limite di sicurezza" : oneLine(index.output()), List.of());
        Set<String> archivedTables = archiveTables(index.output());
        if (archivedTables.isEmpty())
            return new ArchiveValidation(true, false, "L'archivio non contiene tabelle", List.of());
        if (!archivedTables.equals(expectedTables)) {
            Set<String> missing = new TreeSet<>(expectedTables);
            missing.removeAll(archivedTables);
            Set<String> extra = new TreeSet<>(archivedTables);
            extra.removeAll(expectedTables);
            return new ArchiveValidation(true, false,
                    "Tabelle non compatibili; mancanti=" + missing + ", extra=" + extra, List.of());
        }
        String nullDevice = isWindows() ? "NUL" : "/dev/null";
        Execution payload = run(List.of(pgRestore.toString(), "--file=" + nullDevice,
                "--schema=public", "--strict-names", "--no-owner", "--no-privileges",
                archive.toString()), dumpTimeoutSeconds, OUTPUT_CAP);
        if (payload.exitCode() != 0 || payload.truncated())
            return new ArchiveValidation(true, false,
                    "Payload archivio non verificabile: " + oneLine(payload.output()), List.of());
        List<String> structure = new ArrayList<>(archiveStructure(index.output()));
        try {
            structure.add("SCHEMA_SHA256 " + schemaDigest(archive));
        } catch (Exception failure) {
            return new ArchiveValidation(true, false,
                    "DDL archivio non verificabile: " + oneLine(failure.getMessage()), List.of());
        }
        structure.sort(String::compareTo);
        return new ArchiveValidation(true, true, null, List.copyOf(structure));
    }

    /**
     * Compares generated DDL before touching the live database. The TOC alone proves object
     * identities, not view/function bodies or attributes such as UNLOGGED or GENERATED. Two
     * dumps created by the same tool from the same schema must produce the same schema-only
     * stream; data and sequence values do not participate.
     */
    private String schemaDigest(Path archive) throws Exception {
        Path sql = Files.createTempFile(safeBackupDirectory(), ".pg-schema-preflight-", ".sql");
        restrictPermissions(sql, false);
        try {
            Execution rendered = run(List.of(pgRestore.toString(), "--schema-only",
                    "--schema=public", "--strict-names", "--no-owner", "--no-privileges",
                    "--file=" + sql, archive.toString()), dumpTimeoutSeconds, OUTPUT_CAP);
            if (rendered.exitCode() != 0 || rendered.truncated())
                throw new IOException("pg_restore schema-only: " + oneLine(rendered.output()));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<ByteRange> volatileLines = restrictionTokenRanges(sql);
            try (InputStream input = Files.newInputStream(sql, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                long position = 0;
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read == 0)
                        continue;
                    int cursor = 0;
                    for (ByteRange range : volatileLines) {
                        long overlapStart = Math.max(position, range.start());
                        long overlapEnd = Math.min(position + read, range.end());
                        if (overlapStart >= overlapEnd)
                            continue;
                        int relativeStart = (int) (overlapStart - position);
                        int relativeEnd = (int) (overlapEnd - position);
                        if (relativeStart > cursor)
                            digest.update(buffer, cursor, relativeStart - cursor);
                        cursor = Math.max(cursor, relativeEnd);
                    }
                    if (cursor < read)
                        digest.update(buffer, cursor, read - cursor);
                    position += read;
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } finally {
            Files.deleteIfExists(sql);
        }
    }

    /**
     * Finds exact byte offsets of only pg_dump's random external meta-commands.
     */
    private static List<ByteRange> restrictionTokenRanges(Path sql) throws IOException {
        byte[] restrict = "\\restrict ".getBytes(StandardCharsets.US_ASCII);
        byte[] unrestrict = "\\unrestrict ".getBytes(StandardCharsets.US_ASCII);
        ByteRange firstRestrict = null;
        ByteRange lastUnrestrict = null;
        long offset = 0;
        long lineStart = 0;
        byte[] prefix = new byte[Math.max(restrict.length, unrestrict.length)];
        int prefixLength = 0;
        try (InputStream input = Files.newInputStream(sql, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                for (int index = 0; index < read; index++) {
                    byte value = buffer[index];
                    if (prefixLength < prefix.length && value != '\r' && value != '\n')
                        prefix[prefixLength++] = value;
                    offset++;
                    if (value == '\n') {
                        ByteRange range = new ByteRange(lineStart, offset);
                        if (firstRestrict == null && startsWith(prefix, prefixLength, restrict))
                            firstRestrict = range;
                        if (startsWith(prefix, prefixLength, unrestrict))
                            lastUnrestrict = range;
                        lineStart = offset;
                        prefixLength = 0;
                    }
                }
            }
        }
        if (lineStart < offset) {
            ByteRange range = new ByteRange(lineStart, offset);
            if (firstRestrict == null && startsWith(prefix, prefixLength, restrict))
                firstRestrict = range;
            if (startsWith(prefix, prefixLength, unrestrict))
                lastUnrestrict = range;
        }
        List<ByteRange> ranges = new ArrayList<>(2);
        if (firstRestrict != null)
            ranges.add(firstRestrict);
        if (lastUnrestrict != null && !lastUnrestrict.equals(firstRestrict))
            ranges.add(lastUnrestrict);
        ranges.sort(Comparator.comparingLong(ByteRange::start));
        return ranges;
    }

    private static boolean startsWith(byte[] actual, int actualLength, byte[] prefix) {
        if (actualLength < prefix.length)
            return false;
        for (int index = 0; index < prefix.length; index++)
            if (actual[index] != prefix[index])
                return false;
        return true;
    }

    private record ByteRange(long start, long end) {
    }

    private static List<String> archiveStructure(String index) {
        Pattern entry = Pattern.compile("^\\d+;\\s+\\d+\\s+\\d+\\s+(.*)$");
        List<String> structure = new ArrayList<>();
        for (String line : index.lines().toList()) {
            Matcher matcher = entry.matcher(line.trim());
            if (!matcher.matches())
                continue;
            String payload = matcher.group(1).trim();
            int owner = payload.lastIndexOf(' ');
            structure.add(owner > 0 ? payload.substring(0, owner) : payload);
        }
        structure.sort(String::compareTo);
        return List.copyOf(structure);
    }

    private static Set<String> archiveTables(String index) {
        Pattern tableEntry = Pattern.compile(
                "(?m)^\\d+;\\s+\\d+\\s+\\d+\\s+TABLE\\s+public\\s+([A-Za-z0-9_]+)\\s+.*$");
        Set<String> tables = new TreeSet<>();
        Matcher matcher = tableEntry.matcher(index);
        while (matcher.find())
            tables.add(matcher.group(1));
        return tables;
    }

    /**
     * Structural fingerprint only: data may and should differ between backup and live database.
     */
    private SchemaFingerprint readSchemaFingerprint() throws SQLException {
        Map<String, List<String>> columns = new TreeMap<>();
        Set<String> constraints = new TreeSet<>();
        Set<String> indexes = new TreeSet<>();
        Set<String> sequences = new TreeSet<>();
        Set<String> functions = new TreeSet<>();
        Set<String> views = new TreeSet<>();
        Set<String> triggers = new TreeSet<>();
        Set<String> types = new TreeSet<>();
        Set<String> policies = new TreeSet<>();
        Set<String> flyway = new TreeSet<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '5s'");
                statement.execute("SET LOCAL statement_timeout = '30s'");
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT table_name, column_name, data_type, udt_name, is_nullable,
                                   COALESCE(column_default, '')
                            FROM information_schema.columns
                            WHERE table_schema = 'public'
                              AND EXISTS (
                                  SELECT 1 FROM pg_class c
                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                  WHERE n.nspname = 'public'
                                    AND c.relname = information_schema.columns.table_name
                                    AND c.relkind IN ('r', 'p')
                              )
                            ORDER BY table_name, ordinal_position
                            """)) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    columns.computeIfAbsent(table, ignored -> new ArrayList<>()).add(
                            rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4)
                                    + "|" + rs.getString(5) + "|" + rs.getString(6));
                }
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT c.conrelid::regclass::text, c.conname, c.contype,
                                   pg_get_constraintdef(c.oid, true)
                            FROM pg_constraint c
                            JOIN pg_namespace n ON n.oid = c.connamespace
                            WHERE n.nspname = 'public'
                            ORDER BY 1, 2
                            """)) {
                while (rs.next())
                    constraints.add(
                            rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT tablename, indexname, indexdef
                            FROM pg_indexes WHERE schemaname = 'public' ORDER BY 1, 2
                            """)) {
                while (rs.next())
                    indexes.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT sequence_name, data_type, start_value, minimum_value, maximum_value,
                                   increment, cycle_option
                            FROM information_schema.sequences
                            WHERE sequence_schema = 'public' ORDER BY sequence_name
                            """)) {
                while (rs.next())
                    sequences.add(String.join("|", rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT p.oid::regprocedure::text, p.prokind, pg_get_functiondef(p.oid)
                            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                            WHERE n.nspname = 'public' AND p.prokind IN ('f', 'p') ORDER BY 1
                            """)) {
                while (rs.next())
                    functions.add(rs.getString(1) + "|" + rs.getString(2)
                            + "|" + rs.getString(3));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT 'view', viewname, definition FROM pg_views
                            WHERE schemaname = 'public'
                            UNION ALL
                            SELECT 'matview', matviewname, definition FROM pg_matviews
                            WHERE schemaname = 'public' ORDER BY 1, 2
                            """)) {
                while (rs.next())
                    views.add(rs.getString(1) + "|" + rs.getString(2)
                            + "|" + rs.getString(3));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT tablename, rulename, definition FROM pg_rules
                            WHERE schemaname = 'public' ORDER BY 1, 2
                            """)) {
                while (rs.next())
                    views.add("rule|" + rs.getString(1) + "|" + rs.getString(2)
                            + "|" + rs.getString(3));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT c.relname, t.tgname, pg_get_triggerdef(t.oid, true)
                            FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                            JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = 'public' AND NOT t.tgisinternal ORDER BY 1, 2
                            """)) {
                while (rs.next())
                    triggers.add(rs.getString(1) + "|" + rs.getString(2)
                            + "|" + rs.getString(3));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT t.typname, t.typtype, t.typcategory, format_type(t.oid, NULL),
                                   COALESCE(pg_get_expr(t.typdefaultbin, 0), ''),
                                   COALESCE(string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder), '')
                            FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                            LEFT JOIN pg_enum e ON e.enumtypid = t.oid
                            WHERE n.nspname = 'public' AND t.typrelid = 0
                            GROUP BY t.oid, t.typname, t.typtype, t.typcategory, t.typdefaultbin
                            ORDER BY t.typname
                            """)) {
                while (rs.next())
                    types.add(String.join("|", rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("""
                            SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
                                   COALESCE(p.policyname, ''), COALESCE(p.permissive, ''),
                                   COALESCE(p.roles::text, ''), COALESCE(p.cmd, ''),
                                   COALESCE(p.qual, ''), COALESCE(p.with_check, '')
                            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                            LEFT JOIN pg_policies p ON p.schemaname = n.nspname AND p.tablename = c.relname
                            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') ORDER BY 1, 4
                            """)) {
                while (rs.next())
                    policies.add(String.join("|", rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9)));
            }
            if (columns.containsKey("flyway_schema_history")) {
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("""
                                SELECT installed_rank, COALESCE(version, ''), description, type, script,
                                       COALESCE(checksum, 0), success
                                FROM flyway_schema_history ORDER BY installed_rank
                                """)) {
                    while (rs.next())
                        flyway.add(rs.getInt(1) + "|" + rs.getString(2) + "|"
                                + rs.getString(3) + "|" + rs.getString(4) + "|" + rs.getString(5)
                                + "|" + rs.getInt(6) + "|" + rs.getBoolean(7));
                }
            }
            connection.commit();
        }
        return new SchemaFingerprint(columns, constraints, indexes, sequences, functions, views,
                triggers, types, policies, flyway);
    }

    private record ArchiveValidation(boolean readable, boolean compatible, String detail,
            List<String> structure) {
    }

    private record SchemaFingerprint(Map<String, List<String>> columns, Set<String> constraints,
            Set<String> indexes, Set<String> sequences,
            Set<String> functions, Set<String> views, Set<String> triggers,
            Set<String> types, Set<String> policies, Set<String> flyway) {
        Set<String> tables() {
            return columns.keySet();
        }

        String difference(SchemaFingerprint actual) {
            if (!columns.equals(actual.columns))
                return "tabelle o colonne differenti";
            if (!constraints.equals(actual.constraints))
                return "vincoli differenti";
            if (!indexes.equals(actual.indexes))
                return "indici differenti";
            if (!sequences.equals(actual.sequences))
                return "sequence differenti";
            if (!functions.equals(actual.functions))
                return "funzioni o procedure differenti";
            if (!views.equals(actual.views))
                return "view differenti";
            if (!triggers.equals(actual.triggers))
                return "trigger differenti";
            if (!types.equals(actual.types))
                return "tipi differenti";
            if (!policies.equals(actual.policies))
                return "policy RLS differenti";
            if (!java.util.Objects.equals(flyway, actual.flyway))
                return "storia delle migrazioni (Flyway) differente: il backup è precedente o successivo "
                        + "a un aggiornamento dello schema dell'applicazione";
            return "differenza strutturale non identificata";
        }
    }

    private Map<String, Object> describe(Path p) {
        return BackupFileManager.describe(p, BACKUP_NAME);
    }

    private void rotate() {
        BackupFileManager.rotate(Path.of(backupDir).toAbsolutePath().normalize(), BACKUP_NAME,
                autoKeep, otherKeep, autoRetentionDays, otherRetentionDays);
    }

    // ------------------------------------------------------------------ External processes

    private record Execution(int exitCode, String output, boolean truncated) {
    }

    /**
     * @brief Runs a client tool, collecting output and respecting a duration limit.
     * @details A dedicated thread drains the stream: if the child fills the pipe buffer and no
     *          one reads it, it blocks forever and the timeout would never trigger.
     */
    private Execution run(List<String> command, int timeoutSeconds) throws Exception {
        return run(command, timeoutSeconds, OUTPUT_CAP);
    }

    private Execution run(List<String> command, int timeoutSeconds, int outputCap) throws Exception {
        return run(command, timeoutSeconds, outputCap, Map.of());
    }

    private Execution run(List<String> command, int timeoutSeconds, int outputCap,
            Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.toUpperCase().startsWith("PG"));
        builder.environment().putAll(environment);
        if (sslMode != null)
            builder.environment().put("PGSSLMODE", sslMode);
        if (sslRootCert != null)
            builder.environment().put("PGSSLROOTCERT", sslRootCert);
        // --version and pg_restore --list do not contact the DB: they must not receive the secret.
        if (command.stream().anyMatch(argument -> argument.startsWith("--dbname="))) {
            password.filter(p -> !p.isEmpty())
                    .ifPresent(p -> builder.environment().put("PGPASSWORD", p));
        }
        Process process = builder.start();
        // StringBuffer, not StringBuilder: if join times out, the reader thread may still be alive,
        // and we would read while it writes.
        StringBuffer sink = new StringBuffer();
        AtomicBoolean truncated = new AtomicBoolean();
        Thread drain = new Thread(() -> drainInto(process.getInputStream(), sink, outputCap, truncated),
                "pg-client-output");
        drain.setDaemon(true); // Must not keep the JVM alive if the child does not close the stream.
        drain.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS))
                    throw new IOException("Processo client non terminabile: " + command.get(0));
            }
            drain.join(2_000);
            throw new IOException("Comando non terminato entro " + timeoutSeconds + "s: " + command.get(0));
        }
        drain.join(2_000);
        return new Execution(process.exitValue(), sink.toString().trim(), truncated.get());
    }

    /**
     * @brief Reads with a limit: an error repeated in a loop must not consume unbounded memory.
     * @details Uses a Reader rather than block decoding: pg_dump messages are localized, and an
     *          accented character split across two buffers would otherwise become '?'. Continue
     *          reading beyond the limit: stopping would block the child on a full pipe, preventing
     *          the timeout from ever triggering.
     */
    private static void drainInto(InputStream stream, StringBuffer sink, int outputCap,
            AtomicBoolean truncated) {
        try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int room = outputCap - sink.length();
                if (room > 0)
                    sink.append(buffer, 0, Math.min(read, room));
                if (read > room)
                    truncated.set(true);
            }
        } catch (IOException ignored) {
            // The process was terminated: the exit code already describes the outcome.
        }
    }

    /**
     * @brief Runs a short command while ignoring failures. @return output, or null on failure.
     */
    private String runQuietly(String... command) {
        try {
            Execution execution = run(List.of(command), 10);
            return execution.exitCode() == 0 ? execution.output() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
