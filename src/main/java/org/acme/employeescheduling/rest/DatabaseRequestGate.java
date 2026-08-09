package org.acme.employeescheduling.rest;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Priority;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Keeps a database restore exclusive with respect to REST work.
 *
 * <p>Two independent concerns share this filter, and they must not be confused:</p>
 * <ul>
 *   <li><b>Restore exclusion</b> ({@code GATE}) — engine independent. Every request takes one
 *       permit; {@link #withExclusiveDatabaseAccess} takes them all for the critical section of a
 *       restore, so no REST request can cross it. Background threads (the scheduled backup, the
 *       startup translation sync) take no permit and are not covered.
 *       Both SQLite and PostgreSQL restore use this section. It is engine independent on purpose:
 *       binding it to
 *       {@code serialize-writers}, which PostgreSQL turns off, is what left the restore
 *       unprotected there in the first place, and the PostgreSQL restore will need it.</li>
 *   <li><b>Writer serialization</b> ({@code WRITER_GATE}) — SQLite specific, governed by
 *       {@code app.database.serialize-writers}. SQLite permits a single writer and read-to-write
 *       transaction upgrades otherwise fail with SQLITE_BUSY_SNAPSHOT; PostgreSQL has MVCC and
 *       turns it off.</li>
 * </ul>
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class DatabaseRequestGate implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String PERMITS_PROPERTY = DatabaseRequestGate.class.getName() + ".permits";
    private static final String WRITER_PROPERTY = DatabaseRequestGate.class.getName() + ".writer";
    private static final int ALL_PERMITS = 1_000_000;
    private static final Semaphore GATE = new Semaphore(ALL_PERMITS, true);
    private static final Semaphore WRITER_GATE = new Semaphore(1, true);
    private static volatile boolean serializeWriters = true;
    private static volatile int requestTimeoutSeconds = 180;
    private static volatile int exclusiveTimeoutSeconds = 120;

    @Inject
    @ConfigProperty(name = "app.database.serialize-writers", defaultValue = "true")
    boolean configuredSerialization;

    @Inject
    @ConfigProperty(name = "app.database.gate.request-timeout-seconds", defaultValue = "180")
    int configuredRequestTimeout;

    @Inject
    @ConfigProperty(name = "app.database.gate.exclusive-timeout-seconds", defaultValue = "120")
    int configuredExclusiveTimeout;

    @PostConstruct
    void configure() {
        serializeWriters = configuredSerialization;
        requestTimeoutSeconds = configuredRequestTimeout;
        exclusiveTimeoutSeconds = configuredExclusiveTimeout;
    }

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        String path = request.getUriInfo().getPathSegments().stream()
                .map(segment -> segment.getPath()).collect(java.util.stream.Collectors.joining("/"));
        boolean restore = "POST".equalsIgnoreCase(request.getMethod()) && "backup/restore".equals(path);
        // The restore takes no shared permit: it acquires the whole gate itself, and only around
        // the phases that touch the live database. Taking one here would deadlock against that.
        if (!restore) {
            acquire(GATE, 1, requestTimeoutSeconds);
            request.setProperty(PERMITS_PROPERTY, 1);
        }
        if (!serializeWriters) return;
        if (!restore && isDatabaseMutation(request.getMethod(), path)) {
            acquire(WRITER_GATE, 1, requestTimeoutSeconds);
            request.setProperty(WRITER_PROPERTY, true);
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
        Object value = request.getProperty(PERMITS_PROPERTY);
        if (!(value instanceof Integer permits)) return;
        if (Boolean.TRUE.equals(request.getProperty(WRITER_PROPERTY))) WRITER_GATE.release();
        GATE.release(permits);
        request.removeProperty(PERMITS_PROPERTY);
        request.removeProperty(WRITER_PROPERTY);
    }

    /**
     * Acquires with a bounded wait, never indefinitely.
     *
     * <p>The reason is not courtesy toward the client. A lost permit — for example, an
     * {@code Error} that skips response filters — would make exclusive acquisition, which asks
     * for exactly {@code ALL_PERMITS}, impossible <b>forever</b>; with a fair semaphore, that
     * queued request then blocks all subsequent ones. Without a timeout, the symptom is an
     * application silently dead until restart.</p>
     */
    private static void acquire(Semaphore semaphore, int permits, int timeoutSeconds) {
        if (!tryAcquire(semaphore, permits, timeoutSeconds))
            throw new WebApplicationException("Database busy: gate not acquired within "
                    + timeoutSeconds + "s", jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE);
    }

    private static boolean tryAcquire(Semaphore semaphore, int permits, int timeoutSeconds) {
        try {
            return semaphore.tryAcquire(permits, timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WebApplicationException("Request interrupted while waiting for database maintenance",
                    jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE);
        }
    }

    /** @brief Available permits: lets tests detect a lost permit. */
    static int availablePermits() {
        return GATE.availablePermits();
    }

    /**
     * Runs a restore critical section exclusively against all REST traffic.
     *
     * <p>Deliberately <b>not</b> governed by {@code serialize-writers}: that property concerns
     * SQLite writer serialization, whereas restore exclusion is required for every engine.
     * On PostgreSQL, where it is {@code false}, tying them together would leave promotion
     * completely unprotected.</p>
     *
     * <p>Besides REST traffic, it also acquires {@code WRITER_GATE}: short external writes
     * registered with {@link #withWriterPermit(Runnable)} (currently email auditing after SMTP)
     * cannot cross maintenance. Any future scheduled writers must use the same helper; scheduled
     * backups are already serialized by service locks.</p>
     *
     * <p>The gate is {@code static}, so it applies to this JVM: a multi-instance deployment also
     * requires a database-side lock.</p>
     *
     * @throws GateBusyException if exclusivity is not obtained within the configured limit; the
     *         caller must map it to an outcome, not to a longer wait
     */
    static <T> T withExclusiveDatabaseAccess(java.util.concurrent.Callable<T> criticalSection)
            throws Exception {
        int timeout = exclusiveTimeoutSeconds;
        if (!tryAcquire(GATE, ALL_PERMITS, timeout))
            throw new GateBusyException("Esclusiva non ottenuta entro " + timeout + "s: "
                    + GATE.availablePermits() + " permessi liberi su " + ALL_PERMITS);
        if (!tryAcquire(WRITER_GATE, 1, timeout)) {
            GATE.release(ALL_PERMITS);
            throw new GateBusyException("Writer esterno non fermato entro " + timeout + "s");
        }
        try {
            return criticalSection.call();
        } finally {
            WRITER_GATE.release();
            GATE.release(ALL_PERMITS);
        }
    }

    /** @brief Exclusivity was not obtained within the wait limit. */
    static class GateBusyException extends Exception {
        GateBusyException(String message) {
            super(message);
        }
    }

    /**
     * Serializes a short SQLite write performed by an endpoint excluded from the general writer
     * gate (for example, auditing after an SMTP send). Slow external work must remain outside
     * this callback.
     */
    static void withWriterPermit(Runnable databaseMutation) {
        // PostgreSQL must honor it as well: serializeWriters governs normal REST requests, while
        // this helper covers writers that continue after the slow request has ended.
        acquire(WRITER_GATE, 1, requestTimeoutSeconds);
        try {
            databaseMutation.run();
        } finally {
            WRITER_GATE.release();
        }
    }

    private static boolean isDatabaseMutation(String method, String path) {
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) return false;
        // These endpoints either do CPU/network/file work only, or perform a single atomic
        // audit upsert after the slow external operation; holding SQLite's writer gate for
        // their full duration would unnecessarily freeze CRUD.
        return !(path.startsWith("schedules") || path.startsWith("system-info/")
                || path.equals("backup/run") || path.equals("backup/settings")
                || path.startsWith("backup/download/") || path.matches("backup/[^/]+")
                || path.equals("email/send-shifts") || path.equals("email/settings/test"));
    }
}
