package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
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
import org.acme.employeescheduling.dto.Structure;
import org.acme.employeescheduling.persistence.EmailLogEntity;
import org.acme.employeescheduling.persistence.EmailTemplateEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.GeneralSettingsEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.PdfTemplateEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateHeaderEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateSkillEntity;
import org.acme.employeescheduling.persistence.SolverSettingsEntity;
import org.acme.employeescheduling.persistence.SpecialistEntity;
import org.acme.employeescheduling.persistence.StructureEntity;

import java.util.List;
import java.util.Map;

/**
 * @brief Structure CRUD — FIRST resource migrated to ORM (Panache) on the ORM branch.
 *
 * @details REST contract unchanged from the JDBC version (same paths, payloads, and STRUCTURE_*
 *          error codes): the frontend does not distinguish the implementations. Persistence uses
 *          the shared Agroal datasource (WAL + busy_timeout, see application.properties) and
 *          coexists with the legacy JDBC layer.
 */
@Path("/structures")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StructureResource {

    /**
     * @brief Structure list, readable by CAPOSALA users too.
     * @details Not restricted data and a prerequisite for use: without this list, the frontend has
     *          no selected structure and every page remains empty. Changes remain restricted to
     *          administrators (annotations on individual methods).
     */
    @RolesAllowed({"ADMIN", "CAPOSALA"})
    @GET
    public Response getAll() {
        List<Structure> structures = StructureEntity.<StructureEntity>listAll(io.quarkus.panache.common.Sort.by("id"))
                .stream().map(StructureEntity::toDto).toList();
        return Response.ok(structures).build();
    }

    @RolesAllowed("ADMIN")
    @POST
    @Transactional
    public Response add(Structure structure) {
        if (structure == null || structure.getName() == null || structure.getName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "STRUCTURE_NAME_REQUIRED"))
                    .build();
        }
        StructureEntity entity = new StructureEntity();
        entity.applyDto(structure);
        entity.persist();
        structure.setId(entity.id);
        return Response.status(Response.Status.CREATED).entity(structure).build();
    }

    @RolesAllowed("ADMIN")
    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") int id, Structure structure) {
        if (structure == null || structure.getName() == null || structure.getName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "STRUCTURE_NAME_REQUIRED"))
                    .build();
        }
        StructureEntity entity = StructureEntity.findById(id);
        if (entity == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "STRUCTURE_NOT_FOUND"))
                    .build();
        }
        entity.applyDto(structure); // Managed entity: end-of-transaction flush writes the UPDATE.
        return Response.ok().build();
    }

    @RolesAllowed("ADMIN")
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") int id) {
        if (countStructureUsage(id) > 0) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "STRUCTURE_IN_USE"))
                    .build();
        }
        // Configuration/audit rows are owned by the structure and have no separate
        // user-facing delete workflow. Remove them explicitly because legacy SQLite
        // databases do not consistently enforce ON DELETE CASCADE.
        ShiftTemplateSkillEntity.delete(
                "templateId in (select t.id from ShiftTemplateEntity t where t.structureId = ?1)", id);
        ShiftTemplateEntity.delete("structureId", id);
        ShiftTemplateHeaderEntity.delete("structureId", id);
        SolverSettingsEntity.delete("structureId", id);
        GeneralSettingsEntity.delete("structureId", id);
        EmailTemplateEntity.delete("structureId", id);
        PdfTemplateEntity.delete("structureId", id);
        EmailLogEntity.delete("structureId", id);
        boolean deleted = StructureEntity.deleteById(id);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "STRUCTURE_NOT_FOUND"))
                    .build();
        }
        return Response.ok().build();
    }

    /**
     * @brief Counts primary data requiring explicit confirmation/removal.
     * @details Configuration, templates, and audits are instead cascade-deleted by the delete
     *          transaction because they have no separate workflow.
     */
    private int countStructureUsage(int structureId) {
        return Math.toIntExact(EmployeeEntity.count("structureId", structureId)
                + LocationEntity.count("structureId", structureId)
                + SpecialistEntity.count("structureId", structureId));
    }
}
