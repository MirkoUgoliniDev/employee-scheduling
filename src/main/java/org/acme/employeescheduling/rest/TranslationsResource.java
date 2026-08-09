package org.acme.employeescheduling.rest;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * @brief REST endpoint that exposes all UI translations as a nested JSON map.
 *
 * Returns: { "it": { "btn.save": "Save", ... }, "en": { ... }, ... }
 * The result is served from the in-memory cache in DemoDataRepository.
 */
// Public: the login page must be translatable BEFORE authentication.
@PermitAll
@Path("/translations")
@Produces(MediaType.APPLICATION_JSON)
public class TranslationsResource {

    private final DemoDataRepository repo;

    @Inject
    public TranslationsResource(DemoDataRepository repo) {
        this.repo = repo;
    }

    @GET
    public Response getAll() {
        return Response.ok(repo.getAllTranslationsOrm()).build();
    }
}
