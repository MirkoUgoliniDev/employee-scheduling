package org.acme.employeescheduling.config;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * @brief Tells a single-PC desktop installation apart from a shared server one.
 *
 * @details
 * The distinction is not cosmetic: the same action means different things on the two.
 * Closing the application is one window on a desktop and a service outage for everyone on a
 * server; registration needs no email on a desktop and OTP verification on a server.
 *
 * <p>The rule already existed as {@code RegistrationResource.isServerMode()} and is extracted
 * here because a second caller ({@code SystemInfoResource.exit()}) now needs the same answer.
 * Two copies of a rule that decides who may shut the application down is exactly the shape of
 * defect this codebase has been paying for elsewhere — when one copy is updated and the other
 * is not, nothing fails, and the weaker of the two silently wins.</p>
 *
 * <p>{@code app.registration.mode=auto} (the default) derives the mode from the engine, because
 * the engine is what actually correlates with the deployment: SQLite is the desktop package,
 * PostgreSQL is the shared server. An explicit {@code server} overrides that for the case of a
 * SQLite installation deliberately exposed to several people.</p>
 */
@ApplicationScoped
public class DeploymentMode {

    @ConfigProperty(name = "app.registration.mode", defaultValue = "auto")
    String registrationMode;

    @ConfigProperty(name = "app.database.kind", defaultValue = "sqlite")
    String databaseKind;

    /** @brief true if this instance is shared: several users, email verification, no local owner. */
    public boolean isServerMode() {
        return "server".equals(registrationMode)
                || ("auto".equals(registrationMode) && "postgresql".equals(databaseKind));
    }

    /** @brief true if this is the single-PC desktop package: whoever sits at it owns the instance. */
    public boolean isStandalone() {
        return !isServerMode();
    }
}
