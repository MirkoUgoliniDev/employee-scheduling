package org.acme.employeescheduling.rest;

/**
 * @brief Why the pre-operation safety backup did or did not happen.
 *
 * @details
 * The three callers refuse to write when the safety backup fails, which is the right call — they
 * rewrite shifts in bulk, and without a snapshot there is nothing to go back to. What was wrong
 * was collapsing every failure into one {@code 503 SAFETY_BACKUP_FAILED}: the user could not tell
 * "your installation is missing a prerequisite and this will never work" from "a scheduled dump is
 * running, press Save again in a minute". Those need opposite reactions — one is a call to the
 * system administrator, the other is patience.
 *
 * <p>Following {@link RestoreOutcome}, each failure carries a stable machine code that travels to
 * the frontend as {@code error} and is translated there — never localized text. The generic
 * {@code SAFETY_BACKUP_FAILED} keeps its historical spelling so an existing client keeps working;
 * the two specific codes are additions beside it.</p>
 *
 * <p>SQLite emits only {@link #OK} and {@link #FAILED}: there the safety backup is a
 * {@code VACUUM INTO} with no external tool and no lock to lose, so the other two cannot arise.</p>
 */
public enum SafetyBackupOutcome {

    /** The snapshot was taken; the operation may proceed. */
    OK(null),

    /**
     * PostgreSQL only: {@code pg_dump} is absent, or older than the server it must read.
     *
     * <p>Permanent and about configuration, not load: retrying changes nothing. This is the one
     * that leaves an installation unable to save shifts at all, and the one whose message must
     * name the missing tool rather than talk about backups.</p>
     */
    CLIENT_TOOLS_MISSING("SAFETY_BACKUP_CLIENT_TOOLS_MISSING"),

    /**
     * Another backup held the lock past the wait limit — typically the scheduled automatic dump.
     *
     * <p>Transient: the same request usually succeeds shortly after. It exists as its own outcome
     * so the UI can say "retry" instead of sending the user to look for a broken installation.</p>
     */
    BUSY("SAFETY_BACKUP_BUSY"),

    /** The dump was attempted and failed: disk full, permissions, server error. See the log. */
    FAILED("SAFETY_BACKUP_FAILED");

    private final String errorCode;

    SafetyBackupOutcome(String errorCode) {
        this.errorCode = errorCode;
    }

    public boolean isOk() {
        return this == OK;
    }

    /**
     * @brief The machine code to put in the {@code error} field of the response.
     * @throws IllegalStateException on {@link #OK}, which is not an error and has no code —
     *         failing loudly beats sending {@code "error": null} to the client.
     */
    public String errorCode() {
        if (errorCode == null)
            throw new IllegalStateException("OK is not a failure and carries no error code");
        return errorCode;
    }
}
