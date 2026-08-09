package org.acme.employeescheduling.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkus.scheduler.Scheduled;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.sqlite.SQLiteConnection;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * @brief SQLite database backup and restore (schedule-data protection).
 *
 * @details
 *          - **Correct hot backup**: uses `VACUUM INTO` (atomic and consistent even with WAL
 *          active and application writes); simply copying the file would be risky.
 *          - **Automatic**: at each configured interval; `auto` tag, rotating the latest
 *          `backup.auto.keep` (default 48). Does not use size/mtime, which are unreliable in WAL mode.
 *          - **Pre-operation**: called before destructive operations (apply-template,
 *          save-assignments) with the `preop` tag; rotates the latest `backup.other.keep` per tag.
 *          - **Restore**: first creates a `prerestore` backup, then uses SQLite's Online Backup API
 *          on the open database (compatible with the ORM pool and Windows). Runtime caches are
 *          invalidated and the legacy schema is checked again.
 */
@ApplicationScoped
@UnlessBuildProperty(name = "app.database.kind", stringValue = "postgresql")
public class BackupService implements DatabaseBackupService {

    private static final Logger logger = Logger.getLogger(BackupService.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    /**
     * @brief Backup filename: base_yyyyMMdd_HHmmss[_n]_tag.db (no path traversal).
     */
    private static final Pattern BACKUP_NAME = Pattern
            .compile("^([A-Za-z0-9_-]+)_(\\d{8}_\\d{6})(?:_\\d+)?_([a-z]+)\\.db$");

    @ConfigProperty(name = "demo.db.name")
    String dbName;

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

    @Inject
    DemoDataRepository repo;

    @Inject
    AgroalDataSource dataSource;

    /**
     * Serializes backup, restore, rotation, and settings saves.
     *
     * <p>
     * {@link ReentrantLock} rather than {@code synchronized} because restore needs a
     * <b>timed</b> wait: it acquires this while holding the entire REST gate, and an unlimited
     * wait behind the scheduler's {@code VACUUM INTO} would freeze the application for the full
     * duration of the automatic backup.
     * </p>
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Restore lock-wait limit, to avoid freezing the application behind a backup.
     */
    private static final int LOCK_WAIT_SECONDS = 60;
    private volatile long lastAutomaticRun = 0;
    /**
     * @brief A restore was requested and is waiting for exclusive access.
     * @details Prevents automatic backup from starting while restore is queued: it would acquire
     *          the lock and force restore to wait while the application is already blocked.
     */
    private volatile boolean restorePending = false;
    private volatile int intervalMinutes = 30;
    private volatile int autoRetentionDays;
    private volatile int otherRetentionDays;
    /**
     * Limit on the NUMBER of backups retained per tag (the second rotation criterion, in addition
     * to retention in days).
     */
    private volatile int autoKeep;
    private volatile int otherKeep;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @PostConstruct
    void loadSettings() {
        autoRetentionDays = defaultAutoRetentionDays;
        otherRetentionDays = defaultOtherRetentionDays;
        autoKeep = defaultAutoKeep;
        otherKeep = defaultOtherKeep;
        Path file = Path.of(settingsFile).toAbsolutePath().normalize();
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                p.load(in);
                intervalMinutes = bounded(p.getProperty("intervalMinutes"), 1, 1440, intervalMinutes);
                autoRetentionDays = bounded(p.getProperty("autoRetentionDays"), 1, 3650, autoRetentionDays);
                otherRetentionDays = bounded(p.getProperty("otherRetentionDays"), 1, 3650, otherRetentionDays);
                autoKeep = bounded(p.getProperty("autoKeep"), 1, 100000, autoKeep);
                otherKeep = bounded(p.getProperty("otherKeep"), 1, 100000, otherKeep);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Impossibile leggere le impostazioni backup", e);
            }
        }
        hardenExistingBackups();
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
            throw new IllegalStateException("Permessi directory backup SQLite non bonificabili", failure);
        }
    }

    private static int bounded(String value, int min, int max, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public Map<String, Object> getSettings() {
        return Map.of("restoreSupported", true,
                "intervalMinutes", intervalMinutes,
                "autoRetentionDays", autoRetentionDays,
                "otherRetentionDays", otherRetentionDays,
                "autoKeep", autoKeep,
                "otherKeep", otherKeep);
    }

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

    private String baseName() {
        String file = Path.of(dbName).getFileName().toString();
        String raw = file.endsWith(".db") ? file.substring(0, file.length() - 3) : file;
        String safe = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isBlank() ? "sqlite" : safe;
    }

    /**
     * @brief Performs a consistent backup with VACUUM INTO. @return created-file information.
     */
    public Map<String, Object> performBackup(String tag) throws Exception {
        lock.lock();
        try {
            String safeTag = tag != null && tag.matches("[a-z]{1,20}") ? tag : "manual";
            Path dir = safeBackupDirectory();
            String ts = LocalDateTime.now().format(TS);
            Path target = dir.resolve(baseName() + "_" + ts + "_" + safeTag + ".db");
            for (int n = 1; Files.exists(target); n++) {
                target = dir.resolve(baseName() + "_" + ts + "_" + n + "_" + safeTag + ".db");
            }
            Path staging = dir.resolve(".sqlite-backup-" + UUID.randomUUID() + ".part");
            try {
                // VACUUM INTO requires a nonexistent target; the random hidden name cannot be
                // listed by the API and is published only after verification + fsync.
                String sqlPath = staging.toString().replace('\\', '/').replace("'", "''");
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbName);
                        Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA busy_timeout = 5000");
                    stmt.execute("VACUUM INTO '" + sqlPath + "'");
                }
                restrictPermissions(staging, false);
                if (!sqliteIntegrityOk(staging))
                    throw new SQLException("Snapshot SQLite non integro");
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
     * @brief Pre-destructive-operation backup: best effort; does not block the operation on failure.
     */
    public boolean safetyBackup(String tag) {
        try {
            performBackup(tag);
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Backup di sicurezza non riuscito: operazione annullata", e);
            return false;
        }
    }

    /**
     * @brief Automatic backup scheduled at every configured interval.
     * @details Does not attempt to infer SQLite changes from file metadata: in WAL mode a commit
     *          may touch only the -wal file, and any size/mtime fingerprint can collide.
     *          Retention limits disk usage.
     */
    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledBackup() {
        try {
            // A restore is waiting: do not put a backup ahead of it. Leave lastAutomaticRun
            // unchanged so the skipped backup is retried on the next tick.
            if (restorePending)
                return;
            long now = System.currentTimeMillis();
            if (lastAutomaticRun > 0 && now - lastAutomaticRun < intervalMinutes * 60_000L)
                return;
            Path db = Path.of(dbName);
            if (!Files.exists(db))
                return;
            performBackup("auto");
            // A backup error must not postpone retry for an entire interval.
            lastAutomaticRun = now;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Errore nel backup automatico", e);
        }
    }

    /** @brief Existing backups, newest first. */
    public List<Map<String, Object>> listBackups() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = Path.of(backupDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            return out;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> BACKUP_NAME.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(p -> out.add(describe(p)));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Errore nella lettura dei backup", e);
        }
        return out;
    }

    /**
     * @brief Resolves (and validates) a backup filename inside the backup directory.
     */
    public Path resolveBackup(String filename) {
        if (filename == null || !BACKUP_NAME.matcher(filename).matches())
            return null;
        Path dir = Path.of(backupDir).toAbsolutePath().normalize();
        Path p = dir.resolve(filename).normalize();
        return p.getParent() != null && p.getParent().equals(dir)
                && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? p : null;
    }

    /** @brief Deletes one already-validated backup file. */
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

    /**
     * @brief Restores the DB from a backup. First saves current state (prerestore tag).
     * @details Uses SQLite's native Online Backup API on the open DB: works on Windows even with
     *          persistent ORM connections, without replacing a locked file. Then invalidates
     *          caches and reruns idempotent migrations.
     */
    public RestoreOutcome restore(Path backupFile) throws Exception {
        // Exclusive access is acquired here rather than by the REST filter: this makes the
        // exclusive section the one touching the live database, not the entire request.
        //
        // Nonblocking probe BEFORE closing REST traffic: if a backup is already running, restore
        // would still fail after a 60-second wait — but meanwhile the application would be
        // needlessly stopped for every user. Reject immediately here without affecting anyone.
        //
        // The probe does not acquire the lock, so it cannot introduce the deadlock that the
        // gate -> lock ordering exists to avoid: REST requests calling safetyBackup hold a permit
        // and then acquire this lock, so acquiring it before the gate would be fatal. Staying
        // outside the lock creates a race window (a backup can start immediately after the probe),
        // in which case doRestore's wait applies: unchanged behavior, never worse than before.
        if (lock.isLocked())
            return RestoreOutcome.rejected("BACKUP_IN_PROGRESS",
                    "Backup in corso: riprovare fra qualche istante");
        restorePending = true;
        try {
            return DatabaseRequestGate.withExclusiveDatabaseAccess(() -> doRestore(backupFile));
        } catch (DatabaseRequestGate.GateBusyException busy) {
            return RestoreOutcome.rejected("DATABASE_BUSY", busy.getMessage());
        } finally {
            restorePending = false;
        }
    }

    private RestoreOutcome doRestore(Path backupFile) throws Exception {
        // Limited wait, not `synchronized`: all REST-gate permits are already held here, and
        // queueing without a limit behind the scheduler's VACUUM INTO would block the entire
        // application for the duration of the automatic backup. Do not reverse gate -> lock order:
        // REST requests calling safetyBackup (apply-template, save-assignments) hold a permit then
        // acquire this lock, so acquiring it before the gate would deadlock.
        if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS))
            return RestoreOutcome.rejected("BACKUP_IN_PROGRESS",
                    "Backup in corso: lock non ottenuto entro " + LOCK_WAIT_SECONDS + "s");
        Path staged = null;
        Path snapshotFile = null;
        String snapshotName = null;
        boolean attempted = false;
        Set<String> expectedSchema = null;
        try { // Reentrant: performBackup reacquires the same lock without deadlock.
            Matcher nameMatcher = BACKUP_NAME.matcher(backupFile.getFileName().toString());
            if (!nameMatcher.matches() || !baseName().equals(nameMatcher.group(1)))
                return RestoreOutcome.rejected(RestoreOutcome.INCOMPATIBLE_DATABASE,
                        "Il backup non proviene da questo database");
            expectedSchema = sqliteSchema(Path.of(dbName));
            if (backupFile == null || !Files.isRegularFile(backupFile, LinkOption.NOFOLLOW_LINKS))
                return RestoreOutcome.rejected(RestoreOutcome.NOT_A_DATABASE, "File SQLite non leggibile");
            staged = Files.createTempFile(safeBackupDirectory(), ".sqlite-restore-source-", ".part");
            restrictPermissions(staged, false);
            try (var source = Files.newInputStream(backupFile, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!sqliteIntegrityOk(staged) || !expectedSchema.equals(sqliteSchema(staged)))
                return RestoreOutcome.rejected(RestoreOutcome.INCOMPATIBLE_DATABASE,
                        "Il backup SQLite non e' integro o non contiene lo schema applicativo atteso");

            snapshotName = (String) performBackup("prerestore").get("filename");
            snapshotFile = resolveBackup(snapshotName);
            if (snapshotFile == null || !sqliteIntegrityOk(snapshotFile)
                    || !expectedSchema.equals(sqliteSchema(snapshotFile)))
                return RestoreOutcome.rejected(RestoreOutcome.NO_ROLLBACK_SNAPSHOT,
                        "Snapshot SQLite di sicurezza non verificabile");
            dataSource.flush(AgroalDataSource.FlushMode.IDLE);
            attempted = true;
            restoreSqlite(staged);
            if (!sqliteIntegrityOk(Path.of(dbName)) || !expectedSchema.equals(sqliteSchema(Path.of(dbName))))
                throw new SQLException("SQLite ripristinato ma non conforme allo schema atteso");
            settleAfterCommittedRestore();
            logger.info("DB ripristinato da: " + backupFile.getFileName());
            return RestoreOutcome.restored();
        } catch (Exception failure) {
            if (!attempted)
                return RestoreOutcome.rejected(RestoreOutcome.PROMOTION_IO_ERROR,
                        String.valueOf(failure.getMessage()));
            try {
                if (snapshotFile == null)
                    throw new SQLException("Snapshot SQLite assente");
                restoreSqlite(snapshotFile);
                if (!sqliteIntegrityOk(Path.of(dbName)) || !expectedSchema.equals(sqliteSchema(Path.of(dbName))))
                    throw new SQLException("Verifica SQLite dopo rollback fallita");
                settleAfterCommittedRestore();
                return RestoreOutcome.rolledBack(RestoreOutcome.PROMOTION_IO_ERROR,
                        String.valueOf(failure.getMessage()));
            } catch (Exception rollbackFailure) {
                return RestoreOutcome.inconsistent(RestoreOutcome.PROMOTION_IO_ERROR,
                        failure.getMessage() + " | rollback SQLite fallito: " + rollbackFailure.getMessage(),
                        snapshotName);
            }
        } finally {
            try {
                if (staged != null)
                    Files.deleteIfExists(staged);
            } catch (IOException cleanupFailure) {
                logger.log(Level.WARNING, "File temporaneo restore SQLite non eliminato: " + staged,
                        cleanupFailure);
            } finally {
                lock.unlock();
            }
        }
    }

    private void restoreSqlite(Path source) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbName)) {
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            int result = sqlite.getDatabase().restore("main", source.toString(), null, 100, 50, 100);
            if (result != 0)
                throw new SQLException("SQLite restore failed with code " + result);
        }
    }

    private void settleAfterCommittedRestore() {
        try {
            dataSource.flush(AgroalDataSource.FlushMode.ALL);
        } catch (RuntimeException cacheFailure) {
            // SQLite restore and verification are complete: a pool/cache error must not trigger
            // a second destructive restore of the previous snapshot.
            logger.log(Level.SEVERE, "Database SQLite ripristinato ma refresh pool/cache non riuscito",
                    cacheFailure);
        }
        try {
            repo.invalidateRuntimeCaches();
        } catch (RuntimeException cacheFailure) {
            logger.log(Level.SEVERE, "Database SQLite ripristinato ma cache runtime non invalidata",
                    cacheFailure);
        }
    }

    private Path safeBackupDirectory() throws Exception {
        Path dir = Path.of(backupDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("La directory backup non e' una directory reale: " + dir);
        restrictPermissions(dir, true);
        return dir;
    }

    /**
     * @brief Restricts backup and settings permissions to the owner only. Best effort.
     *
     * @details No longer propagates errors: unsuccessful hardening is information, not a reason
     *          to fail every automatic-backup cycle (this happened on Windows on each scheduled
     *          run, silently except for the log).
     *
     *          On Windows, rewrite the DACL <b>only</b> outside directories already private to the
     *          user. Inside {@code %LOCALAPPDATA%} or home, rewriting would be unnecessary — those
     *          directories are inaccessible to other users — and harmful: replacing the entire ACL
     *          with one entry excludes SYSTEM and Administrators, so the MSI uninstaller, which
     *          runs as SYSTEM, can no longer read permissions ("Error getting file security ...").
     */
    private static void restrictPermissions(Path path, boolean directory) {
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
            // Windows: evaluate the explicit DACL below.
        } catch (IOException e) {
            logger.log(Level.WARNING, "Permessi POSIX non applicati a " + path, e);
            return;
        }
        if (isUserPrivateLocation(path))
            return;
        try {
            var view = Files.getFileAttributeView(path,
                    java.nio.file.attribute.AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                logger.log(Level.WARNING, "Filesystem senza permessi POSIX o ACL: " + path);
                return;
            }
            var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            var entry = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                    .build();
            view.setAcl(List.of(entry));
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING,
                    "Permessi non ristretti su " + path + ": restano quelli ereditati", e);
        }
    }

    /** @brief true if the path is in a directory already private to the user (LOCALAPPDATA or home). */
    private static boolean isUserPrivateLocation(Path path) {
        Path target = path.toAbsolutePath().normalize();
        for (String root : new String[] { System.getenv("LOCALAPPDATA"), System.getProperty("user.home") }) {
            if (root == null || root.isBlank()) continue;
            try {
                if (target.startsWith(Path.of(root).toAbsolutePath().normalize())) return true;
            } catch (RuntimeException ignored) {
                // Unparseable path: continue with the next check.
            }
        }
        return false;
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

    private static boolean sqliteIntegrityOk(Path database) {
        try (Connection connection = DriverManager.getConnection(sqliteReadOnlyUrl(database));
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            try (var result = statement.executeQuery("PRAGMA integrity_check")) {
                if (!(result.next() && "ok".equalsIgnoreCase(result.getString(1)) && !result.next()))
                    return false;
            }
            try (var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                return !violations.next();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Set<String> sqliteSchema(Path database) throws SQLException {
        Set<String> schema = new TreeSet<>();
        boolean hasFlywayHistory = false;
        try (Connection connection = DriverManager.getConnection(sqliteReadOnlyUrl(database));
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            try (var result = statement.executeQuery("""
                    SELECT type, name, tbl_name, coalesce(sql, '') FROM sqlite_schema
                    WHERE type IN ('table', 'index', 'trigger', 'view')
                      AND name NOT LIKE 'sqlite_%'
                    ORDER BY type, name
                    """)) {
                while (result.next()) {
                    if ("table".equals(result.getString(1))
                            && "flyway_schema_history".equals(result.getString(2))) {
                        hasFlywayHistory = true;
                    }
                    schema.add(result.getString(1) + "\u001f" + result.getString(2) + "\u001f"
                            + result.getString(3) + "\u001f" + normalizeSql(result.getString(4)));
                }
            }
            if (hasFlywayHistory) {
                try (var history = statement.executeQuery("""
                        SELECT installed_rank, coalesce(version, ''), description, type, script,
                               coalesce(checksum, 0), success
                        FROM flyway_schema_history ORDER BY installed_rank
                        """)) {
                    while (history.next()) {
                        schema.add("flyway\u001f" + history.getInt(1) + "\u001f" + history.getString(2)
                                + "\u001f" + history.getString(3) + "\u001f" + history.getString(4)
                                + "\u001f" + history.getString(5) + "\u001f" + history.getInt(6)
                                + "\u001f" + history.getBoolean(7));
                    }
                }
            }
        }
        return schema;
    }

    private static String normalizeSql(String sql) {
        return sql == null ? "" : sql.trim().replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sqliteReadOnlyUrl(Path database) {
        return "jdbc:sqlite:file:" + database.toAbsolutePath().normalize().toString().replace('\\', '/') + "?mode=ro";
    }

    private static void publishAtomically(Path staging, Path target) throws Exception {
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(target.getParent());
    }

    private static void syncDirectory(Path directory) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win"))
            return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Directory backup non sincronizzabile: " + directory, failure);
        }
    }

    private void rotate() {
        BackupFileManager.rotate(Path.of(backupDir), BACKUP_NAME, autoKeep, otherKeep,
                autoRetentionDays, otherRetentionDays);
    }

    private Map<String, Object> describe(Path p) {
        return BackupFileManager.describe(p, BACKUP_NAME);
    }

}
