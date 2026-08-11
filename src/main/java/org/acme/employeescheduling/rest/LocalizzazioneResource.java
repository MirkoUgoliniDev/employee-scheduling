package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import io.quarkus.panache.common.Sort;
import org.acme.employeescheduling.dto.Localizzazione;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.security.RichHtmlSanitizer;

/**
 * @brief Per-entity translations (UI labels, skill/location names) — migrated to ORM (Panache).
 *
 * @details Unchanged contract: GET lists by (entityType, entityId); PUT uses replacement semantics
 *          (delete + insert in a transaction). Writes to labels/skills/locations invalidate the
 *          /translations cache (legacy rule).
 */
@RolesAllowed("ADMIN")
@Path("/localizzazioni")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LocalizzazioneResource {

    /** @brief Only for the translation cache (CRUD uses Panache). */
    private final DemoDataRepository repo;

    @Inject
    public LocalizzazioneResource(DemoDataRepository repo) {
        this.repo = repo;
    }

    @GET
    @Path("/{entityType}/{entityId}")
    public Response get(@PathParam("entityType") String entityType,
            @PathParam("entityId") int entityId,
            @QueryParam("structureId") Integer structureId) {
        Response ownershipFailure = validateLocationOwnership(entityType, entityId, structureId);
        if (ownershipFailure != null)
            return ownershipFailure;
        List<Localizzazione> list = LocalizzazioneEntity.<LocalizzazioneEntity>list(
                "entityType = ?1 and entityId = ?2", Sort.by("languageId"), entityType, entityId)
                .stream().map(LocalizzazioneEntity::toDto).toList();
        return Response.ok(list).build();
    }

    @PUT
    @Path("/{entityType}/{entityId}")
    @Transactional
    public Response save(@PathParam("entityType") String entityType,
            @PathParam("entityId") int entityId,
            @QueryParam("structureId") Integer structureId,
            List<Localizzazione> items) {
        if (items == null)
            items = List.of();
        if (!("labels".equals(entityType) || "skills".equals(entityType) || "locations".equals(entityType))
                || entityId <= 0
                || items.stream().anyMatch(loc -> loc == null || loc.getLanguageId() <= 0 || loc.getValue() == null)) {
            return ApiErrors.badRequest("LOCALIZATION_PAYLOAD_INVALID");
        }
        Response ownershipFailure = validateLocationOwnership(entityType, entityId, structureId);
        if (ownershipFailure != null)
            return ownershipFailure;
        boolean targetExists = switch (entityType) {
            case "labels" -> LabelEntity.count("id", entityId) > 0;
            case "skills" -> SkillEntity.count("id", entityId) > 0;
            case "locations" -> LocationEntity.count("id", entityId) > 0;
            default -> false;
        };
        if (!targetExists)
            return Response.status(Response.Status.NOT_FOUND).build();
        if (items.stream().anyMatch(loc -> LanguageEntity.count("id", loc.getLanguageId()) == 0)) {
            return ApiErrors.badRequest("LOCALIZATION_LANGUAGE_UNKNOWN");
        }
        // Transactional replacement: validate the complete payload before deletion, so a
        // malformed item cannot leave a partial replacement.
        LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", entityType, entityId);
        for (Localizzazione loc : items) {
            LocalizzazioneEntity entity = new LocalizzazioneEntity();
            entity.entityType = entityType;
            entity.entityId = entityId;
            entity.fieldName = loc.getFieldName() != null ? loc.getFieldName() : "value";
            entity.languageId = loc.getLanguageId();
            // Values are served to anonymous clients (/translations) and rendered with
            // dangerouslySetInnerHTML on the home page: sanitize on write (server authority).
            entity.value = RichHtmlSanitizer.sanitize(loc.getValue());
            entity.persist();
        }
        // UI labels + dynamic names (skills, locations) all live in the /translations map.
        if ("labels".equals(entityType) || "skills".equals(entityType) || "locations".equals(entityType)) {
            repo.invalidateTranslationsAfterCommit();
        }
        return Response.ok().build();
    }

    /**
     * Locations are tenant-scoped: ID and structureId must always identify the same row.
     */
    private Response validateLocationOwnership(String entityType, int entityId, Integer structureId) {
        if (!"locations".equals(entityType))
            return null;
        if (structureId == null || structureId <= 0) {
            return ApiErrors.badRequest("STRUCTURE_ID_REQUIRED");
        }
        if (LocationEntity.count("id = ?1 and structureId = ?2", entityId, structureId) == 0) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return null;
    }
}
