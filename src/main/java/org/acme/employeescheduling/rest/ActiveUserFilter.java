package org.acme.employeescheduling.rest;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.acme.employeescheduling.persistence.AppUserEntity;

import java.util.Map;

/**
 * @brief Blocks requests from inactive users (awaiting approval or deactivated).
 *
 * @details Quarkus Security JPA authenticates on username+password+role without consulting
 *          {@code active}: form-auth login succeeds even for an inactive user and issues
 *          a cookie with the full role. This filter re-checks {@code active} on EVERY
 *          authenticated request and answers 403, making approval a real authorization
 *          check and not just a UI message.
 *
 *          Exceptions (paths an inactive user must be able to reach to understand their
 *          state or to get out): {@code /auth/me} (the frontend reads {@code reason=INACTIVE})
 *          and {@code /auth/logout}.
 *
 *          The check runs in a dedicated JPA transaction: the JAX-RS filter runs outside
 *          the request lifecycle, so it cannot use the endpoint's session.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class ActiveUserFilter implements ContainerRequestFilter {

    @Inject
    io.quarkus.security.identity.SecurityIdentity identity;

    @Override
    public void filter(ContainerRequestContext request) {
        if (identity == null || identity.isAnonymous()) return;

        // getPath() prepends the slash, the segments do not: use the segments like the
        // other filters in the project, otherwise none of the exemptions below match and
        // the inactive user loses /auth/me and /auth/logout too.
        String path = request.getUriInfo().getPathSegments().stream()
                .map(segment -> segment.getPath()).collect(java.util.stream.Collectors.joining("/"));
        if (path.equals("auth/me") || path.equals("auth/logout") || path.startsWith("auth/register/"))
            return;

        String username = identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
        if (username == null) return;

        boolean active = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            AppUserEntity user = AppUserEntity.findByUsername(username);
            return user == null || user.active;
        });

        if (!active) {
            request.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "ACCOUNT_INACTIVE"))
                    .build());
        }
    }
}
