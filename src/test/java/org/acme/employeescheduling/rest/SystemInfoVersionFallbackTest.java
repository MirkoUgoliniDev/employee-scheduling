package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * @brief Fails the build if {@link SystemInfoResource}'s fallback versions diverge from those
 *        declared in pom.xml.
 * @details In dev mode the MANIFEST does not expose Implementation-Version, so System Info shows
 *          hardcoded fallbacks: without this test, a Timefold/Quarkus bump in the POM would leave
 *          stale fallbacks and users would see incorrect versions. Pure unit test (no Quarkus
 *          context): reads the POM from the module root.
 */
class SystemInfoVersionFallbackTest {

    private static String pom() throws Exception {
        return Files.readString(Path.of("pom.xml"));
    }

    private static String find(String pom, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(pom);
        if (!m.find()) throw new AssertionError("Pattern non trovato nel pom.xml: " + regex);
        return m.group(1).trim();
    }

    @Test
    void fallbackTimefoldMatchesPom() throws Exception {
        assertEquals(find(pom(), "<version\\.ai\\.timefold\\.solver>([^<]+)</version\\.ai\\.timefold\\.solver>"),
                SystemInfoResource.FALLBACK_TIMEFOLD,
                "SystemInfoResource.FALLBACK_TIMEFOLD non allineato a version.ai.timefold.solver nel pom.xml");
    }

    @Test
    void fallbackQuarkusMatchesPom() throws Exception {
        assertEquals(find(pom(), "<version\\.io\\.quarkus>([^<]+)</version\\.io\\.quarkus>"),
                SystemInfoResource.FALLBACK_QUARKUS,
                "SystemInfoResource.FALLBACK_QUARKUS non allineato a version.io.quarkus nel pom.xml");
    }

}
