package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.panache.common.Sort;
import org.acme.employeescheduling.dto.Specialist;
import org.acme.employeescheduling.persistence.AffinityEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SpecialistEntity;
import org.acme.employeescheduling.persistence.StructureEntity;

/**
 * @brief REST endpoint for Specialist records (clinic doctors).
 *
 * @details Structure-scoped CRUD with the same behavior as Employees: email validation, unique
 *          code, and name capitalization. Migrated to ORM (Panache) — REST contract unchanged
 *          from legacy.
 */
@RolesAllowed({"ADMIN", "CAPOSALA"})
@Path("/specialists")
@Produces(MediaType.APPLICATION_JSON)
public class SpecialistResource {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static boolean isValidEmail(String email) {
        return email == null || email.isBlank() || EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    /** @brief true if the code is already assigned to a specialist other than excludeId. */
    private static boolean codeInUse(String code, int excludeId) {
        return SpecialistEntity.count("code = ?1 and id != ?2", code, excludeId) > 0;
    }

    /**
     * @brief Checks required specialist fields during both creation and editing.
     * @details The schema declares code/first_name/last_name NOT NULL, but NOT NULL does not reject
     *          an empty string: without this check the record is still inserted, and a nameless
     *          specialist appears as an empty option in location-association dropdowns.
     * @return the error response to return, or null if the fields are valid.
     */
    private static Response validateSpecialistFields(Specialist specialist) {
        if (isMissing(specialist.getFirstName())) return ApiErrors.badRequest("SPECIALIST_FIRST_NAME_REQUIRED");
        if (isMissing(specialist.getLastName())) return ApiErrors.badRequest("SPECIALIST_LAST_NAME_REQUIRED");
        if (isMissing(specialist.getCode())) return ApiErrors.badRequest("SPECIALIST_CODE_REQUIRED");
        return null;
    }

    /** @brief True if a required field is absent or consists only of whitespace. */
    private static boolean isMissing(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** @brief List of specialists in the structure. */
    @GET
    public Response getSpecialists(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        List<Specialist> specialists = SpecialistEntity.<SpecialistEntity>list(
                "structureId = ?1", Sort.by("lastName").and("firstName"), structureId)
                .stream().map(SpecialistEntity::toDto).toList();
        return Response.ok(specialists).build();
    }

    /** @brief Next sequential code (for example, "SPE023"), max over the SPE<number> pattern. */
    @GET
    @Path("/next-code")
    public Response getNextCode() {
        // Legacy parity (GLOB 'SPE[0-9]*'): prefilter with LIKE, then numeric parsing discards
        // nonconforming codes (same logic as ignoring NumberFormatException).
        int max = SpecialistEntity.<SpecialistEntity>list("code like 'SPE%'").stream()
                .map(s -> s.code.substring(3))
                .mapToInt(numPart -> {
                    try { return Integer.parseInt(numPart); } catch (NumberFormatException e) { return 0; }
                })
                .max().orElse(0);
        return Response.ok("{\"code\": \"" + String.format("SPE%03d", max + 1) + "\"}").build();
    }

    /** @brief Single specialist by ID. */
    @GET
    @Path("/{id}")
    public Response getSpecialist(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        SpecialistEntity entity = SpecialistEntity.findById(id);
        if (entity == null || structureId <= 0 || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(entity.toDto()).build();
    }

    /** @brief Creates a new specialist in the specified structure. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response addSpecialist(Specialist specialist, @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (StructureEntity.count("id", structureId) == 0)
            return Response.status(Response.Status.NOT_FOUND).build();
        if (specialist == null) return Response.status(Response.Status.BAD_REQUEST).build();
        Response invalid = validateSpecialistFields(specialist);
        if (invalid != null) return invalid;
        if (!isValidEmail(specialist.getEmail())) {
            return ApiErrors.badRequest("SPECIALIST_EMAIL_INVALID");
        }
        if (specialist.getEmail() != null) specialist.setEmail(specialist.getEmail().trim());
        if (codeInUse(specialist.getCode().trim(), 0)) {
            return ApiErrors.conflict("SPECIALIST_CODE_IN_USE");
        }
        SpecialistEntity entity = new SpecialistEntity();
        entity.applyDto(specialist);
        entity.structureId = structureId;
        entity.persist();
        return Response.status(Response.Status.CREATED).build();
    }

    /** @brief Updates an existing specialist. */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateSpecialist(@PathParam("id") int id, Specialist specialist,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (specialist == null || structureId <= 0 || specialist.getId() != id) {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch").build();
        }
        Response invalid = validateSpecialistFields(specialist);
        if (invalid != null) return invalid;
        if (!isValidEmail(specialist.getEmail())) {
            return ApiErrors.badRequest("SPECIALIST_EMAIL_INVALID");
        }
        if (specialist.getEmail() != null) specialist.setEmail(specialist.getEmail().trim());
        if (codeInUse(specialist.getCode().trim(), id)) {
            return ApiErrors.conflict("SPECIALIST_CODE_IN_USE");
        }
        SpecialistEntity entity = SpecialistEntity.findById(id);
        if (entity == null || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        entity.applyDto(specialist); // Managed: flush at transaction end (structure_id unchanged, as in legacy).
        return Response.ok().build();
    }

    /** @brief Deletes a specialist by ID (cascading to affinities). */
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteSpecialist(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (structureId <= 0 || SpecialistEntity.count(
                "id = ?1 and structureId = ?2", id, structureId) == 0)
            return Response.status(Response.Status.NOT_FOUND).build();
        boolean deleted = SpecialistEntity.deleteById(id);
        if (deleted) {
            // Application-level ORM cascade: also required for legacy SQLite databases that do
            // not enforce FKs consistently. Remains atomic with the deletion.
            AffinityEntity.delete("specialistId", id);
            LocationEntity.update("specialistId = null where specialistId = ?1", id);
        }
        return deleted ? Response.status(Response.Status.NO_CONTENT).build()
                       : Response.status(Response.Status.NOT_FOUND).build();
    }
}
