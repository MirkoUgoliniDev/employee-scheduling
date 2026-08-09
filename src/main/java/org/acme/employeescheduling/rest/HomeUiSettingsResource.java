package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.employeescheduling.dto.HomeUiSettings;

@Path("/home-ui-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HomeUiSettingsResource {

    @Inject DemoDataRepository repository;

    @GET
    @RolesAllowed({"ADMIN", "CAPOSALA"})
    @Transactional
    public Response get() {
        return Response.ok(repository.getHomeUiSettingsOrm()).build();
    }

    @PUT
    @RolesAllowed("ADMIN")
    @Transactional
    public Response save(HomeUiSettings settings) {
        if (settings == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (!isCoverDataUrlValid(settings.getCoverDataUrl())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(java.util.Map.of("error", "INVALID_COVER_DATA"))
                    .type(MediaType.APPLICATION_JSON).build();
        }
        return repository.saveHomeUiSettingsOrm(settings)
                ? Response.ok(repository.getHomeUiSettingsOrm()).build()
                : Response.serverError().build();
    }

    private boolean isCoverDataUrlValid(String coverDataUrl) {
        if (coverDataUrl == null || coverDataUrl.isBlank()) return true;
        if (!coverDataUrl.startsWith("data:image/webp;base64,")) return false;
        return coverDataUrl.length() <= 3_000_000;
    }
}
