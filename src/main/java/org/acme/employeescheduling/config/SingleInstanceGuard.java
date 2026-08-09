package org.acme.employeescheduling.config;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * @brief Prevents a second application instance from starting.
 *
 * @details Acquires an exclusive {@link FileLock} on {@code app.lock} in the data directory:
 *          if another instance already holds it, this process reopens the browser on the live
 *          instance and exits immediately.
 *
 *          <p><b>Why this is not a CDI bean.</b> It used to be one and observed
 *          {@code StartupEvent}: too late. Flyway runs during runtime initialization,
 *          <em>before</em> that event is emitted, so two processes launched fractions of a second
 *          apart migrated the same database concurrently, and the second discovered it was
 *          redundant only after migration. Worse, the observer started shutdown on a separate
 *          thread and <em>returned</em>, so the main thread continued startup and could win the
 *          HTTP port bind against the instance intended to survive — causing the browser to open
 *          on a dead port. Here the check runs in {@code main}, before any Quarkus code: the
 *          process without the lock opens neither connections nor sockets.</p>
 *
 *          <p>Active ONLY when the application runs from the installed package, that is, when
 *          {@link AppDataDirectory#base()} has a value: the guard is inert in development and
 *          tests, where it would otherwise block hot reload and test suites.</p>
 *
 *          <p>Best effort: if a filesystem error prevents acquiring the lock, startup continues —
 *          a running application is better than a false "already running" report.</p>
 */
public final class SingleInstanceGuard {

    /**
     * @brief Writes a line to stderr without using the logger.
     *
     * @details This code runs before Quarkus: using {@code java.util.logging} here initializes
     *          LogManager too early, which JBoss Log Manager complains about on every startup.
     *          Also, {@code halt} terminates the process before a buffer can be flushed, whereas
     *          this writes and forces output immediately — support still gets a trace of events.
     */
    private static void note(String message) {
        System.err.println("[single-instance] " + message);
        System.err.flush();
    }

    /** @brief Maximum time to wait for an OK click before terminating anyway. */
    private static final long WATCHDOG_MILLIS = 60_000L;
    /** @brief Maximum wait for the live instance to open the HTTP port. */
    private static final long PROBE_TIMEOUT_MILLIS = 15_000L;
    private static final long PROBE_INTERVAL_MILLIS = 300L;

    /**
     * @brief References deliberately kept alive.
     * @details The lock remains valid while the channel is open: losing its reference would
     *          expose both to garbage collection and cause the lock to be released automatically.
     */
    private static FileChannel channel;
    private static FileLock lock;

    private SingleInstanceGuard() {
    }

    /**
     * @brief Acquires the lock; if another instance is live, does NOT return: terminates the process.
     */
    public static void enforce() {
        Path base = AppDataDirectory.base();
        if (base == null || !enabled()) {
            return;
        }
        Path lockFile = base.resolve("app.lock");
        try {
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
        } catch (Exception e) {
            closeQuietly();
            note("Single-instance lock non disponibile: avvio senza guardia (" + e + ")");
            return;
        }
        if (lock == null) {
            note("Employee Scheduling e' gia' in esecuzione: riapro il browser ed esco");
            closeQuietly();
            handoffToRunningInstance();
        }
        note("Single-instance lock acquisito: " + lockFile);
    }

    /** @brief {@code true} unless explicitly disabled via a property or environment variable. */
    private static boolean enabled() {
        String value = System.getProperty("app.single-instance-lock",
                System.getenv("APP_SINGLE_INSTANCE_LOCK"));
        return value == null || value.isBlank() || Boolean.parseBoolean(value.trim());
    }

    private static int port() {
        String value = System.getProperty("quarkus.http.port", System.getenv("QUARKUS_HTTP_PORT"));
        try {
            return value == null || value.isBlank() ? 8080 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    /**
     * @brief Notifies the user and always terminates the process.
     *
     * @details This used to call {@code System.exit}, which did not work: it starts an orderly
     *          shutdown that waits for startup to finish — while startup is stopped at this exact
     *          point. The process remained in memory forever: eight processes and 1.1 GB were
     *          measured after four double-clicks on the executable. {@code Runtime.halt} skips
     *          shutdown hooks and kills the process immediately, which is correct here: this
     *          instance opened nothing and does not hold the lock.
     *
     *          The watchdog covers a dialog that is never closed (or ends up behind other
     *          windows): after the timeout, the process exits anyway.
     */
    private static void handoffToRunningInstance() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_MILLIS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(1);
        }, "single-instance-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        int port = port();
        if (!waitForHttp(port) || !openBrowser(port)) {
            showAlert("Employee Scheduling e' gia' in esecuzione.\n"
                    + "Apri manualmente http://localhost:" + port);
        }
        Runtime.getRuntime().halt(1);
    }

    /**
     * @brief Waits until the live instance is actually listening on the port.
     * @details The lock is acquired before Quarkus opens the socket: without this wait, the
     *          browser would open on a still-closed port and the user would see an unexplained
     *          error page. The timing window grows precisely when the other instance is slow,
     *          namely on first startup.
     */
    private static boolean waitForHttp(int port) {
        long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 1_000);
                return true;
            } catch (Exception ignored) {
                // Not listening yet: retry.
            }
            try {
                Thread.sleep(PROBE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void showAlert(String message) {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, "Employee Scheduling",
                        JOptionPane.WARNING_MESSAGE);
            }
        } catch (Throwable ignored) {
            // No GUI available: the message is already in the logs.
        }
    }

    private static boolean openBrowser(int port) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            Desktop.getDesktop().browse(URI.create("http://localhost:" + port));
            return true;
        } catch (Exception e) {
            note("Apertura browser fallita per istanza esistente: " + e);
            return false;
        }
    }

    private static void closeQuietly() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (Exception ignored) {
            // best-effort
        } finally {
            lock = null;
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
            // best-effort
        } finally {
            channel = null;
        }
    }
}
