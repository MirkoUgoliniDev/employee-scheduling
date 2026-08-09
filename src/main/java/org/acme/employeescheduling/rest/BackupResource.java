package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.inject.Inject;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * @brief Database backup/restore endpoint (Configuration → Backup).
 *
 * @details Delegates to the profile's infrastructure implementation. The service validates the
 *          filename (regex whitelist, no path traversal).
 */
@RolesAllowed("ADMIN")
@jakarta.ws.rs.Path("/backup")
@Produces(MediaType.APPLICATION_JSON)
public class BackupResource {

    private static final Logger logger = Logger.getLogger(BackupResource.class.getName());

    private final DatabaseBackupService backup;

    @Inject
    HttpServerRequest serverRequest;

    @Inject
    public BackupResource(DatabaseBackupService backup) {
        this.backup = backup;
    }

    /** @brief Lists existing backups, newest first. */
    @GET
    @jakarta.ws.rs.Path("/list")
    public Response list() {
        if (!backup.isAvailable()) return unavailable();
        return Response.ok(backup.listBackups()).build();
    }

    /** @brief Runs a manual backup immediately. */
    @POST
    @jakarta.ws.rs.Path("/run")
    public Response run() {
        if (!backup.isAvailable()) return unavailable();
        try {
            Response response = Response.ok(backup.performBackup("manual")).build();
            audit("run", "success", null);
            return response;
        } catch (Exception e) {
            audit("run", "failure", null);
            logger.log(Level.SEVERE, "Errore nel backup manuale", e);
            return Response.serverError().entity(Map.of("error", "BACKUP_FAILED")).build();
        }
    }

    /** @brief Restores the database from the specified backup (saving current state first). */
    @POST
    @jakarta.ws.rs.Path("/restore")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response restore(Map<String, String> body) {
        if (!backup.isAvailable()) return unavailable();
        String filename = body != null ? body.get("filename") : null;
        java.nio.file.Path file = backup.resolveBackup(filename);
        if (file == null)
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_FILENAME")).build();
        try {
            RestoreOutcome outcome = backup.restore(file);
            audit("restore", outcome.status().name(), filename);
            return toResponse(outcome, filename);
        } catch (Exception e) {
            audit("restore", "failure", filename);
            logger.log(Level.SEVERE, "Errore nel ripristino da " + filename, e);
            return Response.serverError().entity(Map.of("error", "RESTORE_FAILED")).build();
        }
    }

    /**
     * @brief Maps the restore outcome to an HTTP status and JSON body.
     * @details The {@code restored} field is always present: the client declares it in its type
     *          and already discriminates by HTTP status. Diagnostic fields are additive, so an
     *          old client continues to work unchanged. The machine code travels under
     *          {@code error}, as throughout the rest of the API; otherwise the frontend's
     *          {@code errorCode()} helper would not find it.
     */
    Response toResponse(RestoreOutcome outcome, String filename) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("restored", outcome.isRestored());
        entity.put("status", outcome.status().name());
        entity.put("filename", filename);
        if (outcome.reason() != null) entity.put("error", outcome.reason());
        if (outcome.detail() != null) entity.put("detail", outcome.detail());
        // Filename only, never the path: do not expose filesystem details even to administrators.
        if (outcome.recoveryFile() != null) entity.put("recoveryFile", outcome.recoveryFile());

        // Integer codes, not Response.Status: in Jakarta REST 3.1 the enum has no 422, and
        // fromStatusCode(422) returns null, which Response.status(StatusType) rejects by throwing.
        int status = switch (outcome.status()) {
            case RESTORED -> 200;
            // Well-formed request with invalid content: rejected by validation.
            case REJECTED -> rejectedStatus(outcome.reason());
            // Promotion failed but was rolled back: the database is back to its previous state.
            case ROLLED_BACK -> 409;
            case INCONSISTENT -> 500;
        };
        if (!outcome.isRestored()) {
            // REJECTED is an expected outcome (invalid file selected by the user), not a failure:
            // at SEVERE level, anyone could flood the log and bury real failures.
            Level level = outcome.status() == RestoreOutcome.Status.INCONSISTENT
                    ? Level.SEVERE : Level.WARNING;
            logger.log(level, "Ripristino da " + filename + " non riuscito: " + outcome.status()
                    + " " + outcome.reason() + " — " + oneLine(outcome.detail()));
        }
        return Response.status(status).entity(entity).build();
    }

    private static int rejectedStatus(String reason) {
        if ("DATABASE_BUSY".equals(reason) || "BACKUP_IN_PROGRESS".equals(reason)) return 503;
        if (RestoreOutcome.PROMOTION_BUSY.equals(reason)) return 409;
        return 422;
    }

    /** @brief Flattens the detail to one line so it cannot split the log. */
    private static String oneLine(String detail) {
        return detail == null ? null : detail.replace('\n', ' ').replace('\r', ' ');
    }

    /** @brief Downloads a backup file. */
    @GET
    @jakarta.ws.rs.Path("/download/{filename}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("filename") String filename) {
        if (!backup.isAvailable()) return unavailable();
        java.nio.file.Path file = backup.resolveBackup(filename);
        if (file == null)
            return Response.status(Response.Status.NOT_FOUND).build();
        try {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            long size = channel.size();
            InputStream input = Channels.newInputStream(channel);
            StreamingOutput stream = output -> {
                try (input) {
                    input.transferTo(output);
                    audit("download", "success", filename);
                } catch (java.io.IOException failure) {
                    audit("download", "failure", filename);
                    throw failure;
                }
            };
            return Response.ok(stream)
                .header("Content-Length", size)
                .header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"")
                .build();
        } catch (Exception e) {
            audit("download", "failure", filename);
            logger.log(Level.SEVERE, "Errore nel download del backup " + filename, e);
            return Response.serverError().build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/settings")
    public Response settings() {
        if (!backup.isAvailable()) return unavailable();
        return Response.ok(backup.getSettings()).build();
    }

    @jakarta.ws.rs.PUT
    @jakarta.ws.rs.Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveSettings(Map<String, Integer> body) {
        if (!backup.isAvailable()) return unavailable();
        if (body == null) return Response.status(Response.Status.BAD_REQUEST).build();
        try {
            backup.saveSettings(body.getOrDefault("intervalMinutes", 0),
                    body.getOrDefault("autoRetentionDays", 0), body.getOrDefault("otherRetentionDays", 0),
                    body.getOrDefault("autoKeep", 0), body.getOrDefault("otherKeep", 0));
            audit("settings", "success", null);
            return Response.ok(backup.getSettings()).build();
        } catch (IllegalArgumentException e) {
            audit("settings", "rejected", null);
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_SETTINGS")).build();
        } catch (Exception e) {
            audit("settings", "failure", null);
            logger.log(Level.SEVERE, "Errore nel salvataggio delle impostazioni backup", e);
            return Response.serverError().entity(Map.of("error", "SETTINGS_FAILED")).build();
        }
    }

    /** @brief Deletes a backup file. */
    @DELETE
    @jakarta.ws.rs.Path("/{filename}")
    public Response delete(@PathParam("filename") String filename) {
        if (!backup.isAvailable()) return unavailable();
        java.nio.file.Path file = backup.resolveBackup(filename);
        if (file == null)
            return Response.status(Response.Status.NOT_FOUND).build();
        try {
            if (!backup.delete(file)) {
                audit("delete", "not_found", filename);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            audit("delete", "success", filename);
            return Response.ok(Map.of("deleted", true, "filename", filename)).build();
        } catch (Exception e) {
            audit("delete", "failure", filename);
            logger.log(Level.SEVERE, "Errore nella cancellazione del backup " + filename, e);
            return Response.serverError().entity(Map.of("error", "DELETE_FAILED")).build();
        }
    }

    /**
     * @details The reason travels in the body: without it, users see only a generic error and
     *          cannot tell whether `pg_dump` is missing, too old, or deliberately unavailable.
     */
    private Response unavailable() {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("error", "BACKUP_TOOLS_UNAVAILABLE");
        if (backup instanceof PostgresqlBackupService postgresql && postgresql.unavailableReason() != null)
            entity.put("detail", postgresql.unavailableReason());
        return Response.status(Response.Status.NOT_IMPLEMENTED).entity(entity).build();
    }

    private void audit(String action, String result, String filename) {
        String remote = serverRequest != null && serverRequest.remoteAddress() != null
                ? serverRequest.remoteAddress().hostAddress() : "unknown";
        logger.info("BACKUP_AUDIT action=" + action + " result=" + oneLine(result)
                + " remote=" + oneLine(remote)
                + (filename == null ? "" : " filename=" + oneLine(filename)));
    }
}
