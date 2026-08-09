package org.acme.employeescheduling.security;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

/** Central server-side policy for the limited rich text accepted by email templates. */
public final class RichHtmlSanitizer {

    private static final PolicyFactory POLICY = Sanitizers.BLOCKS
            .and(Sanitizers.FORMATTING)
            .and(Sanitizers.LINKS);

    private RichHtmlSanitizer() {
    }

    public static String sanitize(String html) {
        return POLICY.sanitize(html == null ? "" : html);
    }
}
