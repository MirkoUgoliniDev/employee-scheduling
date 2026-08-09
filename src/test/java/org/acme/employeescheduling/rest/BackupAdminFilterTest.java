package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackupAdminFilterTest {

    @Test
    void normalizesAbsoluteAndRelativeRequestPaths() {
        assertEquals("backup/settings", BackupAdminFilter.normalizePath("/backup/settings"));
        assertEquals("backup/settings", BackupAdminFilter.normalizePath("backup/settings"));
    }

    @Test
    void recognizesOnlyActualLoopbackAddresses() {
        assertTrue(BackupAdminFilter.isLoopback("127.0.0.1"));
        assertTrue(BackupAdminFilter.isLoopback("::1"));
        assertTrue(BackupAdminFilter.isLoopback("0:0:0:0:0:0:0:1%4"));
        assertFalse(BackupAdminFilter.isLoopback("192.168.1.10"));
        assertFalse(BackupAdminFilter.isLoopback(null));
        assertFalse(BackupAdminFilter.isLoopback("not-an-address.invalid"));
    }

    @Test
    void tokenComparisonIsExactAndNullSafe() {
        assertTrue(BackupAdminFilter.tokenEquals("secret-123", "secret-123"));
        assertFalse(BackupAdminFilter.tokenEquals("secret-123", "secret-124"));
        assertFalse(BackupAdminFilter.tokenEquals("secret-123", null));
    }
}
