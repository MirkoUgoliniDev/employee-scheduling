package org.acme.employeescheduling.config;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * @brief Application entry point.
 *
 * @details It exists for one reason only: to give {@link SingleInstanceGuard} a place to run
 *          <em>before</em> Quarkus. The duplicate-instance check used to be in a
 *          {@code StartupEvent} observer, which is emitted after runtime initialization — therefore
 *          after Flyway had already migrated the database and the HTTP port had been contested.
 *          Two rapid double-clicks on the executable caused two processes to migrate the same
 *          SQLite file concurrently.
 *
 *          <p>Here, instead, the process that does not obtain the lock exits without even opening
 *          a connection. Outside the installed package the guard is inert, and this main method
 *          simply starts Quarkus as the generated one would.</p>
 */
@QuarkusMain
public class AppMain {

    public static void main(String... args) {
        SingleInstanceGuard.enforce();
        Quarkus.run(args);
    }
}
