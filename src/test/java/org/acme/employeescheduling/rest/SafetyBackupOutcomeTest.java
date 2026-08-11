package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * @brief The refusal to write must name a cause the interface can translate.
 *
 * @details The three bulk-rewrite operations refuse to run without a safety snapshot, which is
 *          correct. What was wrong was answering every failure with one code: a missing
 *          {@code pg_dump} is a call to the system administrator, a running backup is thirty
 *          seconds of patience, and the user could not tell them apart.
 *
 *          <p>The test that matters here is the last one. A backend code with no entry in
 *          {@code backendErrors.ts} does not break anything visibly — {@code backendErrorText}
 *          returns null and the caller falls back to a generic message — so the specific wording
 *          this whole change exists to deliver would silently never appear.</p>
 */
class SafetyBackupOutcomeTest {

    private static final Path BACKEND_ERRORS =
            Path.of("frontend", "src", "i18n", "backendErrors.ts");

    @Test
    void okIsNotAFailureAndHasNoCode() {
        assertTrue(SafetyBackupOutcome.OK.isOk());
        assertThrows(IllegalStateException.class, SafetyBackupOutcome.OK::errorCode);
    }

    @Test
    void everyFailureCarriesADistinctCode() {
        Set<String> codes = new HashSet<>();
        for (SafetyBackupOutcome outcome : SafetyBackupOutcome.values()) {
            if (outcome.isOk()) continue;
            assertFalse(outcome.isOk());
            assertTrue(codes.add(outcome.errorCode()),
                    "duplicate error code on " + outcome + ": the frontend maps one message per code");
        }
        assertEquals(SafetyBackupOutcome.values().length - 1, codes.size());
    }

    /**
     * @details Kept spelled exactly as before this change: an older client that only knows
     *          SAFETY_BACKUP_FAILED still shows a sensible message for the generic failure.
     */
    @Test
    void theHistoricalCodeIsPreserved() {
        assertEquals("SAFETY_BACKUP_FAILED", SafetyBackupOutcome.FAILED.errorCode());
    }

    @Test
    void everyCodeIsTranslatableByTheFrontend() throws IOException {
        assertTrue(Files.isRegularFile(BACKEND_ERRORS),
                "backendErrors.ts not found at " + BACKEND_ERRORS.toAbsolutePath());
        String source = Files.readString(BACKEND_ERRORS, StandardCharsets.UTF_8);

        for (SafetyBackupOutcome outcome : SafetyBackupOutcome.values()) {
            if (outcome.isOk()) continue;
            assertTrue(source.contains(outcome.errorCode() + ":"),
                    outcome.errorCode() + " has no entry in backendErrors.ts, so the user would see"
                    + " the caller's generic message instead of the specific one.");
        }
    }
}
