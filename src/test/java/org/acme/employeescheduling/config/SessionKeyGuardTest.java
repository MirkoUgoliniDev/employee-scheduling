package org.acme.employeescheduling.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

/**
 * @brief Verifies that the published default session key cannot reach a production startup.
 *
 * @details The failure this prevents is silent by construction: the application starts, logs in,
 *          and works perfectly while every session cookie is forgeable by anyone who has read the
 *          public repository. There is nothing to notice at runtime, so the check has to be a
 *          refusal to boot, and the test has to pin the literal it refuses.
 */
class SessionKeyGuardTest {

    /** @brief Matches the property and captures the default that follows the colon. */
    private static final Pattern PROPERTY_DEFAULT = Pattern.compile(
            "^quarkus\\.http\\.auth\\.session\\.encryption-key=\\$\\{[^:}]+:([^}]*)}\\s*$",
            Pattern.MULTILINE);

    private static SessionKeyGuard guard(String key) {
        SessionKeyGuard guard = new SessionKeyGuard();
        guard.sessionKey = key;
        return guard;
    }

    @Test
    void refusesToStartInProductionWithThePublishedDefault() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard(SessionKeyGuard.DEVELOPMENT_DEFAULT).validate(LaunchMode.NORMAL));
        assertTrue(failure.getMessage().contains("AUTH_SESSION_KEY"),
                "the message must name the variable to set, otherwise the operator is stuck: " + failure.getMessage());
    }

    @Test
    void refusesItRegardlessOfSurroundingWhitespace() {
        assertThrows(IllegalStateException.class,
                () -> guard("  " + SessionKeyGuard.DEVELOPMENT_DEFAULT + "  ").validate(LaunchMode.NORMAL));
    }

    @Test
    void acceptsAKeyProvidedByTheEnvironment() {
        assertDoesNotThrow(() -> guard("Zx4Qw9Lm2Pv7Rt1Ns6Hj3Kd8Fg5Bc0Ye7Ua2Iv9Ow4Tl6Mz").validate(LaunchMode.NORMAL));
    }

    /**
     * @details Dev and test are exactly where the default belongs: `quarkus:dev` must keep working
     *          with no environment setup, and the suite runs on the same file.
     */
    @Test
    void staysInertInDevelopmentAndTest() {
        assertDoesNotThrow(() -> guard(SessionKeyGuard.DEVELOPMENT_DEFAULT).validate(LaunchMode.DEVELOPMENT));
        assertDoesNotThrow(() -> guard(SessionKeyGuard.DEVELOPMENT_DEFAULT).validate(LaunchMode.TEST));
    }

    /**
     * @brief The guard's constant must stay equal to the default written in the properties file.
     *
     * @details If someone edits the default in `application.properties` without updating the
     *          constant, the guard keeps compiling, keeps passing every other test, and silently
     *          stops matching the value it exists to reject — the worst possible outcome, because
     *          it looks protected.
     */
    @Test
    void theConstantStillMatchesTheDefaultInApplicationProperties() throws IOException {
        Path properties = Path.of("src", "main", "resources", "application.properties");
        assertTrue(Files.isRegularFile(properties), "application.properties not found at " + properties.toAbsolutePath());

        Matcher matcher = PROPERTY_DEFAULT.matcher(Files.readString(properties, StandardCharsets.UTF_8));
        assertTrue(matcher.find(), "quarkus.http.auth.session.encryption-key must keep the form ${VAR:default}");
        assertEquals(SessionKeyGuard.DEVELOPMENT_DEFAULT, matcher.group(1),
                "SessionKeyGuard.DEVELOPMENT_DEFAULT no longer matches the default in application.properties");
    }
}
