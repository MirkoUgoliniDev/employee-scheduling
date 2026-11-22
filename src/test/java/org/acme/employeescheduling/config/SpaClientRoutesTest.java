package org.acme.employeescheduling.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @file SpaClientRoutesTest.java
 * @brief Keeps {@link SpaRoutingFilter#CLIENT_ROUTES} aligned with the React route table.
 *
 * @details
 * The SPA fallback is an explicit allowlist, and nothing used to verify it. Adding a page to
 * {@code frontend/src/App.tsx} without adding the path to the filter left the page working when
 * reached by clicking, and broken on reload or bookmark: the browser sends {@code text/html} for
 * a navigation, the filter lets the request through because the path is not on its list, and
 * RESTEasy answers for the REST endpoint of the same name — 401 JSON on {@code /customers} and
 * {@code /assignments}, which is exactly what happened when the customer pages were added.
 *
 * The check reads the TypeScript source rather than duplicating the route list in Java, so the
 * single source of truth stays {@code App.tsx}.
 */
class SpaClientRoutesTest {

    /** @brief Matches {@code path="/shifts"}; the catch-all {@code path="*"} does not match. */
    private static final Pattern ROUTE_PATH = Pattern.compile("path=\"(/[^\"]*)\"");

    @Test
    void everyReactRouteIsServedAsIndexHtml() throws IOException {
        Path app = Path.of("frontend", "src", "App.tsx");
        Assumptions.assumeTrue(Files.exists(app),
                "frontend sources are not next to the build, so the route table cannot be read: "
                        + app.toAbsolutePath());

        String source = Files.readString(app, StandardCharsets.UTF_8);
        Set<String> declared = new LinkedHashSet<>();
        Matcher matcher = ROUTE_PATH.matcher(source);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        assertFalse(declared.isEmpty(),
                "No <Route path=...> found in " + app + ": the assertion below would pass vacuously.");

        Set<String> notServed = new LinkedHashSet<>(declared);
        notServed.removeAll(SpaRoutingFilter.CLIENT_ROUTES);
        assertTrue(notServed.isEmpty(),
                "App.tsx declares routes that SpaRoutingFilter does not serve as index.html: " + notServed
                        + ". Reloading or bookmarking those pages would hit the REST endpoint of the same name"
                        + " (401/404 JSON) instead of the SPA. Add them to CLIENT_ROUTES.");
    }
}
