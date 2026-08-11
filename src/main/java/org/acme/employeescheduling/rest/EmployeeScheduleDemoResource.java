package org.acme.employeescheduling.rest;


import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.dto.Skill;
import org.acme.employeescheduling.dto.ShiftTemplate;
import org.acme.employeescheduling.dto.Location;
import org.acme.employeescheduling.dto.ShiftWithLocations;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.EmployeeDate;
import org.acme.employeescheduling.dto.PdfTemplate;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.LocationSkillEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.AffinityEntity;
import org.acme.employeescheduling.persistence.EmailLogEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateSkillEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateHeaderEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.acme.employeescheduling.security.RichHtmlSanitizer;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.jboss.logging.Logger;







/**
 * @brief REST resource for managing demo data including skills, locations, shifts, employees, and dates.
 * @details Provides JAX-RS endpoints for full CRUD operations on scheduling demo data.
 *          Supports generating unsolved schedules, managing skills, locations with
 *          associated skills, shifts with required/optional competencies, employees
 *          with skill associations, and employee/shift date management.
 * @author Employee Scheduling Team
 * @version 1.0
 */
@Tag(name = "Demo data", description = "Endpoints for managing demo data and skills.")
@RolesAllowed({"ADMIN", "CAPOSALA"})
@Path("/demo-data")
@Produces(MediaType.APPLICATION_JSON)
public class EmployeeScheduleDemoResource {


private final DemoDataRepository demoDataRepository;

/** @brief Safety backup before destructive operations (best effort). */
@Inject
DatabaseBackupService backupService;

/** Entity manager used only for portable JPQL/HQL projections. */
@Inject
EntityManager em;

private static final Logger logger = Logger.getLogger(EmployeeScheduleDemoResource.class);
private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
/** Maximum window to which a template can be applied: the UI works by week/month. */
private static final int MAX_TEMPLATE_WINDOW_DAYS = 62;
/** @brief DB datetime format (same dbFormatter as the legacy repository). */
private static final java.time.format.DateTimeFormatter DB_FORMATTER =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

private static boolean isValidEmail(String email) {
    return email == null || email.isBlank() || EMAIL_PATTERN.matcher(email.trim()).matches();
}

/** @brief True if a required field is absent or consists only of whitespace. */
private static boolean isMissing(String value) {
    return value == null || value.trim().isEmpty();
}

/**
 * @brief Checks required employee fields during both creation and editing.
 * @details First name, last name, and code identify the person on every screen (shift grid,
 *          PDF report, email). Accepting empty values creates indistinguishable anonymous rows
 *          that the user can no longer correct from the list.
 * @return the error response to return, or null if the fields are valid.
 */
private static Response validateEmployeeFields(Employee employee) {
    if (isMissing(employee.getFirstName())) return ApiErrors.badRequest("EMPLOYEE_FIRST_NAME_REQUIRED");
    if (isMissing(employee.getLastName())) return ApiErrors.badRequest("EMPLOYEE_LAST_NAME_REQUIRED");
    if (isMissing(employee.getCode())) return ApiErrors.badRequest("EMPLOYEE_CODE_REQUIRED");
    return null;
}


/**
 * @brief Constructs the demo resource with an injected DemoDataRepository.
 * @param demoDataRepository the repository providing database access for demo data
 */
@Inject
public EmployeeScheduleDemoResource(DemoDataRepository demoDataRepository) {
    this.demoDataRepository = demoDataRepository;
}

    


    
    

    
/**
 * @brief Generates an unsolved demo schedule from database data.
 * @return a Response containing the generated EmployeeSchedule in JSON format
 */
@APIResponse(
    responseCode = "200",
description = "Unsolved demo schedule.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = EmployeeSchedule.class))
)
@Operation(summary = "Generate an unsolved demo schedule using data from the database.")
@GET
@Path("/generate")
public Response generateDemoSchedule(
        @QueryParam("structureId") @DefaultValue("0") int structureId,
        @QueryParam("start") String start,
        @QueryParam("end") String end,
        @QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly,
        @QueryParam("context") @DefaultValue("false") boolean context) {
    // Optional start/end: when present, shifts are filtered to the window; when absent, all
    // shifts are returned (backward compatible with Locations/Employees/Report).
    // activeOnly=true (Shift Management + solver): excludes disabled employees/locations.
    // context=true (solver payload only): adds shifts adjacent to the window as pinned context
    // (SolverSettings.context_days), marked context=true.
    LocalDateTime windowStart = parseWindowBound(start);
    LocalDateTime windowEnd = parseWindowBound(end);
    boolean hasStart = start != null && !start.isBlank();
    boolean hasEnd = end != null && !end.isBlank();
    if (hasStart != hasEnd || (hasStart && (windowStart == null || windowEnd == null))) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("message", "start and end must be valid date-time bounds and must be provided together"))
                .build();
    }
    if (windowStart != null && !windowEnd.isAfter(windowStart)) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("message", "end must be after start"))
                .build();
    }
    EmployeeSchedule schedule = demoDataRepository.generateDemoData(structureId, windowStart, windowEnd, activeOnly, context);
    return Response.ok(schedule).build();
}

/**
 * @brief Returns the first/last shift date for a structure (min/max), lightweight.
 * @details The frontend uses it to position the timeline on the month of the first shift without
 *          downloading every shift.
 */
@GET
@Path("/shift-date-range")
public Response getShiftDateRange(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(demoDataRepository.getShiftDateRange(structureId)).build();
}

/** Lightweight list of employee/location IDs referenced by shifts. */
@GET
@Path("/usage")
public Response getScheduleUsage(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(demoDataRepository.getScheduleUsage(structureId)).build();
}

/** @brief Days (yyyy-MM-dd) with at least one shift, highlighted in the Configuration calendar. */
@GET
@Path("/shift-days")
public Response getShiftDays(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(demoDataRepository.getShiftDays(structureId)).build();
}

/**
 * @brief Parses a window bound tolerating multiple formats; null/blank/invalid becomes null.
 * @details Accepts "yyyy-MM-dd HH:mm:ss" and ISO "yyyy-MM-ddTHH:mm:ss", with optional 'Z' and fractions.
 */
private static LocalDateTime parseWindowBound(String value) {
    if (value == null || value.isBlank()) return null;
    String v = value.trim().replace(' ', 'T');
    if (v.endsWith("Z")) v = v.substring(0, v.length() - 1);
    int dot = v.indexOf('.');
    if (dot > 0) v = v.substring(0, dot); // Remove milliseconds.
    try {
        return LocalDateTime.parse(v); // ISO_LOCAL_DATE_TIME (optional seconds).
    } catch (Exception e) {
        return null;
    }
}

    
    
   
    
    
    
    
/**
 * @brief Retrieves all available skills.
 * @return a Response containing a list of Skill objects in JSON format
 */
@APIResponse(
     responseCode = "200",
     description = "List of all skills.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Skill.class))
)
@Operation(summary = "Retrieve a list of all skills.")
@GET
@Path("/get_skills")
    public Response getSkills(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        // ORM (Panache): same response as legacy (ordered by skill_order, fixed used=true as in
        // the old getSkills — see SkillEntity.toDto()).
        List<Skill> skills = listSkillsForStructure(structureId);
        return Response.ok(skills).build();
    }









/**
 * @brief Saves a list of skills (insert new or update existing).
 *
 * @details ADMIN only, overriding the class-level annotation. The skill catalogue is
 *          administrative: head nurses assign skills, they do not create or rename them.
 *          That was enforced only in the interface — ConfigPage redirects non-admins — so
 *          a head nurse with curl could create and rename skills for the whole structure.
 *          LocalizzazioneResource, which renames the same skills in five languages, has
 *          been ADMIN-only from the start.
 *
 * @param skills the list of Skill objects to save
 * @return a Response indicating success or failure of the save operation
 */
@POST
@RolesAllowed("ADMIN")
@Path("/save_skills")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
    public Response saveSkills(List<Skill> skills,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {

    logger.debug("JSON ricevuto dal frontend: " + skills);

        if (skills == null || skills.stream().anyMatch(skill -> skill == null)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "Payload skills non valido")).build();
        }
        if (structureId <= 0 || !structureExists(structureId)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "Struttura non valida")).build();
        }

        try {
            // ORM (Panache): batch upsert with legacy semantics — id==0 = new skill (INSERT),
            // id!=0 = update; a nonexistent ID is silently ignored (like the legacy UPDATE
            // affecting zero rows).
            for (Skill skill : skills) {
            SkillEntity entity = null;
            if (skill.getId() == 0) {
                entity = new SkillEntity();
                entity.structureId = structureId;
                entity.applyDto(skill);
                entity.persist();
            } else {
                entity = SkillEntity.findById(skill.getId());
                // Skill from another structure: ignore it rather than modify it. The catalog used
                // to be shared, so renaming here changed it for every structure; now each sees and
                // modifies only its own skills.
                if (entity != null
                        && (entity.structureId == null || entity.structureId != structureId)) {
                    continue;
                }
                if (entity != null) entity.applyDto(skill);
            }
                if (skill.getTranslationLanguageId() != null && skill.getTranslationValue() != null
                        && !skill.getTranslationValue().isBlank()) {
                    upsertSkillTranslation(entity != null ? entity.id : skill.getId(),
                            skill.getTranslationLanguageId(), skill.getTranslationValue());
                }
            }

        // Cached employees incorporate the complete skill catalog: rename/reorder/activation
        // must regenerate them (same rule as legacy).
        demoDataRepository.invalidateEmployeesAfterCommit();
        demoDataRepository.invalidateTranslationsAfterCommit();
        return Response.ok().entity(Map.of("message", "Skills salvate con successo!")).build();

    } catch (Exception e) {

        logger.error("Errore nel salvataggio delle skills", e);

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("message", "Errore nel salvataggio delle skills")).build();

    }
}












/**
 * @brief Retrieves all available locations.
 * @return a Response containing a list of Location objects in JSON format
 */
@APIResponse(
	responseCode = "200",
description = "List of all locations.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Location.class))
)
@Operation(summary = "Retrieve a list of all locations.")
@GET
@Path("/getlocations")
public Response getLocations(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(buildLocationDtos(structureId)).build();
}

/**
 * @brief Structure locations ordered by l_order, with ONLY associated skills (used=true) ordered
 *        by skill_order — same shape as legacy getLocations. Reused by /getlocations and
 *        /editshift (ShiftWithLocations).
 */
private List<Location> buildLocationDtos(int structureId) {
    List<LocationEntity> entities = LocationEntity.<LocationEntity>list(
            "structureId = ?1", Sort.by("displayOrder"), structureId);
    Map<Integer, Location> byId = new HashMap<>();
    List<Location> locations = new ArrayList<>();
    for (LocationEntity e : entities) {
        Location dto = e.toDto();
        dto.setRequiredSkill(new ArrayList<>());
        dto.setOptionalSkill(new ArrayList<>());
        byId.put(e.id, dto);
        locations.add(dto);
    }
    // Theta-join JPQL bridge×skills×locations (no @ManyToOne in the bridge).
    List<Object[]> rows = em.createQuery(
            "select ls.locationId, ls.skillTypeId, s.id, s.name, s.skillOrder, s.active " +
            "from LocationSkillEntity ls, SkillEntity s, LocationEntity l " +
            "where s.id = ls.skillId and l.id = ls.locationId and l.structureId = ?1 " +
            "order by s.skillOrder", Object[].class)
            .setParameter(1, structureId).getResultList();
    for (Object[] r : rows) {
        Location location = byId.get(((Number) r[0]).intValue());
        if (location == null) continue;
        Skill skill = new Skill(((Number) r[2]).intValue(), (String) r[3],
                r[4] != null ? ((Number) r[4]).intValue() : 0, true, (Boolean) r[5]);
        if (((Number) r[1]).intValue() == LocationSkillEntity.TYPE_REQUIRED) location.getRequiredSkill().add(skill);
        else location.getOptionalSkill().add(skill);
    }
    return locations;
}







/**
 * @brief Adds a new location with its required and optional skills.
 * @details Validates the location name and order, persists the location,
 *          and associates required and optional skills if provided.
 * @param location the Location object containing name, order, and skills
 * @return a Response with HTTP 201 on success, or HTTP 400 for invalid data
 */
@POST
@Path("/addlocation")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response addLocation(Location location, @QueryParam("structureId") @DefaultValue("0") int structureId) {
	if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
	if (location == null) return Response.status(Response.Status.BAD_REQUEST).build();
	if (location.getSpecialistId() != null && org.acme.employeescheduling.persistence.SpecialistEntity.count(
			"id = ?1 and structureId = ?2", location.getSpecialistId(), structureId) == 0)
		return ApiErrors.badRequest("LOCATION_SPECIALIST_INVALID");

	if (isMissing(location.getName())) {
	    return ApiErrors.badRequest("LOCATION_NAME_REQUIRED");
	}
	if (location.getOrder() <= 0) {
	    return ApiErrors.badRequest("LOCATION_ORDER_REQUIRED");
	}
	if (!validSkillLists(location.getRequiredSkill(), location.getOptionalSkill())) {
	    return ApiErrors.badRequest("LOCATION_SKILLS_INVALID");
	}

	if (location.getCode() != null && !location.getCode().trim().isEmpty()
	        && LocationEntity.count("code = ?1 and id != ?2", location.getCode().trim(), 0) > 0) {
	    return ApiErrors.conflict("LOCATION_CODE_IN_USE");
	}

	logger.debug("Payload ricevuto: " + location);

    // ORM (Panache): insert location + associated skills in ONE transaction (legacy used separate
    // connections). active keeps the true default (parity: the legacy INSERT did not set the
    // column -> default 1).
    LocationEntity entity = new LocationEntity();
    entity.name = location.getName();
    entity.displayOrder = location.getOrder();
    entity.code = location.getCode();
    entity.specialistId = location.getSpecialistId();
    entity.structureId = structureId;
    entity.persist();

    addLocationSkillRows(entity.id, location.getRequiredSkill(), LocationSkillEntity.TYPE_REQUIRED);
    addLocationSkillRows(entity.id, location.getOptionalSkill(), LocationSkillEntity.TYPE_OPTIONAL);

    // ID only: the frontend composes the success message in the user's language.
    return Response.status(Response.Status.CREATED).entity(Map.of("id", entity.id)).build();

}

/**
 * @brief Associates skills with a location (id>0 filter as in legacy; null/empty ignored).
 * @details The structure is derived from the location, not the request parameter: identifiers
 *          come from the client, and a skill belonging to another company must be discarded,
 *          not associated.
 */
private static void addLocationSkillRows(int locationId, List<Skill> skills, int skillTypeId) {
    if (skills == null || skills.isEmpty()) return;
    java.util.Set<Integer> owned = ownedSkillIds(structureOfLocation(locationId));
    for (Skill skill : skills) {
        if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
        LocationSkillEntity row = new LocationSkillEntity();
        row.locationId = locationId;
        row.skillId = skill.getId();
        row.skillTypeId = skillTypeId;
        row.persist();
    }
}







/**
 * @brief Retrieves a specific location by its ID.
 * @param location_id the unique identifier of the location
 * @return a Response containing the Location in JSON, or HTTP 404 if not found
 */
@GET
@Path("/getlocation/{id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getLocation(@PathParam("id") int location_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    LocationEntity entity = LocationEntity.findById(location_id);
    if (entity == null || structureId <= 0 || entity.structureId != structureId) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // Legacy parity: requiredSkill/optionalSkill = ENTIRE CATALOG with used=true only on skills
    // associated with that type (needed by the edit modal).
    Location location = entity.toDto();
    location.setRequiredSkill(catalogWithUsedFlag(location_id, LocationSkillEntity.TYPE_REQUIRED));
    location.setOptionalSkill(catalogWithUsedFlag(location_id, LocationSkillEntity.TYPE_OPTIONAL));
    return Response.ok(location).build();
}

/** @brief Complete skill catalog (natural order), with used=true on associations of the given type. */
private static List<Skill> catalogWithUsedFlag(int locationId, int skillTypeId) {
    java.util.Set<Integer> usedIds = LocationSkillEntity.<LocationSkillEntity>list(
            "locationId = ?1 and skillTypeId = ?2", locationId, skillTypeId)
            .stream().map(ls -> ls.skillId).collect(java.util.stream.Collectors.toSet());
    // Catalog of the location's structure: with listAll(), the modal would also show skills from
    // other companies, which cannot even be assigned here.
    return SkillEntity.<SkillEntity>list("structureId", structureOfLocation(locationId)).stream()
            .map(s -> new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active))
            .toList();
}






/**
 * @brief Updates an existing location by its ID.
 * @details Validates that the path ID matches the body ID, then delegates
 *          the update to the repository.
 * @param location_id the unique identifier of the location to update
 * @param location the updated Location data
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
    responseCode = "200",
    description = "Location updated successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update Location with id.")
@PUT
@Path("/updatelocation/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateLocation(@PathParam("id") int location_id, Location location,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	if (location == null || structureId <= 0 || location.getId() != location_id) {
	    return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch").build();
	}

	if (location.getCode() != null && !location.getCode().trim().isEmpty()
	        && LocationEntity.count("code = ?1 and id != ?2", location.getCode().trim(), location_id) > 0) {
	    return ApiErrors.conflict("LOCATION_CODE_IN_USE");
	}

	LocationEntity entity = LocationEntity.findById(location_id);
	if (entity == null || entity.structureId != structureId) {
	    return Response.status(Response.Status.NOT_FOUND).build();
	}
	// Same checks as creation, but reported individually: editing is where the user corrects an
	// error and needs to know WHICH field was rejected.
	if (isMissing(location.getName())) {
	    return ApiErrors.badRequest("LOCATION_NAME_REQUIRED");
	}
	if (location.getOrder() <= 0) {
	    return ApiErrors.badRequest("LOCATION_ORDER_REQUIRED");
	}
	if (!validSkillLists(location.getRequiredSkill(), location.getOptionalSkill())) {
	    return ApiErrors.badRequest("LOCATION_SKILLS_INVALID");
	}
	if (location.getSpecialistId() != null
	        && org.acme.employeescheduling.persistence.SpecialistEntity.count(
	            "id = ?1 and structureId = ?2", location.getSpecialistId(), structureId) == 0) {
	    return ApiErrors.badRequest("LOCATION_SPECIALIST_INVALID");
	}
	// Validate every reference before replacing skills.
	entity.applyDto(location); // Do NOT change structure_id (legacy parity).
	LocationSkillEntity.delete("locationId", location_id);
	addLocationSkillRows(location_id, location.getRequiredSkill(), LocationSkillEntity.TYPE_REQUIRED);
	addLocationSkillRows(location_id, location.getOptionalSkill(), LocationSkillEntity.TYPE_OPTIONAL);
	return Response.ok().build();

}





/**
 * @brief Deletes a location by its ID.
 * @param id the unique identifier of the location to delete
 * @return a Response with HTTP 204 on success, HTTP 409 if the location is still
 *         referenced by shifts or shift templates, or HTTP 404 if not found
 */
@DELETE
@Path("/deletelocation/{id}")
@Transactional
public Response deleteLocation(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!locationBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    long refs = ShiftEntity.count("locationId", id) + ShiftTemplateEntity.count("locationId", id);
    if (refs > 0) {
        return ApiErrors.conflict("LOCATION_IN_USE");
    }
    // Cascade (associated skills + name translations) + location deletion in ONE transaction
    // (legacy kept localizations and skills in the same transaction, with the check outside).
    LocationSkillEntity.delete("locationId", id);
    LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", "locations", id);
    boolean isDeleted = LocationEntity.deleteById(id);
    if (isDeleted) {
        return Response.status(Response.Status.NO_CONTENT).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}




/**
 * @brief Retrieves required and optional skills available for location assignment.
 * @return a Response containing a map with "requiredSkills" and "optionalSkills" lists
 */
@GET
@Path("/skills-for-location")
@Produces(MediaType.APPLICATION_JSON)
    public Response getSkillsForLocation(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        // ORM (Panache): complete catalog for both types (legacy parity with
        // getRequiredSkills/getOptionalSkills: same query, fixed used=true).
        Map<String, List<Skill>> skillsForShift = new HashMap<>();
        List<Skill> catalog = listSkillsForStructure(structureId);
        skillsForShift.put("requiredSkills", catalog);
        skillsForShift.put("optionalSkills", catalog);
        return Response.ok(skillsForShift).build();
    }

    /**
     * @brief Structure skill catalog, ordered by skill_order.
     * @details Skills belong to one structure (migration V5): without structureId there is no
     *          meaningful catalog to return, and a global list would mix skills from different
     *          companies. Return an empty list.
     */
    private List<Skill> listSkillsForStructure(int structureId) {
        if (structureId <= 0) return new ArrayList<>();
        return SkillEntity.<SkillEntity>list("structureId", Sort.by("skillOrder"), structureId).stream()
                .map(SkillEntity::toDto).toList();
    }

    /** @brief true if the skill exists and belongs to that structure. */
    private static boolean skillBelongsToStructure(int skillId, int structureId) {
        if (skillId <= 0 || structureId <= 0) return false;
        return SkillEntity.count("id = ?1 and structureId = ?2", skillId, structureId) > 0;
    }

    /**
     * @brief Identifiers of skills owned by the structure.
     *
     * @details Filter applied to EVERY assignment (employees, locations, shifts, templates):
     *          identifiers come from the client, and since skills belong to a structure, an ID
     *          from another company is no longer legitimate. The interface does not offer it,
     *          but the API would accept it. One query instead of one per skill.
     */
    private static java.util.Set<Integer> ownedSkillIds(int structureId) {
        if (structureId <= 0) return java.util.Set.of();
        return SkillEntity.<SkillEntity>list("structureId", structureId).stream()
                .map(s -> s.id).collect(java.util.stream.Collectors.toSet());
    }

    /** @brief Structure owning a location (0 if the location does not exist). */
    private static int structureOfLocation(int locationId) {
        LocationEntity location = LocationEntity.findById(locationId);
        return location == null ? 0 : location.structureId;
    }

    /** @brief Structure owning a shift, determined through its location. */
    private static int structureOfShift(int shiftId) {
        ShiftEntity shift = ShiftEntity.findById(shiftId);
        return shift == null ? 0 : structureOfLocation(shift.locationId);
    }


/**
 * @brief Returns the next suggested location code (e.g. "LOC015").
 */
@GET
@Path("/next-location-code")
@Produces(MediaType.APPLICATION_JSON)
public Response getNextLocationCode() {
    // Legacy parity (GLOB 'LOC[0-9]*'): prefilter with LIKE; parsing discards nonconforming values.
    int max = LocationEntity.<LocationEntity>list("code like 'LOC%'").stream()
            .map(l -> l.code.substring(3))
            .mapToInt(numPart -> {
                try { return Integer.parseInt(numPart); } catch (NumberFormatException e) { return 0; }
            })
            .max().orElse(0);
    return Response.ok("{\"code\": \"" + String.format("LOC%03d", max + 1) + "\"}").build();
}

/**
 * @brief Returns the next suggested employee code (e.g. "EMP023").
 */
@GET
@Path("/next-employee-code")
@Produces(MediaType.APPLICATION_JSON)
public Response getNextEmployeeCode() {
    // Legacy parity (GLOB 'EMP[0-9]*'): prefilter with LIKE; parsing discards nonconforming values.
    int max = EmployeeEntity.<EmployeeEntity>list("code like 'EMP%'").stream()
            .map(e -> e.code.substring(3))
            .mapToInt(numPart -> {
                try { return Integer.parseInt(numPart); } catch (NumberFormatException ex) { return 0; }
            })
            .max().orElse(0);
    return Response.ok("{\"code\": \"" + String.format("EMP%03d", max + 1) + "\"}").build();
}






/**
 * @brief Retrieves skills for a specific shift filtered by skill type.
 * @param shift_id the unique identifier of the shift
 * @param skill_type_id the type of skills to retrieve (1 = required, 2 = optional)
 * @return a Response containing a map with a "skills" list, or HTTP 500 on error
 */
@GET
@Path("/getskillsforshift/{shift_id}/{skill_type_id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getSkillsForShiftFromId(@PathParam("shift_id") int shift_id,
        @PathParam("skill_type_id") int skill_type_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	try {
        if (!shiftBelongsToStructure(shift_id, structureId))
            return Response.status(Response.Status.NOT_FOUND).build();
        if (skill_type_id != LocationSkillEntity.TYPE_REQUIRED
                && skill_type_id != LocationSkillEntity.TYPE_OPTIONAL)
            return Response.status(Response.Status.BAD_REQUEST).build();
        // ORM (Panache): catalog with used flag by type (legacy LEFT JOIN parity).
        List<Skill> skills = shiftSkillCatalog(shift_id, skill_type_id);

        Map<String, List<Skill>> skillsForShift = new HashMap<>();

        skillsForShift.put("skills", skills);

        return Response.ok(skillsForShift).build();

    } catch (Exception e) {
        logger.error("Errore durante il recupero delle skill", e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Errore durante il recupero delle skill")).build();
    }
}







/**
 * @brief Retrieves all shifts for a specific location.
 * @param location_id the unique identifier of the location
 * @return a Response containing a list of Shift objects, or HTTP 400/404/500 on error
 */
@APIResponse(
	    responseCode = "200",
	    description = "Get Detail of given Location Shifts.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Shift.class))
	)
	@APIResponse(responseCode = "400", description = "Invalid location ID.")
	@APIResponse(responseCode = "404", description = "No shifts found for the given location.")
	@APIResponse(responseCode = "500", description = "An unexpected error occurred.")
	@Operation(summary = "Retrieve shifts for a specific location.")
	@GET
	@Path("/get_location_shifts/{location_id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getLocationDates(@PathParam("location_id") int location_id,
	        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	    //logger.info("Fetching shifts for Location ID: " + location_id);

	    // Validate input
	    if (location_id <= 0 || !locationBelongsToStructure(location_id, structureId)) {
	        logger.warn("Invalid Location ID: " + location_id);
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid Location ID. Must be a positive integer.")).build();
	    }


	    try {
	        // Retrieve shift details for the given location
	        List<Shift> locationShifts = demoDataRepository.getLocationShiftsOrm(location_id);

	        if (locationShifts == null || locationShifts.isEmpty()) {
	            logger.warn("No shifts found for Shift ID: " + location_id);
	            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No shifts found for the given Shift ID.")).build();
	        }

	        //logger.info("Returning " + locationShifts.size() + " shifts for Location ID: " + location_id);
	        
	        return Response.ok(locationShifts).build();

	    } catch (Exception e) {
	        logger.error("Error fetching shifts for Location ID: " + location_id, e);
	        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "An unexpected error occurred. Please try again later.")).build();
	    }
	    
	}





/**
 * @brief Retrieves a shift and all available locations, marking the currently assigned one.
 * @details Fetches the shift by ID, retrieves all locations, and sets the "used" flag
 *          on the location that matches the shift's assigned location.
 * @param shift_id the unique identifier of the shift to retrieve
 * @return a Response containing a ShiftWithLocations DTO, or HTTP 400/404/500 on error
 */
@APIResponse(
	    responseCode = "200",
description = "Details of the shift and a list of all locations.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ShiftWithLocations.class))
)
@APIResponse(
    responseCode = "400",
description = "Invalid shift ID."
)
@APIResponse(
    responseCode = "404",
description = "Shift not found for the given ID."
)
@APIResponse(
    responseCode = "500",
description = "An unexpected error occurred."
)
@Operation(summary = "Retrieve a shift and its available locations.")
@GET
@Path("/editshift/{id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getLocationDatesByIdShift(@PathParam("id") int shift_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

// Validate input
if (shift_id <= 0 || structureId <= 0) {
    return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift ID. Must be a positive integer.")).build();
}

try {

    // ORM (Panache): shift + location description + skill catalogs with used flags (legacy
    // getShiftById parity: LEFT JOIN of entire catalog by type).
    ShiftEntity entity = ShiftEntity.findById(shift_id);
    if (entity == null || !shiftBelongsToStructure(shift_id, structureId)) {
        logger.warn("No shift found for shift_id: " + shift_id);
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No shift found for the given shift ID.")).build();
    }
    LocationEntity shiftLocation = LocationEntity.findById(entity.locationId);
    Shift shift = new Shift(shift_id,
            EmployeeDateEntity.parseDbDateTime(entity.startTime),
            EmployeeDateEntity.parseDbDateTime(entity.endTime),
            entity.locationId,
            shiftLocation != null ? shiftLocation.name : null,
            shiftSkillCatalog(shift_id, 1),
            shiftSkillCatalog(shift_id, 2),
            null);
    shift.setEmployeeId(entity.employeeId);
    shift.setPinned(entity.pinned);

    // Structure locations (same shape as /getlocations), with the shift's assigned location
    // marked used.
    List<Location> allLocations = buildLocationDtos(structureId);
    allLocations.forEach(location -> {
        location.setUsed(location.getId() == shift.getLocation_id());
    });

    ShiftWithLocations shiftWithLocations = new ShiftWithLocations(shift, allLocations);
    return Response.ok(shiftWithLocations).build();

} catch (Exception e) {
    logger.error("Error fetching shift and locations for shift ID: " + shift_id, e);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "An unexpected error occurred. Please try again later.")).build();
    }

}

/** @brief Complete skill catalog (natural order), with used=true on shift skills by type. */
private static List<Skill> shiftSkillCatalog(int shiftId, int skillTypeId) {
    java.util.Set<Integer> usedIds = ShiftSkillEntity.<ShiftSkillEntity>list(
            "shiftId = ?1 and skillTypeId = ?2", shiftId, skillTypeId)
            .stream().map(ss -> ss.skillId).collect(java.util.stream.Collectors.toSet());
    return SkillEntity.<SkillEntity>listAll().stream()
            .map(s -> new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active))
            .toList();
}

/** @brief Transactionally replaces ONE type of shift skills (id>0, null ignored). */
private static void replaceShiftSkills(int shiftId, List<Skill> skills, int skillTypeId) {
    ShiftSkillEntity.delete("shiftId = ?1 and skillTypeId = ?2", shiftId, skillTypeId);
    if (skills == null) return;
    // Structure determined from the shift through its location: another company's skills cannot
    // be assigned to this shift.
    java.util.Set<Integer> owned = ownedSkillIds(structureOfShift(shiftId));
    for (Skill skill : skills) {
        if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
        ShiftSkillEntity row = new ShiftSkillEntity();
        row.shiftId = shiftId;
        row.skillId = skill.getId();
        row.skillTypeId = skillTypeId;
        row.persist();
    }
}









/**
 * @brief Updates an existing shift by its ID.
 * @details Validates the shift ID and data, then delegates the update
 *          to the repository. Checks that start and end times are valid.
 * @param shift_id the unique identifier of the shift to update
 * @param shift the updated Shift data
 * @return a Response with HTTP 200 on success, or HTTP 400/500 on error
 */
@PUT
@Path("/updateshift/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response updateShift(@PathParam("id") int shift_id, Shift shift,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
	
    //logger.info("Updating shift with ID: " + shift_id);

    if (shift_id <= 0 || structureId <= 0 || shift == null) {
        //logger.warn("Invalid shift_id: " + shift_id);
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift ID. Must be a positive integer.")).build();
    }

    try {
    	
        if (!shiftBelongsToStructure(shift_id, structureId)
                || !locationBelongsToStructure(shift.getLocation_id(), structureId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (shift.getStart() == null || shift.getEnd() == null
                || !validSkillLists(shift.getRequiredSkill(), shift.getOptionalSkill())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift data. Location ID, start, and end times are required.")).build();
        }

        if (!shift.getEnd().isAfter(shift.getStart())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "End time must be after start time.")).build();
        }

        if (!shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Il turno non può essere a cavallo di due giorni.")).build();
        }

        // ORM (Panache): update fields + replace skills (both types) in ONE transaction.
        // Legacy parity: nonexistent ID -> still 200.
        ShiftEntity entity = ShiftEntity.findById(shift_id);
        if (entity != null) {
            entity.locationId = shift.getLocation_id();
            entity.startTime = shift.getStart().format(DB_FORMATTER);
            entity.endTime = shift.getEnd().format(DB_FORMATTER);
            replaceShiftSkills(shift_id, shift.getRequiredSkill(), 1);
            replaceShiftSkills(shift_id, shift.getOptionalSkill(), 2);
        }
        return Response.ok(Map.of("message", "Shift updated successfully.")).build();
    
    } catch (Exception e) {
    	
        logger.error("Error updating shift with ID: " + shift_id, e);
        
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "An unexpected error occurred. Please try again later.")).build();
    }
}



/**
 * @brief Adds a new shift with required and optional skills.
 * @details Extracts skill IDs from the Shift object's skill lists,
 *          handles null skills gracefully, and delegates insertion to the repository.
 * @param shift the Shift object containing location, times, and skills
 * @return a Response with HTTP 201 and the created Shift on success
 */
@POST
@Path("/addshift")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response addShift(Shift shift,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

if (shift == null || !locationBelongsToStructure(shift.getLocation_id(), structureId)
        || shift.getStart() == null || shift.getEnd() == null
        || !shift.getEnd().isAfter(shift.getStart())
        || !validSkillLists(shift.getRequiredSkill(), shift.getOptionalSkill())) {
    return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift data.")).build();
}
if (!shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())) {
    return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Il turno non può essere a cavallo di due giorni.")).build();
}

// ORM (Panache): insert shift + skills (both types) in ONE transaction, with the same semantics
// as legacy addShift (generated ID returned in the DTO).
ShiftEntity entity = new ShiftEntity();
entity.locationId = shift.getLocation_id();
entity.startTime = shift.getStart().format(DB_FORMATTER);
entity.endTime = shift.getEnd().format(DB_FORMATTER);
entity.persist();
shift.setId(entity.id);
replaceShiftSkills(entity.id, shift.getRequiredSkill(), 1);
replaceShiftSkills(entity.id, shift.getOptionalSkill(), 2);

return Response.status(Response.Status.CREATED).entity(shift) .build();
}

// ─── Shift templates (recurring weekly pattern) ────────────────────────────

/** @brief Extracts skill IDs from a Skill list (null-safe). */
private static List<Integer> skillIds(List<Skill> skills) {
    return skills != null ? skills.stream().map(Skill::getId).toList() : List.of();
}

/** @brief All shift templates for the structure. */
@GET
@Path("/shift-templates")
public Response getShiftTemplates(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): only the "working" template (header_id IS NULL), ordered by day+time,
    // with location name and USED skills by type (legacy parity).
    List<ShiftTemplateEntity> entities = ShiftTemplateEntity.list(
            "structureId = ?1 and headerId is null order by dayOfWeek, startTime", structureId);
    return Response.ok(templateDtos(entities)).build();
}

/** @brief Converts template rows into DTOs with location name and used skills (legacy shape). */
private List<ShiftTemplate> templateDtos(List<ShiftTemplateEntity> entities) {
    if (entities.isEmpty()) return new ArrayList<>();
    Map<Integer, LocationEntity> locations = new HashMap<>();
    List<Integer> locationIds = entities.stream().map(e -> e.locationId).distinct().toList();
    for (int from = 0; from < locationIds.size(); from += 900) {
        List<LocationEntity> chunk = LocationEntity.list("id in ?1",
                locationIds.subList(from, Math.min(from + 900, locationIds.size())));
        for (LocationEntity location : chunk) locations.put(location.id, location);
    }
    Map<Integer, Map<Integer, List<Skill>>> skills = new HashMap<>();
    List<Integer> templateIds = entities.stream().map(e -> e.id).toList();
    for (int from = 0; from < templateIds.size(); from += 900) {
        List<Object[]> rows = em.createQuery(
                "select ts.templateId, ts.skillTypeId, s from ShiftTemplateSkillEntity ts, SkillEntity s "
                + "where s.id = ts.skillId and ts.templateId in ?1 order by s.skillOrder", Object[].class)
                .setParameter(1, templateIds.subList(from, Math.min(from + 900, templateIds.size())))
                .getResultList();
        for (Object[] row : rows) {
            SkillEntity s = (SkillEntity) row[2];
            skills.computeIfAbsent(((Number) row[0]).intValue(), ignored -> new HashMap<>())
                    .computeIfAbsent(((Number) row[1]).intValue(), ignored -> new ArrayList<>())
                    .add(new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0, true, s.active));
        }
    }
    List<ShiftTemplate> templates = new ArrayList<>(entities.size());
    for (ShiftTemplateEntity e : entities) {
        LocationEntity loc = locations.get(e.locationId);
        Map<Integer, List<Skill>> byType = skills.getOrDefault(e.id, Map.of());
        templates.add(new ShiftTemplate(
                e.id, e.structureId, e.dayOfWeek, e.startTime, e.endTime,
                e.locationId, loc != null ? loc.name : null,
                byType.getOrDefault(1, List.of()), byType.getOrDefault(2, List.of())));
    }
    return templates;
}

private static boolean structureExists(int structureId) {
    return structureId > 0 && StructureEntity.count("id", structureId) > 0;
}

private static boolean locationBelongsToStructure(int locationId, int structureId) {
    return locationId > 0 && structureId > 0
            && LocationEntity.count("id = ?1 and structureId = ?2", locationId, structureId) > 0;
}

private static boolean employeeBelongsToStructure(int employeeId, int structureId) {
    return employeeId > 0 && structureId > 0
            && EmployeeEntity.count("id = ?1 and structureId = ?2", employeeId, structureId) > 0;
}

private static boolean shiftBelongsToStructure(int shiftId, int structureId) {
    if (shiftId <= 0 || structureId <= 0) return false;
    ShiftEntity shift = ShiftEntity.findById(shiftId);
    return shift != null && locationBelongsToStructure(shift.locationId, structureId);
}

private static boolean employeeDateBelongsToStructure(int dateId, int structureId) {
    if (dateId <= 0 || structureId <= 0) return false;
    EmployeeDateEntity date = EmployeeDateEntity.findById(dateId);
    return date != null && employeeBelongsToStructure(date.employeeId, structureId);
}

/** Semantically validates and deduplicates skill IDs before every replace/delete. */
private static Set<Integer> validatedSkillIds(List<Skill> skills) {
    if (skills == null || skills.isEmpty()) return Set.of();
    Set<Integer> ids = new HashSet<>();
    for (Skill skill : skills) {
        if (skill == null || skill.getId() <= 0 || !ids.add(skill.getId())) return null;
    }
    List<Integer> values = new ArrayList<>(ids);
    long existing = 0;
    for (int from = 0; from < values.size(); from += 900) {
        existing += SkillEntity.count("id in ?1",
                values.subList(from, Math.min(from + 900, values.size())));
    }
    return existing == ids.size() ? ids : null;
}

private static boolean validSkillLists(List<Skill> required, List<Skill> optional) {
    Set<Integer> requiredIds = validatedSkillIds(required);
    Set<Integer> optionalIds = validatedSkillIds(optional);
    if (requiredIds == null || optionalIds == null) return false;
    return requiredIds.stream().noneMatch(optionalIds::contains);
}

private static boolean validDateInterval(EmployeeDate date) {
    return date != null && date.getDateStart() != null && date.getDateEnd() != null
            && date.getDateEnd().isAfter(date.getDateStart())
            && date.getDateTypeId() >= EmployeeDateEntity.TYPE_DESIRED
            && date.getDateTypeId() <= EmployeeDateEntity.TYPE_UNAVAILABLE;
}

private static boolean validDateIntervals(List<EmployeeDate> dates) {
    return dates == null || dates.stream().allMatch(date -> date != null
            && date.getDateStart() != null && date.getDateEnd() != null
            && date.getDateEnd().isAfter(date.getDateStart()));
}

private static boolean validTemplateWindow(LocalDateTime start, LocalDateTime end) {
    return start != null && end != null && start.isBefore(end)
            && java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate())
                    <= MAX_TEMPLATE_WINDOW_DAYS;
}

private static boolean validTemplatePayload(ShiftTemplate tpl, int structureId) {
    if (tpl == null || tpl.getDayOfWeek() < 0 || tpl.getDayOfWeek() > 6
            || !locationBelongsToStructure(tpl.getLocationId(), structureId)
            || !validSkillLists(tpl.getRequiredSkills(), tpl.getOptionalSkills())) return false;
    try {
        java.time.LocalTime start = java.time.LocalTime.parse(tpl.getStartTime());
        java.time.LocalTime end = java.time.LocalTime.parse(tpl.getEndTime());
        return end.isAfter(start);
    } catch (RuntimeException e) {
        return false;
    }
}

/**
 * @brief Replaces/inserts template skills (both lists by type).
 * @details The structure is read from the template itself: another company's skill is discarded
 *          instead of ending up in shifts generated from that template.
 */
private static void insertTemplateSkillRows(int templateId, List<Integer> skillIds, int skillTypeId) {
    if (skillIds == null) return;
    ShiftTemplateEntity template = ShiftTemplateEntity.findById(templateId);
    java.util.Set<Integer> owned = ownedSkillIds(template == null ? 0 : template.structureId);
    for (Integer skillId : skillIds) {
        if (skillId == null || !owned.contains(skillId)) continue;
        ShiftTemplateSkillEntity row = new ShiftTemplateSkillEntity();
        row.templateId = templateId;
        row.skillId = skillId;
        row.skillTypeId = skillTypeId;
        row.persist();
    }
}

/** @brief Creates a shift template. */
@POST
@Path("/shift-template")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response addShiftTemplate(ShiftTemplate tpl, @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    if (!validTemplatePayload(tpl, structureId)
            || (tpl.getHeaderId() != null && ShiftTemplateHeaderEntity.count(
                    "id = ?1 and structureId = ?2", tpl.getHeaderId(), structureId) == 0))
        return Response.status(Response.Status.BAD_REQUEST).build();
    tpl.setStructureId(structureId);
    // ORM (Panache): insert row + skills in ONE transaction (headerId passes through unchanged:
    // null = working template, non-null = row in a saved template).
    ShiftTemplateEntity entity = new ShiftTemplateEntity();
    entity.structureId = structureId;
    entity.dayOfWeek = tpl.getDayOfWeek();
    entity.startTime = tpl.getStartTime();
    entity.endTime = tpl.getEndTime();
    entity.locationId = tpl.getLocationId();
    entity.headerId = tpl.getHeaderId();
    entity.persist();
    insertTemplateSkillRows(entity.id, skillIds(tpl.getRequiredSkills()), 1);
    insertTemplateSkillRows(entity.id, skillIds(tpl.getOptionalSkills()), 2);
    tpl.setId(entity.id);
    return Response.status(Response.Status.CREATED).entity(tpl).build();
}

/** @brief Updates a shift template. */
@PUT
@Path("/shift-template/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateShiftTemplate(@PathParam("id") int id, ShiftTemplate tpl,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): update fields (structure/header unchanged, as in legacy) + complete skill
    // rewrite in the same transaction.
    ShiftTemplateEntity entity = ShiftTemplateEntity.findById(id);
    if (entity == null || structureId <= 0 || entity.structureId != structureId)
        return Response.status(Response.Status.NOT_FOUND).build();
    if (!validTemplatePayload(tpl, structureId))
        return Response.status(Response.Status.BAD_REQUEST).build();
    entity.dayOfWeek = tpl.getDayOfWeek();
    entity.startTime = tpl.getStartTime();
    entity.endTime = tpl.getEndTime();
    entity.locationId = tpl.getLocationId();
    ShiftTemplateSkillEntity.delete("templateId", id);
    insertTemplateSkillRows(id, skillIds(tpl.getRequiredSkills()), 1);
    insertTemplateSkillRows(id, skillIds(tpl.getOptionalSkills()), 2);
    return Response.ok().build();
}

/** @brief Deletes a shift template. */
@DELETE
@Path("/shift-template/{id}")
@Transactional
public Response deleteShiftTemplate(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0 || ShiftTemplateEntity.count(
            "id = ?1 and structureId = ?2", id, structureId) == 0)
        return Response.status(Response.Status.NOT_FOUND).build();
    // ORM (Panache): skills + row in the same transaction (legacy parity).
    ShiftTemplateSkillEntity.delete("templateId", id);
    return ShiftTemplateEntity.deleteById(id)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
}

/** @brief List of saved (named) templates for a structure. */
@GET
@Path("/saved-templates")
@Produces(MediaType.APPLICATION_JSON)
public Response getSavedTemplates(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): header + row count, ordered by created_at DESC, id DESC (same JSON shape as
    // legacy: id, structure_id, description, created_at, item_count).
    List<ShiftTemplateHeaderEntity> headers = ShiftTemplateHeaderEntity.list(
            "structureId = ?1 order by createdAt desc, id desc", structureId);
    Map<Integer, Long> itemCounts = new HashMap<>();
    List<Integer> headerIds = headers.stream().map(h -> h.id).toList();
    for (int from = 0; from < headerIds.size(); from += 900) {
        List<Object[]> counts = em.createQuery(
                "select t.headerId, count(t) from ShiftTemplateEntity t where t.headerId in ?1 group by t.headerId",
                Object[].class).setParameter(1, headerIds.subList(from, Math.min(from + 900, headerIds.size())))
                .getResultList();
        for (Object[] count : counts)
            itemCounts.put(((Number) count[0]).intValue(), ((Number) count[1]).longValue());
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (ShiftTemplateHeaderEntity h : headers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id);
        m.put("structure_id", h.structureId);
        m.put("description", h.description);
        m.put("created_at", h.createdAt);
        m.put("item_count", itemCounts.getOrDefault(h.id, 0L));
        result.add(m);
    }
    return Response.ok(result).build();
}

/**
 * @brief Saves the week as a NEW named template (adds, does not replace).
 * @details Body: {"description": "..."}. Saves only the shift pattern, never employees.
 */
@POST
@Path("/saved-template")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public Response addSavedTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId,
                                 @QueryParam("weekStart") String weekStart,
                                 Map<String, String> body) {
    java.time.LocalDateTime ws = parseWindowBound(weekStart);
    if (ws == null)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "weekStart mancante o non valido")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    String description = body != null ? body.getOrDefault("description", "") : "";
    int id = demoDataRepository.addSavedTemplateFromWeekOrm(structureId, ws, description);
    return id > 0 ? Response.status(Response.Status.CREATED).entity(Map.of("id", id)).build()
                  : Response.serverError().build();
}

/**
 * @brief Takes the pre-operation snapshot; returns the response to send, or null to proceed.
 *
 * @details Shared by the three operations that rewrite shifts in bulk. Without a snapshot they
 *          refuse to write — there would be nothing to go back to — but the refusal now names
 *          which failure it was, because {@link SafetyBackupOutcome#CLIENT_TOOLS_MISSING} means
 *          "this installation is missing pg_dump and will never work" while
 *          {@link SafetyBackupOutcome#BUSY} means "press Save again in a minute". One shared 503
 *          left the user unable to tell a call to the system administrator from patience.
 */
private Response safetyBackupFailure(String tag) {
    SafetyBackupOutcome outcome = backupService.safetyBackup(tag);
    if (outcome.isOk()) return null;
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .entity(Map.of("error", outcome.errorCode())).build();
}

/**
 * @brief Applies a saved template to [start, end) (REPLACES shifts in the window).
 * @details Destructive operation on window shifts: confirmation is the frontend's responsibility.
 */
@POST
@Path("/saved-template/{id}/apply")
@Produces(MediaType.APPLICATION_JSON)
public Response applySavedTemplate(@PathParam("id") int headerId,
                                   @QueryParam("structureId") @DefaultValue("0") int structureId,
                                   @QueryParam("start") String start,
                                   @QueryParam("end") String end) {
    java.time.LocalDateTime ws = parseWindowBound(start);
    java.time.LocalDateTime we = parseWindowBound(end);
    if (structureId <= 0 || !validTemplateWindow(ws, we))
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "start/end mancanti o non validi")).build();
    if (ShiftTemplateHeaderEntity.count("id = ?1 and structureId = ?2", headerId, structureId) == 0)
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "template non trovato per la struttura")).build();
    Response safetyFailure = safetyBackupFailure("preop");
    if (safetyFailure != null) return safetyFailure;
    int created = demoDataRepository.applySavedTemplateToWindowOrm(headerId, structureId, ws, we);
    if (created == -2) return Response.status(Response.Status.CONFLICT)
            .entity(Map.of("error", "template non valido: nessun turno modificato")).build();
    if (created < 0) return Response.status(Response.Status.NOT_FOUND)
            .entity(Map.of("error", "template non trovato per la struttura")).build();
    return Response.ok(Map.of("created", created)).build();
}

/** @brief Shift-template rows of a saved template, for the editor. */
@GET
@Path("/saved-template/{id}/items")
@Produces(MediaType.APPLICATION_JSON)
public Response getSavedTemplateItems(@PathParam("id") int headerId,
                                      @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0 || ShiftTemplateHeaderEntity.count(
            "id = ?1 and structureId = ?2", headerId, structureId) == 0)
        return Response.status(Response.Status.NOT_FOUND).build();
    // ORM (Panache): header rows ordered by day+time, same shape as the list.
    List<ShiftTemplateEntity> entities = ShiftTemplateEntity.list(
            "headerId = ?1 order by dayOfWeek, startTime", headerId);
    return Response.ok(templateDtos(entities)).build();
}

/** @brief Updates a saved template's description. Body: {"description": "..."}. */
@PUT
@Path("/saved-template/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateSavedTemplate(@PathParam("id") int headerId,
                                    @QueryParam("structureId") @DefaultValue("0") int structureId,
                                    Map<String, String> body) {
    String description = body != null ? body.getOrDefault("description", "") : "";
    ShiftTemplateHeaderEntity header = ShiftTemplateHeaderEntity.findById(headerId);
    if (header == null || structureId <= 0 || header.structureId != structureId)
        return Response.status(Response.Status.NOT_FOUND).build();
    header.description = description.trim(); // Trim as in legacy.
    return Response.ok().build();
}

/** @brief Deletes a saved template. */
@DELETE
@Path("/saved-template/{id}")
@Transactional
public Response deleteSavedTemplate(@PathParam("id") int headerId,
                                    @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0 || ShiftTemplateHeaderEntity.count(
            "id = ?1 and structureId = ?2", headerId, structureId) == 0)
        return Response.status(Response.Status.NOT_FOUND).build();
    // ORM (Panache): cascade skills (from header rows) + rows + header in ONE transaction
    // (legacy deleteSavedTemplateHeader parity).
    ShiftTemplateSkillEntity.delete(
            "templateId in (select t.id from ShiftTemplateEntity t where t.headerId = ?1)", headerId);
    ShiftTemplateEntity.delete("headerId", headerId);
    return ShiftTemplateHeaderEntity.deleteById(headerId)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
}

/** @brief Builds the weekly template from a real week (weekStart = Monday). */
@POST
@Path("/shift-template-prepopulate")
public Response prepopulateTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId,
                                    @QueryParam("weekStart") String weekStart) {
    java.time.LocalDateTime ws = parseWindowBound(weekStart);
    if (ws == null)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "weekStart mancante o non valido")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    demoDataRepository.prepopulateTemplateFromWeekOrm(structureId, ws);
    // Response = working-template list via ORM (same shape as /shift-templates).
    return Response.ok(templateDtos(ShiftTemplateEntity.<ShiftTemplateEntity>list(
            "structureId = ?1 and headerId is null order by dayOfWeek, startTime", structureId))).build();
}

/**
 * @brief Populates [start, end) with weekly-template shifts (REPLACES existing shifts).
 * @details Destructive operation: deletes shifts starting in the window and recreates them from
 *          the template. Confirmation is the frontend's responsibility.
 */
@POST
@Path("/apply-template")
public Response applyTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId,
                             @QueryParam("start") String start,
                             @QueryParam("end") String end) {
    java.time.LocalDateTime ws = parseWindowBound(start);
    java.time.LocalDateTime we = parseWindowBound(end);
    if (structureId <= 0 || !validTemplateWindow(ws, we))
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "start/end mancanti o non validi")).build();
    Response safetyFailure = safetyBackupFailure("preop");
    if (safetyFailure != null) return safetyFailure;
    int created = demoDataRepository.applyTemplateToWindowOrm(structureId, ws, we);
    if (created == -2) return Response.status(Response.Status.CONFLICT)
            .entity(Map.of("error", "template non valido: nessun turno modificato")).build();
    return Response.ok(Map.of("created", created)).build();
}

/**
 * @brief Employee date-constraint summary (only employees with at least one).
 * @details Populates the "Employee Date Preferences" page table.
 */
@GET
@Path("/employee-dates-summary")
public Response getEmployeeDatesSummary(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(demoDataRepository.getEmployeeDatesSummaryOrm(structureId)).build();
}

/**
 * @brief Persists shift-to-employee assignments from the solver solution accepted by the user.
 * @details Called by the "Save assignments" button in the Solve Result modal.
 */
@POST
@Path("/save-assignments")
@Consumes(MediaType.APPLICATION_JSON)
public Response saveAssignments(List<org.acme.employeescheduling.dto.ShiftAssignment> assignments,
                                @QueryParam("structureId") @DefaultValue("0") int structureId,
                                @QueryParam("start") String start,
                                @QueryParam("end") String end) {
    LocalDateTime ws = parseWindowBound(start);
    LocalDateTime we = parseWindowBound(end);
    if (structureId <= 0 || ws == null || we == null || !ws.isBefore(we))
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "struttura o finestra non valida")).build();
    Response safetyFailure = safetyBackupFailure("preop");
    if (safetyFailure != null) return safetyFailure;
    // Optional start/end restrict UPDATE to the solve window: they exclude context shifts from
    // adjacent windows even if the payload contains them.
    int updated = demoDataRepository.saveShiftAssignmentsOrm(assignments, structureId,
            ws.format(DB_FORMATTER), we.format(DB_FORMATTER));
    // Shifts modified after solving: 409, not 400. The user did nothing wrong — they only need
    // to reload and rerun the solver against updated data.
    if (updated == DemoDataRepository.STALE_SHIFTS)
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "SHIFTS_CHANGED")).build();
    if (updated < 0) return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", "assegnazione non valida per la struttura")).build();
    return Response.ok(Map.of("updated", updated)).build();
}

// ─── Email templates (subject + HTML body with placeholders) ───────────────

/** @brief Structure email template (empty if none has been saved). */
@GET
@Path("/email-template")
@Transactional
public Response getEmailTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    return Response.ok(demoDataRepository.getEmailTemplateOrm(structureId)).build();
}

/** @brief Saves (upserts) the structure email template. */
@PUT
@Path("/email-template")
@RolesAllowed("ADMIN")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response saveEmailTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId,
                                  org.acme.employeescheduling.dto.EmailTemplate tpl) {
    if (structureId <= 0 || tpl == null || tpl.getSubject() == null || tpl.getBody() == null
            || tpl.getSubject().length() > 255 || tpl.getBody().length() > 100_000)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    String safeBody = RichHtmlSanitizer.sanitize(tpl.getBody());
    boolean ok = demoDataRepository.saveEmailTemplateOrm(structureId, tpl.getSubject(), safeBody);
    return ok ? Response.ok(demoDataRepository.getEmailTemplateOrm(structureId)).build()
              : Response.serverError().build();
}

// ─── PDF template (shared appearance, dynamic report content) ──────────────

@GET
@Path("/pdf-template")
@Transactional
public Response getPdfTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    return Response.ok(demoDataRepository.getPdfTemplateOrm(structureId)).build();
}

@PUT
@Path("/pdf-template")
@RolesAllowed("ADMIN")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response savePdfTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId,
                                PdfTemplate tpl) {
    if (structureId <= 0 || tpl == null || tpl.getHeaderText() == null || tpl.getFooterText() == null
            || tpl.getLogoDataUrl() == null || tpl.getPrimaryColor() == null
            || tpl.getHeaderText().length() > 500 || tpl.getFooterText().length() > 500
            || tpl.getLogoDataUrl().length() > 2_800_000
            || (!tpl.getLogoDataUrl().isBlank()
                && !tpl.getLogoDataUrl().matches("^data:image/(png|jpeg);base64,[A-Za-z0-9+/=\\r\\n]+$"))
            || !tpl.getPrimaryColor().matches("^#[0-9A-Fa-f]{6}$"))
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    boolean ok = demoDataRepository.savePdfTemplateOrm(structureId, tpl.getHeaderText(), tpl.getFooterText(),
        tpl.getLogoDataUrl(), tpl.getPrimaryColor().toUpperCase());
    return ok ? Response.ok(demoDataRepository.getPdfTemplateOrm(structureId)).build()
              : Response.serverError().build();
}

@DELETE
@Path("/pdf-template")
@RolesAllowed("ADMIN")
@Transactional
public Response deletePdfTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    return demoDataRepository.deletePdfTemplateOrm(structureId)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
}







/**
 * @brief Retrieves all employees with their associated data.
 * @return a Response containing a list of Employee objects in JSON format
 */
@APIResponse(
    responseCode = "200",
description = "List of all employees.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Employee.class))
)
@Operation(summary = "Retrieve a list of all employees.")
@GET
@Path("/employees")
public Response getEmployees(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): three bulk reads (employees, dates, skills) — no N+1, therefore no need for
    // the legacy cache (which remains for the solver payload).
    List<EmployeeEntity> entities = EmployeeEntity.list("structureId", Sort.by("id"), structureId);
    Map<Integer, Employee> byId = new LinkedHashMap<>();
    List<Employee> employees = new ArrayList<>();
    for (EmployeeEntity e : entities) {
        Employee dto = new Employee(e.id, e.code, e.firstName, e.lastName,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        dto.setActive(e.active);
        dto.setEmail(e.email);
        byId.put(e.id, dto);
        employees.add(dto);
    }
    // Dates for every employee in the structure, grouped by type.
    List<EmployeeDateEntity> dates = em.createQuery(
            "select d from EmployeeDateEntity d, EmployeeEntity e " +
            "where e.id = d.employeeId and e.structureId = ?1 " +
            "order by d.employeeId, d.dateStart, d.dateTypeId, d.id", EmployeeDateEntity.class)
            .setParameter(1, structureId).getResultList();
    for (EmployeeDateEntity d : dates) {
        Employee employee = byId.get(d.employeeId);
        if (employee == null) continue;
        EmployeeDate date = d.toDto();
        switch (d.dateTypeId) {
            case EmployeeDateEntity.TYPE_DESIRED -> employee.getDesiredDates().add(date);
            case EmployeeDateEntity.TYPE_UNDESIRED -> employee.getUndesiredDates().add(date);
            case EmployeeDateEntity.TYPE_UNAVAILABLE -> employee.getUnavailableDates().add(date);
            default -> { }
        }
    }
    // Skills: STRUCTURE catalog for each employee, with used=true on associated skills (legacy
    // parity: copies do NOT carry the active flag — it stays at its default). With listAll(),
    // every employee would also carry skills from other companies, which would appear among the
    // checkboxes in their modal.
    List<SkillEntity> catalog = SkillEntity.list("structureId",
            Sort.by("skillOrder").and("id"), structureId);
    Map<Integer, java.util.Set<Integer>> skillIdsByEmployee = new HashMap<>();
    List<EmployeeSkillEntity> links = em.createQuery(
            "select es from EmployeeSkillEntity es, EmployeeEntity e " +
            "where e.id = es.employeeId and e.structureId = ?1", EmployeeSkillEntity.class)
            .setParameter(1, structureId).getResultList();
    for (EmployeeSkillEntity es : links)
        skillIdsByEmployee.computeIfAbsent(es.employeeId, k -> new java.util.HashSet<>()).add(es.skillId);
    for (Employee employee : employees) {
        java.util.Set<Integer> usedIds = skillIdsByEmployee.getOrDefault(employee.getId(), java.util.Set.of());
        List<Skill> skills = new ArrayList<>(catalog.size());
        for (SkillEntity s : catalog)
            skills.add(new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active));
        employee.setSkills(skills);
    }
    return Response.ok(employees).build();
}



/**
 * @brief Retrieves a specific employee by ID.
 * @param employee_id the unique identifier of the employee
 * @return a Response containing the Employee in JSON, or HTTP 404 if not found
 */
@APIResponse(
	responseCode = "200",
description = "Employee retrieved successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Employee.class))
)
@Operation(summary = "Retrieve a specific employee by ID.")
@GET
@Path("/getemployee/{id}")
public Response getEmployee(@PathParam("id") int employee_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    EmployeeEntity entity = EmployeeEntity.findById(employee_id);
    if (entity == null || structureId <= 0 || entity.structureId != structureId) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // Legacy parity: skills = ENTIRE CATALOG with used=true on associated skills (LEFT JOIN,
    // natural order); dates grouped by type and ordered by date and type.
    Employee employee = new Employee(entity.id, entity.code, entity.firstName, entity.lastName,
            employeeDates(employee_id, EmployeeDateEntity.TYPE_DESIRED),
            employeeDates(employee_id, EmployeeDateEntity.TYPE_UNDESIRED),
            employeeDates(employee_id, EmployeeDateEntity.TYPE_UNAVAILABLE),
            employeeSkillCatalog(employee_id));
    employee.setActive(entity.active);
    employee.setEmail(entity.email);
    return Response.ok(employee).build();
}

/** @brief Employee dates by type, ordered by date and type (as in legacy). */
private static List<EmployeeDate> employeeDates(int employeeId, int dateTypeId) {
    return EmployeeDateEntity.<EmployeeDateEntity>list(
            "employeeId = ?1 and dateTypeId = ?2 order by dateStart, dateTypeId", employeeId, dateTypeId)
            .stream().map(EmployeeDateEntity::toDto).toList();
}

/** @brief Complete skill catalog (natural order), with used=true on the employee's skills. */
private static List<Skill> employeeSkillCatalog(int employeeId) {
    java.util.Set<Integer> usedIds = EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId", employeeId)
            .stream().map(es -> es.skillId).collect(java.util.stream.Collectors.toSet());
    return SkillEntity.<SkillEntity>listAll().stream()
            .map(s -> new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active))
            .toList();
}





/**
 * @brief Adds a new employee to the system.
 * @param employee the Employee object to add
 * @return a Response with HTTP 201 on success
 */
@APIResponse(
	responseCode = "201",
    description = "Employee added successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Add a new employee.")
@POST
@Path("/addemployee")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response addEmployee(Employee employee, @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    if (employee == null) return Response.status(Response.Status.BAD_REQUEST).build();
    Response invalid = validateEmployeeFields(employee);
    if (invalid != null) return invalid;
    if (!isValidEmail(employee.getEmail())) {
        return ApiErrors.badRequest("EMPLOYEE_EMAIL_INVALID");
    }
    if (!validSkillLists(employee.getSkills(), null)
            || !validDateIntervals(employee.getUnavailableDates())
            || !validDateIntervals(employee.getUndesiredDates())
            || !validDateIntervals(employee.getDesiredDates())) {
        return Response.status(Response.Status.BAD_REQUEST).build();
    }
    if (employee.getEmail() != null) employee.setEmail(employee.getEmail().trim());
    if (EmployeeEntity.count("code = ?1 and id != ?2", employee.getCode().trim(), 0) > 0) {
        return ApiErrors.conflict("EMPLOYEE_CODE_IN_USE");
    }
    // ORM (Panache): insert employee + skills + dates in ONE transaction. Legacy parity:
    // capitalized first/last names; creation-payload skills are resolved by NAME
    // (getSkillIdByName), discarding unknown ones; dates are written as date-only
    // (legacy setDate -> yyyy-MM-dd).
    EmployeeEntity entity = new EmployeeEntity();
    entity.code = employee.getCode();
    entity.firstName = capitalizeName(employee.getFirstName());
    entity.lastName = capitalizeName(employee.getLastName());
    entity.email = employee.getEmail() == null ? "" : employee.getEmail();
    entity.structureId = structureId;
    entity.persist();

    if (employee.getSkills() != null) {
        // Only skills from the employee's structure: another company's skills could still arrive
        // from the client, but do not belong to the employee.
        java.util.Set<Integer> owned = ownedSkillIds(structureId);
        for (Skill skill : employee.getSkills()) {
            if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
            EmployeeSkillEntity link = new EmployeeSkillEntity();
            link.employeeId = entity.id;
            link.skillId = skill.getId();
            link.persist();
        }
    }
    insertDateIntervalRows(entity.id, employee.getUnavailableDates(), EmployeeDateEntity.TYPE_UNAVAILABLE);
    insertDateIntervalRows(entity.id, employee.getUndesiredDates(), EmployeeDateEntity.TYPE_UNDESIRED);
    insertDateIntervalRows(entity.id, employee.getDesiredDates(), EmployeeDateEntity.TYPE_DESIRED);

    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.status(Response.Status.CREATED).build();
}

/** @brief Uppercase first letter, lowercase remainder (legacy addEmployee parity). */
private static String capitalizeName(String s) {
    if (s == null || s.isEmpty()) return s;
    return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
}

/** @brief Inserts date intervals as date-only yyyy-MM-dd (legacy setDate parity). */
private static void insertDateIntervalRows(int employeeId, List<EmployeeDate> intervals, int dateTypeId) {
    if (intervals == null || intervals.isEmpty()) return;
    for (EmployeeDate interval : intervals) {
        EmployeeDateEntity row = new EmployeeDateEntity();
        row.employeeId = employeeId;
        row.dateStart = interval.getDateStart().toLocalDate().toString();
        row.dateEnd = interval.getDateEnd().toLocalDate().toString();
        row.dateTypeId = dateTypeId;
        row.persist();
    }
}




/**
 * @brief Updates an existing employee by ID.
 * @param id the unique identifier of the employee to update
 * @param employee the updated Employee data
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
    responseCode = "200",
    description = "Employee updated successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update an existing employee.")
@PUT
@Path("/updateemployee/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateEmployee(@PathParam("id") int id, Employee employee,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	if (employee == null || structureId <= 0 || employee.getId() != id) {
	    return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch").build();
	}

	Response invalid = validateEmployeeFields(employee);
	if (invalid != null) return invalid;

	if (!isValidEmail(employee.getEmail())) {
	    return ApiErrors.badRequest("EMPLOYEE_EMAIL_INVALID");
	}
	if (employee.getEmail() != null) employee.setEmail(employee.getEmail().trim());

	if (EmployeeEntity.count("code = ?1 and id != ?2", employee.getCode().trim(), id) > 0) {
	    return ApiErrors.conflict("EMPLOYEE_CODE_IN_USE");
	}

	// ORM (Panache): update fields + replace skills in ONE transaction. Legacy updateEmployee
	// parity: NO capitalization here (only in add); structure_id unchanged; update-payload skills
	// arrive by ID.
	EmployeeEntity entity = EmployeeEntity.findById(id);
	if (entity == null || entity.structureId != structureId) {
	    return Response.status(Response.Status.NOT_FOUND).build();
	}
	if (!validSkillLists(employee.getSkills(), null)) {
	    return Response.status(Response.Status.BAD_REQUEST).build();
	}
	entity.code = employee.getCode();
	entity.firstName = employee.getFirstName();
	entity.lastName = employee.getLastName();
	entity.email = employee.getEmail() == null ? "" : employee.getEmail();
	entity.active = employee.isActive();

	EmployeeSkillEntity.delete("employeeId", id);
	if (employee.getSkills() != null) {
	    // Structure read from the employee itself: filter out other companies' skills.
	    java.util.Set<Integer> owned = ownedSkillIds(entity.structureId);
	    for (Skill skill : employee.getSkills()) {
	        if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
	        EmployeeSkillEntity link = new EmployeeSkillEntity();
	        link.employeeId = id;
	        link.skillId = skill.getId();
	        link.persist();
	    }
	}

	// Note: preferred/undesired/unavailable dates are NOT touched here. They are managed by the
	// dedicated "Employee Date Preferences" page through its own endpoints; the employee update
	// payload (name/email/skills/active) does not carry them, so rewriting them here would delete
	// them on every save.

	// Employee-specialist affinities arrive in the payload and belong in the same transaction:
	// atomic deletion + reinsertion, as with skills.
	if (employee.getAffinities() != null) {
            AffinityEntity.delete("operatorId", id);
            for (var a : employee.getAffinities()) {
                if (a.getSpecialistId() <= 0) continue;
                var row = new AffinityEntity();
                row.specialistId = a.getSpecialistId();
                row.operatorId = id;
                row.type = a.getType();
                row.persist();
            }
        }

	// Disabled employee: release future shifts (and their pin), otherwise they remain assigned in
	// the DB but invisible in Shift Management. Past shifts remain intact: they are history.
	if (!employee.isActive()) {
	    long freed = unassignEmployeeShifts(id, LocalDateTime.now().format(DB_FORMATTER));
	    if (freed > 0)
	        logger.info("Operatore " + id + " disattivato: liberati " + freed + " turni futuri.");
	}

	demoDataRepository.invalidateEmployeesAfterCommit();
	return Response.ok().build();

}






/**
 * @brief Deletes an employee by ID.
 * @details Removes the employee and all associated data (skills, dates).
 * @param id the unique identifier of the employee to delete
 * @return a Response with HTTP 204 on success, or HTTP 404 if not found
 */
@APIResponse(
	responseCode = "204",
    description = "Employee deleted successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Delete an existing employee by ID.")
@DELETE
@Path("/employees/{id}")
@Transactional
public Response deleteEmployee(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // ORM (Panache): complete cascade in ONE transaction (legacy used separate connections plus
    // affinity repository OUTSIDE the transaction). SQLite does not enforce FKs: skills, dates,
    // and affinities must be removed explicitly; shifts are detached (an orphan employee_id is
    // not resolvable by any view).
    EmployeeSkillEntity.delete("employeeId", id);
    EmployeeDateEntity.delete("employeeId", id);
    EmailLogEntity.delete("employeeId", id);
    unassignEmployeeShifts(id, null);
    org.acme.employeescheduling.persistence.AffinityEntity.delete("operatorId", id);
    boolean isDeleted = EmployeeEntity.deleteById(id);
    if (isDeleted) {
        demoDataRepository.invalidateEmployeesAfterCommit();
        return Response.status(Response.Status.NO_CONTENT).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}












/**
 * @brief Retrieves all dates for a specific employee.
 * @param employee_id the unique identifier of the employee
 * @return a Response containing a list of EmployeeDate objects, or HTTP 404 if none found
 */
@APIResponse(
	responseCode = "200",
    description = "List of all dates for the given employee.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = EmployeeDate.class))
)
@Operation(summary = "Retrieve all dates for a specific employee.")
@GET
@Path("/getemployeedates/{id}/")
@Produces(MediaType.APPLICATION_JSON)
public Response getEmployeeDates(@PathParam("id") int employee_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(employee_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // ORM (Panache): every employee date (legacy type=0 parity), ordered by date and type.
    List<EmployeeDate> employeeDates = EmployeeDateEntity.<EmployeeDateEntity>list(
            "employeeId = ?1 order by dateStart, dateTypeId", employee_id)
            .stream().map(EmployeeDateEntity::toDto).toList();
    return Response.ok(employeeDates).build();
}





/**
 * @brief Retrieves a specific employee date entry by its date ID.
 * @param date_id the unique identifier of the employee date record
 * @return a Response containing the EmployeeDate data, or HTTP 404 if not found
 */
@APIResponse(
	responseCode = "200",
    description = "Specific date of Employ.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = EmployeeDate.class))
)
@Operation(summary = "Retrieve all dates for a specific employee.")
@GET
@Path("/editemployeedate/{id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getEmployeeDateByDateId(@PathParam("id") int date_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): legacy parity, which returned the single row as a LIST.
    EmployeeDateEntity entity = EmployeeDateEntity.findById(date_id);
    if (entity == null || !employeeDateBelongsToStructure(date_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity("No dates found for the given date_id ID.").build();
    }
    return Response.ok(List.of(entity.toDto())).build();
}










/**
 * @brief Retrieves required and optional skills available for shift assignment.
 * @return a Response containing a map with "requiredSkills" and "optionalSkills" lists
 */
@GET
@Path("/get_skills_for_shift")
@Produces(MediaType.APPLICATION_JSON)
public Response getSkillsForShift(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): same duplicated catalog as /skills-for-location.
    Map<String, List<Skill>> skillsForShift = new HashMap<>();
    List<Skill> catalog = listSkillsForStructure(structureId);
    skillsForShift.put("requiredSkills", catalog);
    skillsForShift.put("optionalSkills", catalog);
    return Response.ok(skillsForShift).build();
}


/**
 * @brief Updates the skills assigned to a shift for a given skill type.
 * @param shift_id the shift whose skills should be updated
 * @param skill_type_id 1 = required, 2 = optional
 * @param skills the list of skills to assign (replaces existing ones of the same type)
 * @return HTTP 200 on success
 */
@PUT
@Path("/update-shift-skills/{shift_id}/{skill_type_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateShiftSkillsFromModal(
        @PathParam("shift_id") int shift_id,
        @PathParam("skill_type_id") int skill_type_id,
        List<Skill> skills,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!shiftBelongsToStructure(shift_id, structureId))
        return Response.status(Response.Status.NOT_FOUND).build();
    if ((skill_type_id != LocationSkillEntity.TYPE_REQUIRED && skill_type_id != LocationSkillEntity.TYPE_OPTIONAL)
            || validatedSkillIds(skills) == null)
        return Response.status(Response.Status.BAD_REQUEST).build();
    // ORM (Panache): replace skills of the specified type (legacy parity, always 200).
    replaceShiftSkills(shift_id, skills, skill_type_id);
    return Response.ok().build();
}


/** Updates managed entities so Hibernate applies the boolean-to-INTEGER mapping. */
private static long unassignEmployeeShifts(int employeeId, String minimumStart) {
    List<ShiftEntity> shifts = minimumStart == null
            ? ShiftEntity.list("employeeId", employeeId)
            : ShiftEntity.list("employeeId = ?1 and startTime >= ?2", employeeId, minimumStart);
    for (ShiftEntity shift : shifts) {
        shift.employeeId = null;
        shift.pinned = false;
    }
    return shifts.size();
}




/**
 * @brief Deletes a shift by its ID.
 * @param shift_id the unique identifier of the shift to delete
 * @return a Response with HTTP 204 on success, HTTP 400 for invalid ID, HTTP 404 if not found, or HTTP 500 on error
 */
@DELETE
@Path("/delete_shift/{shift_id}")
@Transactional
public Response deleteShiftById(@PathParam("shift_id") int shift_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Validate input
    if (shift_id <= 0) {
        return Response.status(Response.Status.BAD_REQUEST).entity("Invalid date ID provided.").build();
    }
    if (!shiftBelongsToStructure(shift_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity("ID not found.").build();
    }

    // ORM (Panache). Beyond legacy: cascade to shift_skills in the same transaction (legacy left
    // orphan rows — the DB FK points to shifts_backup and does not trigger; the orphans were
    // unreachable anyway).
    ShiftSkillEntity.delete("shiftId", shift_id);
    boolean isDeleted = ShiftEntity.deleteById(shift_id);
    if (isDeleted) {
        return Response.status(Response.Status.NO_CONTENT).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).entity("ID not found.").build();
    }
}







/**
 * @brief Deletes a skill by its ID.
 *
 * @details ADMIN only, for the same reason as {@code save_skills}: deleting a skill cascades
 *          over its associations with locations, employees, shifts and templates, and is not
 *          a head nurse's operation.
 *
 * @param id the unique identifier of the skill to delete
 * @return a Response with HTTP 204 on success, or HTTP 404 if not found
 */
@DELETE
@RolesAllowed("ADMIN")
@Path("/skills/{id}")
@Transactional
    public Response deleteSkill(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (structureId <= 0 || !structureExists(structureId)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    // A skill belongs to one structure: a structure that does not own it cannot touch it.
    // Without this check, another company's skill could be deleted by passing its identifier.
    if (!skillBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // Manual cascade over associations: SQLite FKs are not active here, and deleting a skill
    // would leave orphan rows. Everything runs in ONE transaction.
    EmployeeSkillEntity.delete("skillId", id);
    ShiftSkillEntity.delete("skillId", id);
    ShiftTemplateSkillEntity.delete("skillId", id);
    LocationSkillEntity.delete("skillId", id);
    LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", "skills", id);
    boolean isDeleted = SkillEntity.deleteById(id);
    // Same reason as saveSkills: the skill catalog lives inside the employee cache.
    demoDataRepository.invalidateEmployeesAfterCommit();
    demoDataRepository.invalidateTranslationsAfterCommit();
    if (isDeleted) {
        return Response.status(Response.Status.NO_CONTENT).build();
    }
    return Response.status(Response.Status.NOT_FOUND).build();

    
}
	
	








/**
 * @brief Deletes an employee date entry by its ID.
 * @param date_id the unique identifier of the date to delete
 * @return a Response with HTTP 200 on success, HTTP 400 for invalid ID, HTTP 404 if not found, or HTTP 500 on error
 */
@DELETE
@Path("/delete_date/{date_id}")
@Transactional
public Response deleteDateById(@PathParam("date_id") int date_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Validate input
    if (date_id <= 0) {
        return Response.status(Response.Status.BAD_REQUEST).entity("Invalid date ID provided.").build();
    }
    if (!employeeDateBelongsToStructure(date_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity("Date ID not found.").build();
    }

    // ORM (Panache): delete + employee-cache invalidation (dates live inside cached Employee
    // objects in the solver payload — same rule as legacy).
    boolean isDeleted = EmployeeDateEntity.deleteById(date_id);
    if (isDeleted) {
        demoDataRepository.invalidateEmployeesAfterCommit();
        return Response.ok(Map.of("message", "OK")).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).entity("Date ID not found.").build();
    }
}





/**
 * @brief Updates an existing employee date entry.
 * @param date_id the unique identifier of the date to update
 * @param employeeDate the updated EmployeeDate data
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
	    responseCode = "200",
	    description = "Employee date updated successfully.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update an existing employee date.")
@PUT
@Path("/update_employee_dates/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response updateEmployeeDates(@PathParam("id") int date_id, EmployeeDate employeeDate,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Verify that the body ID matches the path ID
    if (employeeDate == null || employeeDate.getId() != date_id) {
        return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch between path and body").build();
    }

    // ORM (Panache). Legacy parity: null dates -> false -> 404 (same visible response); write in
    // full datetime format (dbFormatter).
    EmployeeDateEntity entity = EmployeeDateEntity.findById(date_id);
    if (entity == null || !employeeDateBelongsToStructure(date_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", "No date found with the given ID")).build();
    }
    if (employeeDate.getEmployeeId() != entity.employeeId || !validDateInterval(employeeDate)) {
        return Response.status(Response.Status.BAD_REQUEST).build();
    }
    entity.dateStart = employeeDate.getDateStart().format(DB_FORMATTER);
    entity.dateEnd = employeeDate.getDateEnd().format(DB_FORMATTER);
    entity.dateTypeId = employeeDate.getDateTypeId();
    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.ok().entity(Map.of("message", "Employee date updated successfully")).build();
}








/**
 * @brief Adds a new date entry for a specific employee.
 * @details Validates the date data including required fields, date ordering,
 *          and date type range before persisting.
 * @param employee_id the unique identifier of the employee
 * @param employeeDate the EmployeeDate object containing date details
 * @return a Response with HTTP 201 and the new date ID on success, or HTTP 400/500 on error
 */
@APIResponse(
    responseCode = "201",
    description = "Employee date created successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Add a new employee date.")
@POST
@Path("/add_employee_dates/{employee_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response addEmployeeDate(@PathParam("employee_id") int employee_id, EmployeeDate employeeDate,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Validate required fields
    if (employeeDate == null || employeeDate.getEmployeeId() != employee_id || !validDateInterval(employeeDate)) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Tutti i campi sono obbligatori.")).build();
    }
    if (!employeeBelongsToStructure(employee_id, structureId))
        return Response.status(Response.Status.NOT_FOUND).build();

    // ORM (Panache): insert with employee_id from the PATH (as in legacy; the body carries
    // employeeId only for validation above), full datetime.
    EmployeeDateEntity entity = new EmployeeDateEntity();
    entity.employeeId = employee_id;
    entity.dateStart = employeeDate.getDateStart().format(DB_FORMATTER);
    entity.dateEnd = employeeDate.getDateEnd().format(DB_FORMATTER);
    entity.dateTypeId = employeeDate.getDateTypeId();
    entity.persist();
    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.status(Response.Status.CREATED).entity(Map.of("id", entity.id)).build();

}

/**
 * @brief Atomically replaces all dates for an employee in a single transaction.
 *
 * @details Deletes existing dates and inserts those provided. If the payload is empty or null,
 *          deletes every date. Everything happens in a single JPA transaction: any validation
 *          error stops the entire operation without partial changes.
 */
@RolesAllowed({"ADMIN", "CAPOSALA"})
@POST
@Path("/batch_save_employee_dates/{employee_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response batchSaveEmployeeDates(@PathParam("employee_id") int employee_id,
        List<EmployeeDate> dates,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(employee_id, structureId))
        return Response.status(Response.Status.NOT_FOUND).build();
    if (dates != null) {
        for (EmployeeDate d : dates) {
            if (!validDateInterval(d) || d.getEmployeeId() != employee_id)
                return ApiErrors.badRequest("EMPLOYEE_DATE_INVALID");
        }
    }
    EmployeeDateEntity.delete("employeeId", employee_id);
    if (dates != null) {
        for (EmployeeDate d : dates) {
            EmployeeDateEntity entity = new EmployeeDateEntity();
            entity.employeeId = employee_id;
            entity.dateStart = d.getDateStart().format(DB_FORMATTER);
            entity.dateEnd = d.getDateEnd().format(DB_FORMATTER);
            entity.dateTypeId = d.getDateTypeId();
            entity.persist();
        }
    }
    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.ok(Map.of("saved", dates != null ? dates.size() : 0)).build();
}










/**
 * @brief Updates the dates of an existing shift.
 * @param shift_id the unique identifier of the shift to update
 * @param shift the Shift object containing updated start and end times
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
	    responseCode = "200",
	    description = "Shift date updated successfully.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update an existing Shift date.")
@PUT
@Path("/update_shift_dates/{shift_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response updateShiftDates(@PathParam("shift_id") int shift_id, Shift shift,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Verify that the body ID matches the path ID
    if (shift == null || shift.getId() != shift_id) {
        return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch between path and body").build();
    }

    // ORM (Panache). Legacy updateShiftDate parity: null dates -> 404 (false), update start/end
    // only + replace skills (both types) in the same transaction.
    ShiftEntity entity = ShiftEntity.findById(shift_id);
    if (entity == null || !shiftBelongsToStructure(shift_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", "No date found with the given ID")).build();
    }
    if (shift.getStart() == null || shift.getEnd() == null || !shift.getEnd().isAfter(shift.getStart())
            || !shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())
            || !validSkillLists(shift.getRequiredSkill(), shift.getOptionalSkill()))
        return Response.status(Response.Status.BAD_REQUEST).build();
    entity.startTime = shift.getStart().format(DB_FORMATTER);
    entity.endTime = shift.getEnd().format(DB_FORMATTER);
    replaceShiftSkills(shift_id, shift.getRequiredSkill(), 1);
    replaceShiftSkills(shift_id, shift.getOptionalSkill(), 2);
    return Response.ok().entity(Map.of("message", "Shift date updated successfully")).build();

}





@APIResponse(
	    responseCode = "201",
	    description = "Shift date added successfully.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON)
	)
	/**
	 * @brief Adds a new shift date for a specific location.
	 * @details Validates start and end times before delegating insertion to the repository.
	 * @param location_id the unique identifier of the location
	 * @param shift the Shift object containing start and end times
	 * @return a Response with HTTP 201 and the new shift ID on success, or HTTP 400/500 on error
	 */
	@Operation(summary = "Add a new Shift date.")
	@POST
	@Path("/add_shift_dates/{location_id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transactional
	public Response addShiftDates(@PathParam("location_id") int location_id, Shift shift,
	        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	    // Validate received data
	    if (shift == null || !locationBelongsToStructure(location_id, structureId)
	            || shift.getStart() == null || shift.getEnd() == null) {
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Start date and End date are required")).build();
	    }

	    if (!shift.getEnd().isAfter(shift.getStart())) {
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "End time must be after start time.")).build();
	    }

	    if (!shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())) {
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Il turno non può essere a cavallo di due giorni.")).build();
	    }

	    // ORM (Panache): insert shift only (without skills), location from the PATH.
	    ShiftEntity entity = new ShiftEntity();
	    entity.locationId = location_id;
	    entity.startTime = shift.getStart().format(DB_FORMATTER);
	    entity.endTime = shift.getEnd().format(DB_FORMATTER);
	    entity.persist();
	    return Response.status(Response.Status.CREATED).entity(Map.of("id", entity.id)).build();
	}









	/**
	 * @brief Saves solver assignments for the current week and marks shifts as pinned.
	 * @param shifts the list of solved Shift objects whose employee assignments should be persisted
	 * @return a Response with HTTP 200 on success
	 */
	@POST
	@Path("/save_week_assignments")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response saveWeekAssignments(List<Shift> shifts,
	                                    @QueryParam("structureId") @DefaultValue("0") int structureId) {
	    return demoDataRepository.saveWeekAssignmentsOrm(shifts, structureId)
	            ? Response.ok().build() : Response.status(Response.Status.BAD_REQUEST).build();
	}


	/**
	 * @brief Clears pinning for all shifts in the specified date range.
	 * @param start inclusive start of the week (ISO datetime string)
	 * @param end exclusive end of the week (ISO datetime string)
	 * @return a Response with HTTP 200 on success
	 */
	@PUT
	@Path("/unpin_week")
	public Response unpinWeek(@QueryParam("start") String start,
	                           @QueryParam("end") String end,
	                           @QueryParam("structureId") @DefaultValue("0") int structureId) {
	    LocalDateTime ws = parseWindowBound(start);
	    LocalDateTime we = parseWindowBound(end);
	    if (structureId <= 0 || ws == null || we == null || !ws.isBefore(we))
	        return Response.status(Response.Status.BAD_REQUEST).build();
	    demoDataRepository.unpinWeekOrm(ws.format(DB_FORMATTER), we.format(DB_FORMATTER), structureId);
	    return Response.ok().build();
	}

	/**
	 * @brief Skill-name translation written atomically.
	 * @details See {@link DemoDataRepository#upsertDataTranslation}: the old "find then insert"
	 *          made the entire skill save fail when two head nurses saved the same catalog at the
	 *          same time.
	 */
	private void upsertSkillTranslation(int skillId, int languageId, String value) {
	    demoDataRepository.upsertDataTranslation("skills", skillId, "name", languageId, value);
	}

}
