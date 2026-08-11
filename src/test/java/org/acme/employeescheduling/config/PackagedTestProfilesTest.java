package org.acme.employeescheduling.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * @brief Every test profile must be dropped from the packaged artifact.
 *
 * @details
 * The two {@code application-test-*.properties} files sit under {@code src/main/resources} and
 * cannot move: Quarkus does not apply {@code application-<profile>.properties} placed in
 * {@code src/test/resources}. They are copied to {@code target/test-classes} and silently
 * ignored — the suite then falls back to the default test port and the wrong datasource, which
 * is how the attempted move was caught.
 *
 * <p>Living there, they would ship inside the jar. Launching a released artifact with
 * {@code -Dquarkus.profile=test-sqlite} would hand {@code /backup/*} a token printed in a public
 * repository and repoint the datasource at {@code target/portability-sqlite.db}. The fix is
 * {@code quarkus.package.jar.user-configured-ignored-entries}, and this test is what keeps it
 * true: it derives the expectation from the files that actually exist, so <b>a third test
 * profile added later fails the build until it is listed too</b>, instead of quietly shipping.</p>
 *
 * <p><b>What this does not prove.</b> It pins the configuration, not the produced artifact —
 * failsafe only runs under the {@code native} profile here, so no test opens the real jar during
 * {@code mvn verify}. The jar itself was inspected by hand when the exclusion was introduced:
 * the two entries were absent and the three production profiles still present.</p>
 */
class PackagedTestProfilesTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final String IGNORED_ENTRIES_KEY = "quarkus.package.jar.user-configured-ignored-entries";

    /** @brief Matches the property, tolerating spaces around the equals sign. */
    private static final Pattern IGNORED_ENTRIES = Pattern.compile(
            "^" + Pattern.quote(IGNORED_ENTRIES_KEY) + "\\s*=\\s*(.+)$", Pattern.MULTILINE);

    private static Set<String> declaredIgnoredEntries() throws IOException {
        String properties = Files.readString(RESOURCES.resolve("application.properties"), StandardCharsets.UTF_8);
        Matcher matcher = IGNORED_ENTRIES.matcher(properties);
        assertTrue(matcher.find(), IGNORED_ENTRIES_KEY + " is missing from application.properties");
        return Stream.of(matcher.group(1).split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toSet());
    }

    private static List<String> testProfileFiles() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("application-test-") && name.endsWith(".properties"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void everyTestProfileIsExcludedFromTheJar() throws IOException {
        List<String> profiles = testProfileFiles();
        assertFalse(profiles.isEmpty(),
                "no application-test-*.properties found: has the layout changed? This test would then pass vacuously.");

        Set<String> ignored = declaredIgnoredEntries();
        for (String profile : profiles)
            assertTrue(ignored.contains(profile),
                    profile + " would be packaged and shipped. Add it to " + IGNORED_ENTRIES_KEY
                    + " in application.properties, next to the others.");
    }

    /**
     * @details The production profiles must NOT be excluded — dropping one would break the
     *          installed application in a way no test here would notice, since the suite never
     *          runs against the packaged jar.
     */
    @Test
    void productionProfilesAreNotExcludedByAccident() throws IOException {
        Set<String> ignored = declaredIgnoredEntries();
        for (String profile : List.of("application.properties", "application-sqlite.properties",
                "application-postgresql.properties", "application-legacy-sqlite.properties"))
            assertFalse(ignored.contains(profile),
                    profile + " is a production profile and must stay inside the jar.");
    }
}
