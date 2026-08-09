package org.acme.employeescheduling.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.awt.Desktop;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @brief Automatically opens the browser at startup (a "desktop app" experience).
 *
 * @details When the jpackage bundle starts, the user sees nothing on screen: the interface is a
 *          local web server. This bean opens the system browser at
 *          {@code http://localhost:<port>} as soon as the application is ready.
 *
 *          Only in NORMAL mode (installed package): the browser is not touched in development or
 *          tests. Best effort: if the desktop is unavailable (headless server, sessions without
 *          a GUI), the failure is only logged.
 *
 *          Can be disabled with {@code app.open-browser-on-start=false}.
 */
@ApplicationScoped
public class BrowserLauncher {

    private static final Logger logger = Logger.getLogger(BrowserLauncher.class.getName());

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int port;

    @ConfigProperty(name = "app.open-browser-on-start", defaultValue = "true")
    boolean openBrowser;

    void onStart(@Observes StartupEvent event) {
        if (!openBrowser || LaunchMode.current() != LaunchMode.NORMAL) {
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            logger.info("Browser non disponibile in questa sessione: aprire manualmente http://localhost:" + port);
            return;
        }
        // Brief delay: the endpoint must be reachable when the browser arrives.
        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(1500);
                Desktop.getDesktop().browse(URI.create("http://localhost:" + port));
                logger.info("Browser aperto su http://localhost:" + port);
            } catch (Exception e) {
                logger.log(Level.INFO,
                        "Apertura automatica del browser non riuscita: aprire manualmente http://localhost:" + port, e);
            }
        }, "browser-opener");
        opener.setDaemon(true);
        opener.start();
    }
}
