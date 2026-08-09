package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

/**
 * Covers the outcome-to-HTTP mapping in {@link BackupResource#toResponse}.
 *
 * <p>Originates from a real defect: 422 was built with {@code Response.Status.fromStatusCode(422)},
 * but the Jakarta REST 3.1 enum has no such constant, so the method returned {@code null} and
 * {@code Response.status(null)} threw. No test could detect it because no implementation yet
 * produces outcomes other than RESTORED: these tests construct outcomes manually precisely so
 * they do not depend on the implementation.</p>
 */
class BackupRestoreOutcomeMappingTest {

    private final BackupResource resource = new BackupResource(null);

    @Test
    void restoredMapsTo200() {
        Response response = resource.toResponse(RestoreOutcome.restored(), "large_data_20260801_101500_manual.db");

        assertEquals(200, response.getStatus());
        Map<?, ?> body = body(response);
        assertEquals(Boolean.TRUE, body.get("restored"));
        assertEquals("RESTORED", body.get("status"));
        assertEquals("large_data_20260801_101500_manual.db", body.get("filename"));
        assertNull(body.get("error"), "un esito riuscito non deve portare codici di errore");
        assertFalse(body.containsKey("recoveryFile"));
    }

    @Test
    void rejectedMapsTo422WithoutThrowing() {
        Response response = resource.toResponse(
                RestoreOutcome.rejected("INTEGRITY_CHECK_FAILED", "pagina 42 corrotta"), "backup.db");

        assertEquals(422, response.getStatus());
        Map<?, ?> body = body(response);
        assertEquals(Boolean.FALSE, body.get("restored"));
        assertEquals("REJECTED", body.get("status"));
        // Under "error", not "reason": this is the key the frontend's errorCode() can read.
        assertEquals("INTEGRITY_CHECK_FAILED", body.get("error"));
        assertEquals("pagina 42 corrotta", body.get("detail"));
        assertFalse(body.containsKey("recoveryFile"), "nulla da recuperare: il database e' intatto");
    }

    @Test
    void busyDatabaseMapsTo503() {
        Response response = resource.toResponse(
                RestoreOutcome.rejected("DATABASE_BUSY", "richieste ancora attive"), "backup.db");
        assertEquals(503, response.getStatus());
    }

    @Test
    void backupInProgressMapsTo503() {
        Response response = resource.toResponse(
                RestoreOutcome.rejected("BACKUP_IN_PROGRESS", "backup in corso"), "backup.db");
        assertEquals(503, response.getStatus());
    }

    @Test
    void promotionBusyMapsTo409() {
        Response response = resource.toResponse(
                RestoreOutcome.rejected("PROMOTION_BUSY", "lock non acquisito"), "backup.db");
        assertEquals(409, response.getStatus());
    }

    @Test
    void rolledBackMapsTo409() {
        Response response = resource.toResponse(
                RestoreOutcome.rolledBack("PROMOTION_IO_ERROR", "disco pieno"), "backup.db");

        assertEquals(409, response.getStatus());
        Map<?, ?> body = body(response);
        assertEquals(Boolean.FALSE, body.get("restored"));
        assertEquals("ROLLED_BACK", body.get("status"));
        assertFalse(body.containsKey("recoveryFile"), "il rollback e' riuscito: niente da recuperare");
    }

    @Test
    void inconsistentMapsTo500AndCarriesTheRecoveryFile() {
        Response response = resource.toResponse(
                RestoreOutcome.inconsistent("PROMOTION_IO_ERROR", "rollback fallito",
                        "large_data_20260801_101500_prerestore.db"), "backup.db");

        assertEquals(500, response.getStatus());
        Map<?, ?> body = body(response);
        assertEquals(Boolean.FALSE, body.get("restored"));
        assertEquals("INCONSISTENT", body.get("status"));
        assertEquals("large_data_20260801_101500_prerestore.db", body.get("recoveryFile"));
    }

    @Test
    void everyStatusIsMappedWithoutThrowing() {
        for (RestoreOutcome.Status status : RestoreOutcome.Status.values()) {
            RestoreOutcome outcome = new RestoreOutcome(status, "CODE", "dettaglio", "file.db");
            Response response = resource.toResponse(outcome, "backup.db");
            assertTrue(response.getStatus() >= 200 && response.getStatus() < 600,
                    "status HTTP non valido per " + status);
        }
    }

    private static Map<?, ?> body(Response response) {
        return (Map<?, ?>) response.getEntity();
    }
}
