package org.acme.employeescheduling.rest;

/**
 * @brief Restore outcome independent of the database engine.
 *
 * @details Three of four outcomes are not method failures: restore did its work and the database
 *          is in a known healthy state. Only {@code INCONSISTENT} is exceptional, precisely where
 *          structured data (the recovery file) is needed and an exception would carry it poorly.
 *          Exceptions remain for unexpected failures.
 *
 * <p>The {@code reason} codes listed below are only those an implementation actually emits: a
 * vocabulary declared before behavior silently diverges because no compiler verifies it.</p>
 *
 * @param status operation outcome
 * @param reason stable machine code, never localized text (the frontend translates it)
 * @param detail nonlocalized technical detail for logs and diagnostics
 * @param recoveryFile populated only for {@code INCONSISTENT}: filename (not path) for manual recovery
 */
public record RestoreOutcome(Status status, String reason, String detail, String recoveryFile) {

    public enum Status {
        /** The backup was applied: the live database is the restored one. */
        RESTORED,
        /** Rejected by validation: no writes to the live database. */
        REJECTED,
        /** Promotion failed but was rolled back: the database returned to its previous state. */
        ROLLED_BACK,
        /** Promotion and rollback failed: manual intervention using {@link #recoveryFile()} is required. */
        INCONSISTENT
    }

    /** The archive is unreadable or is not a dump of this database. */
    public static final String NOT_A_DATABASE = "NOT_A_DATABASE";
    /** The dump is readable but does not contain this application's complete schema. */
    public static final String INCOMPATIBLE_DATABASE = "INCOMPATIBLE_DATABASE";
    /** The rollback snapshot was not created or cannot be verified: do not promote. */
    public static final String NO_ROLLBACK_SNAPSHOT = "NO_ROLLBACK_SNAPSHOT";
    /** The database was busy: no changes applied. */
    public static final String PROMOTION_BUSY = "PROMOTION_BUSY";
    /** Promotion failed; state depends on {@link Status}. */
    public static final String PROMOTION_IO_ERROR = "PROMOTION_IO_ERROR";

    public static RestoreOutcome restored() {
        return new RestoreOutcome(Status.RESTORED, null, null, null);
    }

    public static RestoreOutcome rejected(String reason, String detail) {
        return new RestoreOutcome(Status.REJECTED, reason, detail, null);
    }

    public static RestoreOutcome rolledBack(String reason, String detail) {
        return new RestoreOutcome(Status.ROLLED_BACK, reason, detail, null);
    }

    public static RestoreOutcome inconsistent(String reason, String detail, String recoveryFile) {
        return new RestoreOutcome(Status.INCONSISTENT, reason, detail, recoveryFile);
    }

    public boolean isRestored() {
        return status == Status.RESTORED;
    }
}
