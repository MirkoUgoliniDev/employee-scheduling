package org.acme.employeescheduling.config;

import java.util.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;

/**
 * @brief Blocks startup when the session cookie is encrypted with the published default key.
 *
 * @details
 * {@code quarkus.http.auth.session.encryption-key} carries a development default in
 * {@code application.properties} so that {@code quarkus:dev} works without any environment
 * setup. That default ships with a <b>public repository</b>, and the form-auth cookie
 * ({@code employee_scheduling_session}) is encrypted and integrity-protected by that key alone.
 *
 * <p>Left in place on a reachable instance, anyone holding the published literal can forge a
 * valid cookie offline for an arbitrary user with role {@code ADMIN}, bypassing bcrypt, the OTP
 * flow and {@code ActiveUserFilter} in a single step — no request to the server is needed to
 * mint it. The comment next to the property already said production must override it, but a
 * comment is documentation, not enforcement: nothing refused to boot.</p>
 *
 * <p>The supported installation paths were never affected — {@code scripts/install-windows.ps1}
 * and {@code setup/steps/env_config.py} generate a random 48-character key. The exposure is the
 * installation that bypasses them: {@code mvn quarkus:dev} left reachable, a hand-rolled
 * {@code java -jar}, a container built straight from the repository, an {@code .env} copied from
 * the example. This guard converts all of those from silently insecure into a refusal to start.</p>
 *
 * <p>Inert in dev and test, where the default is the point: the check applies only to
 * {@link LaunchMode#NORMAL}. It runs at {@code APPLICATION} priority (2000), so startup stops
 * before the bootstrap in {@code DemoDataRepository} (default priority 2500) touches the
 * database.</p>
 */
@ApplicationScoped
public class SessionKeyGuard {

    private static final Logger logger = Logger.getLogger(SessionKeyGuard.class.getName());

    /**
     * @brief The literal published in {@code application.properties}.
     *
     * @details Kept in sync by {@code SessionKeyGuardTest}, which fails if the property's default
     *          drifts away from this constant — otherwise the guard would silently stop matching
     *          the very value it exists to reject.
     */
    static final String DEVELOPMENT_DEFAULT = "dev-only-change-me-32-chars-min!!";

    @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
    String sessionKey;

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent ignored) {
        validate(LaunchMode.current());
    }

    /** @brief Runs the check; throws IllegalStateException naming the fix to apply. */
    void validate(LaunchMode mode) {
        if (mode != LaunchMode.NORMAL) return;

        if (DEVELOPMENT_DEFAULT.equals(sessionKey == null ? null : sessionKey.trim()))
            throw new IllegalStateException(
                    "Session encryption key not configured: it is still the development default"
                    + " published in this repository. Anyone can forge a valid session cookie as"
                    + " ADMIN without knowing any password. Set AUTH_SESSION_KEY to a random"
                    + " value of at least 32 characters (the installers generate a 48-character"
                    + " one) and restart.");

        logger.fine("Session encryption key supplied by the environment.");
    }
}
