package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.employeescheduling.dto.GeneralSettings;
import org.acme.employeescheduling.persistence.StructureEntity;

@RolesAllowed("ADMIN")
@Path("/general-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeneralSettingsResource {
    @Inject DemoDataRepository repository;

    @GET
    @Transactional
    public Response get(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (structureId <= 0) return Response.status(Response.Status.BAD_REQUEST).build();
        if (StructureEntity.count("id", structureId) == 0) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(repository.getGeneralSettingsOrm(structureId)).build();
    }

    @PUT
    @Transactional
    public Response save(@QueryParam("structureId") @DefaultValue("0") int structureId, GeneralSettings s) {
        boolean validMode = s != null && ("week".equals(s.getShiftWindowMode()) || "month".equals(s.getShiftWindowMode()));
        if (structureId <= 0 || !validMode)
            return Response.status(Response.Status.BAD_REQUEST).build();
        if (StructureEntity.count("id", structureId) == 0)
            return Response.status(Response.Status.NOT_FOUND).build();
        return repository.saveGeneralSettingsOrm(structureId, s)
                ? Response.ok(repository.getGeneralSettingsOrm(structureId)).build()
                : Response.serverError().build();
    }
}
