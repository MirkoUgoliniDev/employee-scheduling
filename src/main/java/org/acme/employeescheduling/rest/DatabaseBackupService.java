package org.acme.employeescheduling.rest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Backup infrastructure selected by the Quarkus profile.
 *
 * <p>The domain does not know the engine: SQLite uses integrated backup/restore
 * ({@code VACUUM INTO} and Online Backup API), while PostgreSQL invokes {@code pg_dump} in custom
 * format. Both produce one file and the same response shape, so the UI is identical.</p>
 *
 * <p>Capabilities differ and must be declared rather than assumed: PostgreSQL exposes restore
 * only when {@code pg_restore} is available beside {@code pg_dump}; {@code getSettings()} reports
 * this through {@code restoreSupported}.</p>
 */
public interface DatabaseBackupService {
    boolean isAvailable();

    /**
     * @brief Rotation settings plus engine capabilities.
     * @details In addition to the five numeric keys, carries {@code restoreSupported}: without it,
     *          the UI would show a Restore button even when the required tools are missing.
     */
    Map<String, Object> getSettings();
    void saveSettings(int interval, int autoDays, int otherDays, int autoKeep, int otherKeep) throws Exception;
    Map<String, Object> performBackup(String tag) throws Exception;

    /**
     * @brief Snapshot taken before an operation that rewrites shifts in bulk.
     * @details Returns an outcome rather than a boolean for the same reason as
     *          {@link #restore(Path)}: the caller must distinguish a missing prerequisite, which
     *          no retry will fix, from a backup already running, which clears on its own. Callers
     *          <b>do</b> refuse to write when this is not {@link SafetyBackupOutcome#OK} — the
     *          contract is fail-closed, not best effort.
     */
    SafetyBackupOutcome safetyBackup(String tag);
    List<Map<String, Object>> listBackups();
    Path resolveBackup(String filename);
    boolean delete(Path backupFile) throws Exception;

    /**
     * @brief Restores the database from the specified backup.
     * @details Returns an outcome rather than merely not throwing: the caller must distinguish
     *          "rejected, database intact" from "rolled back, state restored" and "inconsistent,
     *          manual intervention required". Exceptions remain for unexpected failures.
     */
    RestoreOutcome restore(Path backupFile) throws Exception;
}
