package org.acme.employeescheduling.rest;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/** Protects the backup admin API without changing the authentication of the rest of the app. */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 200)
public class BackupAdminFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger logger = Logger.getLogger(BackupAdminFilter.class.getName());
    static final String TOKEN_HEADER = "X-Backup-Admin-Token";

    @Inject
    HttpServerRequest serverRequest;

    @ConfigProperty(name = "backup.admin-token")
    Optional<String> configuredToken;

    @ConfigProperty(name = "backup.admin.require-tls-for-remote", defaultValue = "true")
    boolean requireTlsForRemote;

    private static final int MIN_TOKEN_BYTES = 32;
    private static final int MAX_FAILURES_PER_MINUTE = 10;
    private static final int MAX_TRACKED_REMOTES = 4096;
    private static final ConcurrentHashMap<String, FailedAttempts> FAILURES = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext request) {
        String path = requestPath(request);
        if (!("backup".equals(path) || path.startsWith("backup/"))) return;

        String remote = serverRequest.remoteAddress() == null
                ? null : serverRequest.remoteAddress().hostAddress();
        if (requireTlsForRemote && !isLoopback(remote) && !serverRequest.isSSL()) {
            request.abortWith(Response.status(426)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("error", "BACKUP_ADMIN_TLS_REQUIRED"))
                    .build());
            return;
        }

        String expected = configuredToken.map(String::trim)
                .filter(value -> value.getBytes(StandardCharsets.UTF_8).length >= MIN_TOKEN_BYTES)
                .orElse(null);
        if (expected == null) {
            request.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("error", "BACKUP_ADMIN_TOKEN_NOT_CONFIGURED"))
                    .build());
            return;
        }
        // A valid credential must always be able to unblock the administrator: applying the
        // rate limit before the comparison would let an attacker lock out the correct token
        // too, especially when many clients share a reverse proxy.
        if (tokenEquals(expected, request.getHeaderString(TOKEN_HEADER))) {
            FAILURES.remove(remoteKey(remote));
            return;
        }
        if (tooManyFailures(remote)) {
            request.abortWith(Response.status(429)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .header("Retry-After", "60")
                    .entity(Map.of("error", "BACKUP_ADMIN_RATE_LIMITED"))
                    .build());
            return;
        }
        recordFailure(remote);
        logger.warning("BACKUP_AUDIT action=authentication result=denied remote=" + remoteKey(remote));
        request.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("WWW-Authenticate", "BackupToken")
                .header("Cache-Control", "no-store")
                .entity(Map.of("error", "BACKUP_ADMIN_AUTH_REQUIRED"))
                .build());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = requestPath(request);
        if (!("backup".equals(path) || path.startsWith("backup/"))) return;
        response.getHeaders().putSingle("Cache-Control", "no-store, private");
        response.getHeaders().putSingle("Vary", TOKEN_HEADER);
    }

    private static String requestPath(ContainerRequestContext request) {
        return request.getUriInfo().getPathSegments().stream()
                .map(segment -> segment.getPath())
                .collect(java.util.stream.Collectors.joining("/"));
    }

    static String normalizePath(String path) {
        if (path == null) return "";
        int firstNonSlash = 0;
        while (firstNonSlash < path.length() && path.charAt(firstNonSlash) == '/') firstNonSlash++;
        return path.substring(firstNonSlash);
    }

    static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) return false;
        try {
            int scope = host.indexOf('%');
            String address = scope >= 0 ? host.substring(0, scope) : host;
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean tokenEquals(String expected, String provided) {
        if (provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean tooManyFailures(String remote) {
        FailedAttempts attempts = FAILURES.get(remoteKey(remote));
        if (attempts == null) return false;
        if (System.currentTimeMillis() - attempts.windowStart() >= 60_000L) {
            FAILURES.remove(remoteKey(remote), attempts);
            return false;
        }
        return attempts.count() >= MAX_FAILURES_PER_MINUTE;
    }

    private static void recordFailure(String remote) {
        long now = System.currentTimeMillis();
        if (FAILURES.size() >= MAX_TRACKED_REMOTES) {
            FAILURES.entrySet().removeIf(entry -> now - entry.getValue().windowStart() >= 60_000L);
            // Fail-safe memory limit: an arbitrary, by now less useful entry is dropped.
            if (FAILURES.size() >= MAX_TRACKED_REMOTES) {
                FAILURES.keySet().stream().findAny().ifPresent(FAILURES::remove);
            }
        }
        FAILURES.compute(remoteKey(remote), (key, previous) ->
                previous == null || now - previous.windowStart() >= 60_000L
                        ? new FailedAttempts(1, now)
                        : new FailedAttempts(previous.count() + 1, previous.windowStart()));
    }

    private static String remoteKey(String remote) {
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private record FailedAttempts(int count, long windowStart) { }
}
