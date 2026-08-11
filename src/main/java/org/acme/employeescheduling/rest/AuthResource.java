package org.acme.employeescheduling.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.acme.employeescheduling.config.DeploymentMode;
import org.acme.employeescheduling.persistence.AppUserEntity;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * @brief Session state: who I am and how I log out.
 *
 * @details The actual login is handled by Quarkus on {@code POST /j_security_check} (form auth):
 *          credentials are not compared by hand here. What is left are the two pieces form auth
 *          does not cover: knowing who is connected and with which role, and closing the session.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    DeploymentMode deployment;

    @ConfigProperty(name = "quarkus.http.auth.form.cookie-name", defaultValue = "quarkus-credential")
    String sessionCookieName;

    /**
 * @brief Who is connected.
 * @details Public on purpose: the frontend calls it <b>before</b> knowing whether there is a
 *          session, and must be able to tell "not authenticated" from "server unreachable".
 *          With a 401 the client could not make that distinction without special cases.
     *
 *          A user who is authenticated but <b>inactive</b> (account awaiting approval or
 *          deactivated) does not get the application: the Quarkus login succeeds (the row exists),
 *          but {@code /auth/me} answers {@code authenticated=false} with {@code reason=INACTIVE}
 *          and the frontend shows the appropriate message. The session stays valid only to
 *          allow the logout.
     */
    @GET
    @Path("/me")
    @PermitAll
    public Response me() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean anonymous = identity == null || identity.isAnonymous();
        if (!anonymous) {
            String username = identity.getPrincipal().getName();
            AppUserEntity user = AppUserEntity.findByUsername(username);
            if (user != null && !user.active) {
                body.put("authenticated", false);
                body.put("reason", "INACTIVE");
                return Response.ok(body).build();
            }
            body.put("authenticated", true);
            body.put("username", username);
            body.put("roles", identity.getRoles());
            body.put("displayName", user != null && user.displayName != null
                    ? user.displayName : username);
            body.put("admin", user != null && user.isAdmin());
            // Desktop package or shared server: the UI hides actions that only make sense on one
            // of the two. Today that is "Chiudi applicazione", which a CAPOSALA may perform on a
            // desktop but not on a server, where SystemInfoResource.exit() answers 403.
            body.put("standalone", deployment.isStandalone());
            return Response.ok(body).build();
        }
        body.put("authenticated", false);
        return Response.ok(body).build();
    }

    /**
 * @brief Closes the session by expiring the cookie.
 * @details The cookie is encrypted server-side and has no state to invalidate: expiring it
 *          is the only way to close it. Consequence to be aware of: a cookie that has already
 *          been copied stays valid until its natural expiry.
     */
    @POST
    @Path("/logout")
    @RolesAllowed({"ADMIN", "CAPOSALA"})
    @Transactional
    public Response logout() {
        NewCookie expired = new NewCookie.Builder(sessionCookieName)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        return Response.ok(Map.of("loggedOut", true)).cookie(expired).build();
    }
}
