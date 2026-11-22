package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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

import org.acme.employeescheduling.dto.AssignmentSeries;
import org.acme.employeescheduling.dto.AssignmentSeriesRequirement;
import org.acme.employeescheduling.persistence.AssignmentExceptionEntity;
import org.acme.employeescheduling.persistence.AssignmentSeriesEntity;
import org.acme.employeescheduling.persistence.AssignmentSeriesRequirementEntity;
import org.acme.employeescheduling.persistence.CustomerEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SkillEntity;

/**
 * @brief REST endpoint for recurring customer assignments.
 *
 * @details A series is created once and then generated into a period; the split between
 *          {@code POST /} (the template) and {@code POST /\{id\}/generate} (the concrete visits)
 *          is what keeps the horizon the planner's decision. Generating is idempotent, so the UI
 *          can call it again whenever the planning window moves forward.
 *
 *          <p>Per-occurrence deviations go through {@code exceptions/*} and never rewrite the
 *          series: cancelling the second Tuesday of March leaves every other Tuesday alone, and
 *          the recorded exception keeps the next generation from recreating it.
 */
@RolesAllowed({"ADMIN", "CAPOSALA"})
@Path("/assignments/series")
@Produces(MediaType.APPLICATION_JSON)
public class AssignmentSeriesResource {

    @Inject
    AssignmentSeriesService series;

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    /**
     * @brief Checks a series payload against the same rules the assignment payload obeys.
     * @return a 400 response with a contract code, or null when the payload is usable
     */
    private static Response validate(AssignmentSeries payload, int structureId) {
        if (payload == null || structureId <= 0) {
            return ApiErrors.badRequest("SERIES_PAYLOAD_REQUIRED");
        }
        CustomerEntity customer = CustomerEntity.findById(payload.customerId);
        if (customer == null || customer.structureId != structureId) {
            return ApiErrors.badRequest("ASSIGNMENT_CUSTOMER_UNKNOWN");
        }
        LocationEntity location = LocationEntity.findById(payload.locationId);
        if (location == null || location.structureId != structureId) {
            return ApiErrors.badRequest("ASSIGNMENT_LOCATION_UNKNOWN");
        }
        // By value: both ids are Integer, and `!=` on two boxes compares references, so a
        // location would only match its customer while both ids stayed inside the JVM's cache.
        if (location.customerId != null && !location.customerId.equals(customer.id)) {
            return ApiErrors.badRequest("ASSIGNMENT_LOCATION_CUSTOMER_MISMATCH");
        }
        if (parseTime(payload.startTime) == null) {
            return ApiErrors.badRequest("ASSIGNMENT_START_TIME_INVALID");
        }
        if (payload.durationMinutes < 1 || payload.durationMinutes > 1440) {
            return ApiErrors.badRequest("ASSIGNMENT_DURATION_INVALID");
        }
        if (payload.priority < 1 || payload.priority > 3) {
            return ApiErrors.badRequest("ASSIGNMENT_PRIORITY_INVALID");
        }
        String pattern = payload.patternType == null ? "" : payload.patternType.trim().toUpperCase();
        if (!List.of(AssignmentSeriesEntity.WEEKLY, AssignmentSeriesEntity.BIWEEKLY,
                AssignmentSeriesEntity.MONTHLY_NTH_WEEKDAY).contains(pattern)) {
            return ApiErrors.badRequest("SERIES_PATTERN_INVALID");
        }
        LocalDate from = parseDate(payload.effectiveFrom);
        LocalDate to = parseDate(payload.effectiveTo);
        if (from == null || to == null) {
            return ApiErrors.badRequest("SERIES_RANGE_REQUIRED");
        }
        if (from.isAfter(to)) {
            return ApiErrors.badRequest("SERIES_RANGE_INVALID");
        }
        if (AssignmentSeriesEntity.MONTHLY_NTH_WEEKDAY.equals(pattern)
                && (payload.nth < 1 || payload.nth > 5)) {
            return ApiErrors.badRequest("SERIES_NTH_INVALID");
        }
        try {
            AssignmentSeriesService.parseWeekdays(payload.weekdays);
        } catch (IllegalArgumentException e) {
            return ApiErrors.badRequest(e.getMessage());
        }
        if (payload.requirements == null || payload.requirements.isEmpty()) {
            return ApiErrors.badRequest("ASSIGNMENT_REQUIREMENT_REQUIRED");
        }
        int total = 0;
        for (AssignmentSeriesRequirement requirement : payload.requirements) {
            if (requirement == null || requirement.role == null || requirement.role.isBlank()
                    || !AssignmentMaterializer.ROLES.contains(requirement.role)) {
                return ApiErrors.badRequest("ASSIGNMENT_ROLE_INVALID");
            }
            if (requirement.headcount < 1) {
                return ApiErrors.badRequest("ASSIGNMENT_HEADCOUNT_INVALID");
            }
            if (requirement.skillId != null && SkillEntity.count(
                    "id = ?1 and structureId = ?2", requirement.skillId, structureId) == 0) {
                return ApiErrors.badRequest("ASSIGNMENT_SKILL_UNKNOWN");
            }
            total += requirement.headcount;
        }
        if (total > AssignmentService.MAX_POSITIONS) {
            return ApiErrors.badRequest("ASSIGNMENT_TOO_MANY_POSITIONS");
        }
        return null;
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Series
    // -----------------------------------------------------------------------

    @GET
    public Response list(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (structureId <= 0) {
            return ApiErrors.badRequest("STRUCTURE_REQUIRED");
        }
        return Response.ok(series.list(structureId)).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") int id, @QueryParam("structureId") @DefaultValue("0") int structureId) {
        AssignmentSeriesEntity entity = AssignmentSeriesEntity.findById(id);
        if (entity == null || (structureId > 0 && entity.structureId != structureId)) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        return Response.ok(series.toDto(entity)).build();
    }

    /** @brief Creates the template. No occurrence is generated yet — that is an explicit step. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(AssignmentSeries payload,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        Response invalid = validate(payload, structureId);
        if (invalid != null) {
            return invalid;
        }
        AssignmentSeriesEntity entity = new AssignmentSeriesEntity();
        entity.structureId = structureId;
        apply(payload, entity);
        entity.createdAt = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        entity.persist();
        for (AssignmentSeriesRequirement requirement : payload.requirements) {
            AssignmentSeriesRequirementEntity line = new AssignmentSeriesRequirementEntity();
            line.seriesId = entity.id;
            line.role = requirement.role;
            line.skillId = requirement.skillId;
            line.headcount = requirement.headcount;
            line.persist();
        }
        return Response.status(Response.Status.CREATED).entity(series.toDto(entity)).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response update(@PathParam("id") int id, AssignmentSeries payload,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        AssignmentSeriesEntity entity = AssignmentSeriesEntity.findById(id);
        if (entity == null || (structureId > 0 && entity.structureId != structureId)) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        Response invalid = validate(payload, entity.structureId);
        if (invalid != null) {
            return invalid;
        }
        apply(payload, entity);
        AssignmentSeriesRequirementEntity.delete("seriesId = ?1", id);
        for (AssignmentSeriesRequirement requirement : payload.requirements) {
            AssignmentSeriesRequirementEntity line = new AssignmentSeriesRequirementEntity();
            line.seriesId = id;
            line.role = requirement.role;
            line.skillId = requirement.skillId;
            line.headcount = requirement.headcount;
            line.persist();
        }
        return Response.ok(series.toDto(entity)).build();
    }

    private static void apply(AssignmentSeries payload, AssignmentSeriesEntity entity) {
        entity.customerId = payload.customerId;
        entity.locationId = payload.locationId;
        entity.title = payload.title == null ? "" : payload.title.trim();
        entity.service = payload.service == null ? "" : payload.service;
        entity.startTime = payload.startTime;
        entity.durationMinutes = payload.durationMinutes;
        entity.priority = payload.priority;
        entity.fixedTime = payload.fixedTime;
        entity.notes = payload.notes == null ? "" : payload.notes;
        entity.patternType = payload.patternType.trim().toUpperCase();
        entity.weekdays = payload.weekdays.trim();
        entity.nth = AssignmentSeriesEntity.MONTHLY_NTH_WEEKDAY.equals(entity.patternType)
                ? payload.nth : 0;
        entity.effectiveFrom = payload.effectiveFrom;
        entity.effectiveTo = payload.effectiveTo;
        entity.active = payload.active;
    }

    /**
     * @brief Generates the missing occurrences of a period.
     * @details Idempotent: existing dates and dates with an exception are skipped, so the UI may
     *          call this every time the planning window moves without duplicating a visit.
     */
    @POST
    @Path("/{id}/generate")
    public Response generate(@PathParam("id") int id,
            @QueryParam("from") String from, @QueryParam("to") String to) {
        AssignmentSeriesEntity entity = AssignmentSeriesEntity.findById(id);
        if (entity == null) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        LocalDate start = parseDate(from);
        LocalDate end = parseDate(to);
        if (start == null || end == null) {
            return ApiErrors.badRequest("SERIES_RANGE_REQUIRED");
        }
        AssignmentSeriesService.GenerationResult result;
        try {
            result = series.generate(id, start, end);
        } catch (IllegalArgumentException e) {
            return ApiErrors.badRequest(e.getMessage());
        }
        if (result == null) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        return Response.ok(Map.of(
                "occurrences", result.occurrences(),
                "generated", result.generated(),
                "skipped", result.skippedException(),
                "positions", result.positions())).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") int id,
            @QueryParam("deleteOccurrences") @DefaultValue("false") boolean deleteOccurrences) {
        if (AssignmentSeriesEntity.findById(id) == null) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        series.delete(id, deleteOccurrences);
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Per-occurrence exceptions
    // -----------------------------------------------------------------------

    @GET
    @Path("/{id}/exceptions")
    public Response exceptions(@PathParam("id") int id) {
        return Response.ok(series.exceptions(id)).build();
    }

    /** @brief Cancels one occurrence; its positions are released, the series stays as it is. */
    @POST
    @Path("/{id}/exceptions/cancel")
    public Response cancel(@PathParam("id") int id, @QueryParam("date") String date,
            @QueryParam("notes") @DefaultValue("") String notes) {
        if (AssignmentSeriesEntity.findById(id) == null) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        LocalDate occurrenceDate = parseDate(date);
        if (occurrenceDate == null) {
            return ApiErrors.badRequest("SERIES_OCCURRENCE_DATE_REQUIRED");
        }
        boolean existed = series.cancelOccurrence(id, occurrenceDate, notes);
        return Response.ok(Map.of("cancelled", existed, "occurrenceDate", occurrenceDate.toString())).build();
    }

    /** @brief Moves one occurrence to another date or time. */
    @POST
    @Path("/{id}/exceptions/move")
    public Response move(@PathParam("id") int id, @QueryParam("date") String date,
            @QueryParam("newDate") String newDate, @QueryParam("newTime") @DefaultValue("") String newTime,
            @QueryParam("notes") @DefaultValue("") String notes) {
        if (AssignmentSeriesEntity.findById(id) == null) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        LocalDate occurrenceDate = parseDate(date);
        LocalDate target = parseDate(newDate);
        if (occurrenceDate == null || target == null) {
            return ApiErrors.badRequest("SERIES_OCCURRENCE_DATE_REQUIRED");
        }
        if (!newTime.isBlank() && parseTime(newTime) == null) {
            return ApiErrors.badRequest("ASSIGNMENT_START_TIME_INVALID");
        }
        if (!series.moveOccurrence(id, occurrenceDate, target, newTime, notes)) {
            return ApiErrors.notFound("SERIES_OCCURRENCE_UNKNOWN");
        }
        return Response.ok(Map.of("moved", true, "occurrenceDate", occurrenceDate.toString(),
                "newDate", target.toString())).build();
    }

    /**
     * @brief Forgets a deviation and regenerates that date.
     *
     * @details This is the undo of a cancellation: the exception row is what blocks the
     *          regeneration, so removing it and generating the single date brings the visit back.
     */
    @DELETE
    @Path("/{id}/exceptions/{date}")
    public Response clearException(@PathParam("id") int id, @PathParam("date") String date) {
        AssignmentSeriesEntity entity = AssignmentSeriesEntity.findById(id);
        if (entity == null) {
            return ApiErrors.notFound("SERIES_UNKNOWN");
        }
        LocalDate occurrenceDate = parseDate(date);
        if (occurrenceDate == null) {
            return ApiErrors.badRequest("SERIES_OCCURRENCE_DATE_REQUIRED");
        }
        AssignmentExceptionEntity exception = series.exceptionOf(id, occurrenceDate);
        // Undoing a move would have to guess whether the visit returns to its old date or is meant
        // to stay where it was moved; the planner moves it back instead of letting us guess.
        if (exception != null && AssignmentExceptionEntity.MOVED.equals(exception.kind)) {
            return ApiErrors.conflict("SERIES_EXCEPTION_MOVED");
        }
        series.clearException(id, occurrenceDate);
        AssignmentSeriesService.GenerationResult result;
        try {
            result = series.generate(id, occurrenceDate, occurrenceDate);
        } catch (IllegalArgumentException e) {
            return ApiErrors.badRequest(e.getMessage());
        }
        return Response.ok(Map.of("generated", result == null ? 0 : result.generated())).build();
    }
}
