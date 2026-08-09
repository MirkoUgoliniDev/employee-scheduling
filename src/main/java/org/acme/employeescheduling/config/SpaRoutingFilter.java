package org.acme.employeescheduling.config;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * @file SpaRoutingFilter.java
 * @brief SPA fallback for the React Single Page App served by Quarkus.
 *
 * @details
 * Browser navigation requests (GET with {@code Accept: text/html}) to paths that are neither
 * static files nor system endpoints receive {@code index.html} with status 200, so React Router
 * (BrowserRouter) handles the client-side route. Without this filter, reloading, pressing F5,
 * or opening a bookmark on a sub-route (for example {@code /shifts}) causes a Quarkus 404.
 *
 * API calls made with {@code fetch()} (Accept: *&#47;*, see frontend/src/api/client.ts) do NOT
 * contain "text/html" and therefore continue to RESTEasy: JSON endpoints remain intact,
 * including the colliding {@code /structures} and {@code /labels} routes (paths that exist as
 * both React routes and REST endpoints).
 */
@ApplicationScoped
public class SpaRoutingFilter {

    private static final String INDEX_RESOURCE = "META-INF/resources/index.html";
    private static final Set<String> CLIENT_ROUTES = Set.of(
            "/", "/login", "/register", "/shifts", "/employees", "/specialists", "/locations",
            "/skills", "/dates", "/report", "/structures", "/labels", "/config");

    /**
     * Registers a high-priority Vert.x filter: it runs BEFORE RESTEasy route handlers and the
     * static-resource handler.
     */
    public void registerSpaFilter(@Observes Filters filters) {
        filters.register(this::handle, 1000);
    }

    private void handle(RoutingContext rc) {
        if (isSpaNavigation(rc)) {
            serveIndex(rc);
        } else {
            rc.next();
        }
    }

    private boolean isSpaNavigation(RoutingContext rc) {
        // 1) GET only (navigation)
        if (rc.request().method() != HttpMethod.GET) {
            return false;
        }

        final String path = rc.normalizedPath(); // decoded path, without query string

        // 2) Intercept only routes declared by React Router. A generic fallback would hide
        //    404s and, worse, protected REST endpoints.
        String route = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
        if (!CLIENT_ROUTES.contains(route)) {
            return false;
        }

        // 3) Browser navigation only: it explicitly sends "text/html".
        //    The client's fetch() does not set Accept -> "*/*" -> no match here,
        //    so API calls (including /structures and /labels) continue onward.
        final String accept = rc.request().getHeader("Accept");
        if (!acceptsHtml(accept)) {
            return false;
        }

        return true;
    }

    private static boolean acceptsHtml(String accept) {
        if (accept == null) return false;
        for (String range : accept.split(",")) {
            String[] parts = range.trim().split(";");
            if (!"text/html".equalsIgnoreCase(parts[0].trim())) continue;
            double quality = 1.0;
            for (int i = 1; i < parts.length; i++) {
                String parameter = parts[i].trim();
                if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                    try {
                        quality = Double.parseDouble(parameter.substring(2).trim());
                    } catch (NumberFormatException ignored) {
                        quality = 0.0;
                    }
                }
            }
            if (quality > 0.0) return true;
        }
        return false;
    }

    private void serveIndex(RoutingContext rc) {
        // Read fresh from the classpath on every navigation: index.html is small and full-page
        // navigations are rare, so frontend hot reload in development (new bundle hashes) is
        // always reflected without stale caching.
        String html = loadIndexHtml();
        if (html == null) {
            rc.next(); // resource not found: leave the default 404
            return;
        }
        rc.response()
          .setStatusCode(200)
          .putHeader("Content-Type", "text/html; charset=utf-8")
          .putHeader("Cache-Control", "no-cache")
          .end(html);
    }

    private static String loadIndexHtml() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SpaRoutingFilter.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
