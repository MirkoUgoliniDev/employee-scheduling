package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.employeescheduling.dto.Language;

import java.util.List;

@RolesAllowed("ADMIN")
@Path("/languages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LanguageResource {

    private final DemoDataRepository repo;

    @Inject
    public LanguageResource(DemoDataRepository repo) {
        this.repo = repo;
    }

    @GET
    public Response getAll() {
        List<Language> languages = repo.getLanguagesOrm();
        return Response.ok(languages).build();
    }

    @POST
    public Response add(Language lang) {
        if (lang == null || lang.getCode() == null || lang.getCode().isBlank()
                || lang.getDescription() == null || lang.getDescription().isBlank()) {
            return ApiErrors.badRequest("LANGUAGE_CODE_DESCRIPTION_REQUIRED");
        }
        int id = repo.addLanguageOrm(lang);
        if (id < 0) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(java.util.Map.of("error", "LANGUAGE_INSERT_FAILED"))
                    .type(MediaType.APPLICATION_JSON).build();
        }
        lang.setId(id);
        return Response.status(Response.Status.CREATED).entity(lang).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") int id, Language lang) {
        if (lang == null || lang.getCode() == null || lang.getCode().isBlank()
                || lang.getDescription() == null || lang.getDescription().isBlank()) {
            return ApiErrors.badRequest("LANGUAGE_CODE_DESCRIPTION_REQUIRED");
        }
        int updated = repo.updateLanguageOrm(id, lang);
        if (updated == 0) {
            return ApiErrors.notFound("LANGUAGE_NOT_FOUND");
        }
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") int id) {
        boolean deleted = repo.deleteLanguageByIdOrm(id);
        if (!deleted) {
            return ApiErrors.badRequest("LANGUAGE_ACTIVE_CANNOT_DELETE");
        }
        return Response.ok().build();
    }

    @PUT
    @Path("/{id}/activate")
    public Response activate(@PathParam("id") int id) {
        return repo.setActiveLanguageOrm(id) ? Response.ok().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }
}
