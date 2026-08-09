package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Comparison between the installed and published versions.
 *
 * <p>Small but subtle logic: getting it wrong means either never notifying users of an update,
 * or forever notifying them of one that does not exist. Both failures are silent, so they must
 * be caught here.</p>
 */
class AppVersionComparisonTest {

    @Test
    void tagPrefixAndSuffixesAreIgnored() {
        assertEquals("1.2.0", SystemInfoResource.normalizeVersion("v1.2.0"));
        assertEquals("1.2.0", SystemInfoResource.normalizeVersion("1.2.0-rc1"));
        assertEquals("1.1", SystemInfoResource.normalizeVersion("1.1-SNAPSHOT"));
        assertEquals("1.2.0", SystemInfoResource.normalizeVersion("  V1.2.0+build7 "));
    }

    @Test
    void newerVersionIsDetected() {
        assertTrue(SystemInfoResource.isNewerVersion("1.2.0", "1.1.0"));
        assertTrue(SystemInfoResource.isNewerVersion("1.1.1", "1.1.0"));
        assertTrue(SystemInfoResource.isNewerVersion("2.0.0", "1.9.9"));
        // Missing parts count as zero: 1.2 is still newer than 1.1.9.
        assertTrue(SystemInfoResource.isNewerVersion("1.2", "1.1.9"));
    }

    @Test
    void sameVersionDoesNotWarn() {
        assertFalse(SystemInfoResource.isNewerVersion("1.1.0", "1.1.0"));
        // The POM says 1.1-SNAPSHOT while the package declares 1.1.0: same version,
        // no notification. Without this rule, it would always appear in development.
        assertFalse(SystemInfoResource.isNewerVersion("1.1.0", "1.1-SNAPSHOT"));
        assertFalse(SystemInfoResource.isNewerVersion("v1.1.0", "1.1"));
    }

    @Test
    void olderPublishedVersionNeverWarns() {
        assertFalse(SystemInfoResource.isNewerVersion("1.0.9", "1.1.0"));
        assertFalse(SystemInfoResource.isNewerVersion("1.1.0", "2.0.0"));
    }

    @Test
    void garbageDoesNotWarn() {
        assertFalse(SystemInfoResource.isNewerVersion("", "1.1.0"));
        assertFalse(SystemInfoResource.isNewerVersion(null, "1.1.0"));
        assertFalse(SystemInfoResource.isNewerVersion("latest", "1.1.0"));
    }
}
