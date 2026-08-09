package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.Test;

class DatabaseRequestGateTest {

    @Test
    void shortExternalWriterUsesTheSameSerializedGate() throws Exception {
        // The flag is static and shared with the running application: Surefire reuses the
        // same JVM (forkCount=1, reuseForks=true), and under the test-postgresql profile a
        // @QuarkusTest sets it to false. Without pinning it here, this test would depend on
        // execution order.
        GateSettings previous = currentSettings();
        configuredGate(true);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> DatabaseRequestGate.withWriterPermit(() -> {
                firstEntered.countDown();
                await(releaseFirst);
            }));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

            var second = executor.submit(() -> DatabaseRequestGate.withWriterPermit(secondEntered::countDown));
            assertFalse(secondEntered.await(Duration.ofMillis(150).toMillis(), TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            restore(previous);
        }
    }

    /**
     * Restore exclusion must hold even with writer serialization disabled: on PostgreSQL
     * {@code app.database.serialize-writers} is false, and coupling the two mechanisms
     * would leave promotion entirely unprotected.
     */
    @Test
    void restoreExclusionHoldsWithWriterSerializationOff() throws Exception {
        assertNormalRequestIsHeldOutDuringExclusiveAccess(false);
    }

    @Test
    void restoreExclusionHoldsWithWriterSerializationOn() throws Exception {
        assertNormalRequestIsHeldOutDuringExclusiveAccess(true);
    }

    @Test
    void restoreExclusionAlsoStopsExternalWriterWithSerializationOff() throws Exception {
        GateSettings previous = currentSettings();
        CountDownLatch insideExclusive = new CountDownLatch(1);
        CountDownLatch releaseExclusive = new CountDownLatch(1);
        CountDownLatch writerEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            configuredGate(false);
            var exclusive = executor.submit(() -> DatabaseRequestGate.withExclusiveDatabaseAccess(() -> {
                insideExclusive.countDown();
                await(releaseExclusive);
                return null;
            }));
            assertTrue(insideExclusive.await(2, TimeUnit.SECONDS));

            var writer = executor.submit(() -> DatabaseRequestGate.withWriterPermit(writerEntered::countDown));
            assertFalse(writerEntered.await(200, TimeUnit.MILLISECONDS),
                    "un writer esterno e' entrato durante il restore");

            releaseExclusive.countDown();
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));
            exclusive.get(2, TimeUnit.SECONDS);
            writer.get(2, TimeUnit.SECONDS);
        } finally {
            releaseExclusive.countDown();
            executor.shutdownNow();
            restore(previous);
        }
    }

    private void assertNormalRequestIsHeldOutDuringExclusiveAccess(boolean serializeWriters) throws Exception {
        GateSettings previous = currentSettings();
        int permitsBefore = DatabaseRequestGate.availablePermits();
        CountDownLatch insideExclusive = new CountDownLatch(1);
        CountDownLatch releaseExclusive = new CountDownLatch(1);
        CountDownLatch requestAdmitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            DatabaseRequestGate gate = configuredGate(serializeWriters);
            var exclusive = executor.submit(() -> DatabaseRequestGate.withExclusiveDatabaseAccess(() -> {
                insideExclusive.countDown();
                await(releaseExclusive);
                return null;
            }));
            assertTrue(insideExclusive.await(2, TimeUnit.SECONDS), "sezione esclusiva non entrata");

            ContainerRequestContext normal = request("GET", "employees");
            var blocked = executor.submit(() -> {
                gate.filter(normal);
                requestAdmitted.countDown();
                gate.filter(normal, null);
                return null;
            });
            assertFalse(requestAdmitted.await(200, TimeUnit.MILLISECONDS),
                    "una richiesta normale e' passata durante il ripristino");

            releaseExclusive.countDown();
            assertTrue(requestAdmitted.await(2, TimeUnit.SECONDS),
                    "la richiesta non e' stata ammessa dopo il rilascio");
            exclusive.get(2, TimeUnit.SECONDS);
            blocked.get(2, TimeUnit.SECONDS);
        } finally {
            releaseExclusive.countDown();
            executor.shutdownNow();
            restore(previous);
        }
        assertEquals(permitsBefore, DatabaseRequestGate.availablePermits(),
                "permessi non restituiti: un permesso perso rende l'esclusiva impossibile per sempre");
    }

    /**
     * The restore request does not acquire a shared permit: if it did, its own exclusive
     * section would block it forever.
     */
    @Test
    void restoreRequestTakesNoSharedPermitAndCannotSelfBlock() throws Exception {
        GateSettings previous = currentSettings();
        DatabaseRequestGate gate = configuredGate(true);
        CountDownLatch insideExclusive = new CountDownLatch(1);
        CountDownLatch releaseExclusive = new CountDownLatch(1);
        CountDownLatch restoreAdmitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var exclusive = executor.submit(() -> DatabaseRequestGate.withExclusiveDatabaseAccess(() -> {
                insideExclusive.countDown();
                await(releaseExclusive);
                return null;
            }));
            assertTrue(insideExclusive.await(2, TimeUnit.SECONDS));

            ContainerRequestContext restore = request("POST", "backup/restore");
            var admitted = executor.submit(() -> {
                gate.filter(restore);
                restoreAdmitted.countDown();
                gate.filter(restore, null);
                return null;
            });
            assertTrue(restoreAdmitted.await(2, TimeUnit.SECONDS),
                    "il filtro ha bloccato la richiesta di ripristino: autoblocco");
            admitted.get(2, TimeUnit.SECONDS);

            releaseExclusive.countDown();
            exclusive.get(2, TimeUnit.SECONDS);
        } finally {
            releaseExclusive.countDown();
            executor.shutdownNow();
            restore(previous);
        }
    }

    /**
     * A lost permit — for example, an {@code Error} that skips response filters — used to
     * make exclusive acquisition impossible forever and, with a fair semaphore, freeze
     * every subsequent request in sequence. It must now fail within the timeout while
     * keeping the application alive.
     */
    @Test
    void aLostPermitMakesTheExclusiveFailFastInsteadOfHangingForever() throws Exception {
        GateSettings previous = currentSettings();
        int permitsBefore = DatabaseRequestGate.availablePermits();
        DatabaseRequestGate gate = configuredGate(true, 1, 1);
        ContainerRequestContext leaked = request("GET", "employees");
        gate.filter(leaked); // acquire without releasing: simulate the lost permit
        try {
            assertEquals(permitsBefore - 1, DatabaseRequestGate.availablePermits());
            assertThrows(DatabaseRequestGate.GateBusyException.class,
                    () -> DatabaseRequestGate.withExclusiveDatabaseAccess(() -> null),
                    "l'esclusiva ha atteso senza tetto invece di arrendersi");
        } finally {
            gate.filter(leaked, null); // return the permit
            restore(previous);
        }
        assertEquals(permitsBefore, DatabaseRequestGate.availablePermits());
    }

    private record GateSettings(boolean serializeWriters, int requestTimeout, int exclusiveTimeout) {
    }

    private static DatabaseRequestGate configuredGate(boolean serializeWriters) {
        return configuredGate(serializeWriters, 30, 30);
    }

    private static DatabaseRequestGate configuredGate(boolean serializeWriters,
            int requestTimeout, int exclusiveTimeout) {
        DatabaseRequestGate gate = new DatabaseRequestGate();
        gate.configuredSerialization = serializeWriters;
        gate.configuredRequestTimeout = requestTimeout;
        gate.configuredExclusiveTimeout = exclusiveTimeout;
        gate.configure();
        return gate;
    }

    private static GateSettings currentSettings() throws Exception {
        return new GateSettings(staticField("serializeWriters").getBoolean(null),
                staticField("requestTimeoutSeconds").getInt(null),
                staticField("exclusiveTimeoutSeconds").getInt(null));
    }

    private static void restore(GateSettings settings) {
        configuredGate(settings.serializeWriters(), settings.requestTimeout(), settings.exclusiveTimeout());
    }

    private static Field staticField(String name) throws Exception {
        Field field = DatabaseRequestGate.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * Minimal request context: the filter uses only the method, path, and properties.
     */
    private static ContainerRequestContext request(String method, String path) {
        Map<String, Object> properties = new HashMap<>();
        List<PathSegment> segments = Arrays.stream(path.split("/")).map(DatabaseRequestGateTest::segment).toList();
        UriInfo uriInfo = proxy(UriInfo.class, (m, args) -> "getPathSegments".equals(m.getName()) ? segments : null);
        return proxy(ContainerRequestContext.class, (m, args) -> switch (m.getName()) {
            case "getUriInfo" -> uriInfo;
            case "getMethod" -> method;
            case "setProperty" -> properties.put((String) args[0], args[1]);
            case "getProperty" -> properties.get((String) args[0]);
            case "removeProperty" -> properties.remove((String) args[0]);
            default -> null;
        });
    }

    private static PathSegment segment(String value) {
        return proxy(PathSegment.class, (m, args) -> "getPath".equals(m.getName()) ? value : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, StubHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (instance, method, args) -> {
                    Object answer = switch (method.getName()) {
                        case "toString" -> type.getSimpleName() + "-stub";
                        case "hashCode" -> System.identityHashCode(instance);
                        case "equals" -> instance == args[0];
                        default -> handler.handle(method, args);
                    };
                    if (answer == null && method.getReturnType().isPrimitive())
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : 0;
                    return answer;
                });
    }

    @FunctionalInterface
    private interface StubHandler {
        Object handle(Method method, Object[] args);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
