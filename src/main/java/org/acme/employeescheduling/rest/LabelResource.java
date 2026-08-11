package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.quarkus.panache.common.Sort;
import org.acme.employeescheduling.dto.Label;
import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.acme.employeescheduling.security.RichHtmlSanitizer;

/**
 * @brief i18n label CRUD — migrated to ORM (Panache), contract unchanged.
 *
 * @details Composite reads use shared Panache entities. Writes invalidate the
 *          DemoDataRepository translation cache (same rule as legacy).
 */
@RolesAllowed("ADMIN")
@Path("/labels")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LabelResource {

    /** @brief Only for the translation cache (CRUD uses Panache). */
    private final DemoDataRepository repo;

    @Inject
    public LabelResource(DemoDataRepository repo) {
        this.repo = repo;
    }

    @GET
    public Response getAll() {
        List<Label> labels = LabelEntity.<LabelEntity>listAll(Sort.by("labelKey"))
                .stream().map(LabelEntity::toDto).toList();
        return Response.ok(labels).build();
    }

    @GET
    @Path("/localized")
    public Response getLocalized() {
        LanguageEntity active = LanguageEntity.find("active", true).firstResult();
        Map<Integer, LocalizzazioneEntity> translations = active == null ? Map.of()
                : LocalizzazioneEntity.<LocalizzazioneEntity>list(
                        "entityType = ?1 and fieldName = ?2 and languageId = ?3",
                        "labels", "value", active.id).stream().collect(Collectors.toMap(
                                row -> row.entityId, Function.identity(), (first, ignored) -> first));
        List<Label> labels = LabelEntity.<LabelEntity>listAll(Sort.by("labelKey")).stream().map(entity -> {
            LocalizzazioneEntity translation = translations.get(entity.id);
            return new Label(entity.id, entity.labelKey, entity.description,
                    translation != null ? translation.value : "");
        }).toList();
        return Response.ok(labels).build();
    }

    /**
     * @brief Pseudo-labels for localizable entity names (skill.&lt;id&gt;, location.&lt;id&gt;),
     *        merged into the `/labels` list on the Localizations page.
     */
    @GET
    @Path("/dynamic-names")
    public Response getDynamicNames(@QueryParam("structureId") int structureId) {
        if (structureId <= 0) return ApiErrors.badRequest("STRUCTURE_ID_REQUIRED");
        if (StructureEntity.count("id", structureId) == 0) return Response.status(Response.Status.NOT_FOUND).build();
        List<Label> list = new ArrayList<>();
        for (SkillEntity entity : SkillEntity.<SkillEntity>listAll(Sort.by("name").and("id")))
            addEntityNameLabel(list, "skills", "skill", "Nome competenza", entity.id, entity.name);
        for (LocationEntity entity : LocationEntity.<LocationEntity>list(
                "structureId", Sort.by("name").and("id"), structureId))
            addEntityNameLabel(list, "locations", "location", "Nome sede", entity.id, entity.name);
        return Response.ok(list).build();
    }

    private void addEntityNameLabel(List<Label> list, String entityType, String keyPrefix,
                                    String descPrefix, int id, String name) {
        Label label = new Label(id, keyPrefix + "." + id, descPrefix + ": " + (name != null ? name : ""));
        label.setEntityType(entityType);
        label.setEntityId(id);
        list.add(label);
    }

    @POST
    @Transactional
    public Response add(Label label) {
        if (label == null || blank(label.getKey()) || blank(label.getDescription()))
            return ApiErrors.badRequest("LABEL_KEY_DESCRIPTION_REQUIRED");
        // Label texts are served to anonymous clients (/translations) and rendered with
        // dangerouslySetInnerHTML on the home page: the server is the authority, the client
        // (DOMPurify) is only a convenience layer.
        label.setDescription(RichHtmlSanitizer.sanitize(label.getDescription()));
        LabelEntity entity = new LabelEntity();
        entity.applyDto(label);
        try {
            entity.persistAndFlush(); // Explicit flush: UNIQUE on key must fail HERE (legacy -1 -> 500 parity).
        } catch (Exception e) {
            return Response.serverError().entity(java.util.Map.of("error", "LABEL_INSERT_FAILED"))
                    .type(MediaType.APPLICATION_JSON).build();
        }
        repo.invalidateTranslationsAfterCommit();
        label.setId(entity.id);
        saveLabelTranslations(entity.id, label.getTranslations());
        return Response.status(Response.Status.CREATED).entity(label).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") int id, Label label) {
        if (label == null || blank(label.getKey()) || blank(label.getDescription()))
            return ApiErrors.badRequest("LABEL_KEY_DESCRIPTION_REQUIRED");
        label.setDescription(RichHtmlSanitizer.sanitize(label.getDescription()));
        LabelEntity entity = LabelEntity.findById(id);
        if (entity == null)
            return ApiErrors.notFound("LABEL_NOT_FOUND");
        entity.applyDto(label);
        saveLabelTranslations(id, label.getTranslations());
        repo.invalidateTranslationsAfterCommit();
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") int id) {
        // Cascade over translations, then delete label. As in legacy: always 200, even for a
        // nonexistent ID.
        LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", "labels", id);
        LabelEntity.deleteById(id);
        repo.invalidateTranslationsAfterCommit();
        return Response.ok().build();
    }

    private boolean blank(String s) { return s == null || s.isBlank(); }

    private void saveLabelTranslations(int labelId, Map<Integer, String> translations) {
        // Transactional replacement, as in /localizzazioni: deletion must always happen, even with
        // an empty/absent map; otherwise attempting to clear all label translations leaves them stale.
        LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", "labels", labelId);
        if (translations == null) return;
        for (var entry : translations.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            var row = new LocalizzazioneEntity();
            row.entityType = "labels";
            row.entityId = labelId;
            row.fieldName = "value";
            row.languageId = entry.getKey();
            row.value = RichHtmlSanitizer.sanitize(entry.getValue());
            row.persist();
        }
    }
}
