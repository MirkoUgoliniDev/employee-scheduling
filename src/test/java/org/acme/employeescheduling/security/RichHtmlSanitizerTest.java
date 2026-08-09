package org.acme.employeescheduling.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RichHtmlSanitizerTest {

    @Test
    void stripsExecutableMarkupAndUnsafeLinksButKeepsSupportedFormatting() {
        String safe = RichHtmlSanitizer.sanitize("""
                <p onclick="alert(1)"><strong>Ciao</strong>
                <a href="javascript:alert(2)">malevolo</a>
                <a href="https://example.test/path">sicuro</a>
                <img src=x onerror="alert(3)"><script>alert(4)</script></p>
                """);

        assertTrue(safe.contains("<p>"));
        assertTrue(safe.contains("<strong>Ciao</strong>"));
        assertTrue(safe.contains("https://example.test/path"));
        assertFalse(safe.toLowerCase().contains("javascript:"));
        assertFalse(safe.toLowerCase().contains("onclick"));
        assertFalse(safe.toLowerCase().contains("onerror"));
        assertFalse(safe.toLowerCase().contains("<script"));
        assertFalse(safe.toLowerCase().contains("<img"));
    }
}
