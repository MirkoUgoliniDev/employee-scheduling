package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

import org.acme.employeescheduling.dto.AssignmentRequirement;
import org.acme.employeescheduling.dto.CustomerAssignment;
import org.acme.employeescheduling.persistence.AssignmentRequirementEntity;
import org.acme.employeescheduling.persistence.CustomerAssignmentEntity;
import org.acme.employeescheduling.persistence.CustomerEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SkillEntity;

/**
 * @brief REST endpoint for customer assignments — the central planning object.
 *
 * @details Writing an assignment (POST/PUT) always materialises its positions, so the data the
 *          solver sees can never drift from the staffing requirement. Reading returns the
 *          positions with the assigned employee, which is what the calendar and the team view
 *          display; the staffing counters tell the planner at a glance whether a visit is fully
 *          covered.
 *
 *          <p>{@code /unfilled} and {@code /conflicts} are the two honest views demanded by the
 *          requirement: uncovered positions, and locked positions whose employee became invalid.
 *          Neither is silent — an unfilled position is never counted as staffed, and a conflict is
 *          never repaired behind the planner's back.
 */
@RolesAllowed({"ADMIN", "CAPOSALA"})
@Path("/assignments")
@Produces(MediaType.APPLICATION_JSON)
public class AssignmentResource {

    @Inject
    AssignmentService assignments;

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    private static boolean isMissing(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * @brief Validates the header and the staffing requirements of an assignment.
     * @details Every rejection carries a stable code so the frontend can translate it. The
     *          duration is bounded to 24 hours and the team to {@link AssignmentService#MAX_POSITIONS}
     *          positions, because a typo like "4800 minutes, 300 positions" would otherwise reach
     *          the solver as a week-long, unsolvable problem.
     */
    private static Response validate(CustomerAssignment assignment, int structureId) {
        if (isMissing(assignment.getTitle())) return ApiErrors.badRequest("ASSIGNMENT_TITLE_REQUIRED");
        if (isMissing(assignment.getAssignmentDate())) return ApiErrors.badRequest("ASSIGNMENT_DATE_REQUIRED");
        if (isMissing(assignment.getStartTime())) return ApiErrors.badRequest("ASSIGNMENT_START_REQUIRED");
        try {
            LocalDate.parse(assignment.getAssignmentDate());
        } catch (DateTimeParseException e) {
            return ApiErrors.badRequest("ASSIGNMENT_DATE_INVALID");
        }
        try {
            LocalTime.parse(assignment.getStartTime());
        } catch (DateTimeParseException e) {
            return ApiErrors.badRequest("ASSIGNMENT_START_INVALID");
        }
        if (assignment.getDurationMinutes() <= 0 || assignment.getDurationMinutes() > 24 * 60) {
            return ApiErrors.badRequest("ASSIGNMENT_DURATION_INVALID");
        }
        if (isMissing(assignment.getStatus())
                || !AssignmentMaterializer.PLANNED_STATUSES.contains(assignment.getStatus())
                        && !List.of("RECURRING", "CANCELLED", "COMPLETED").contains(assignment.getStatus())) {
            return ApiErrors.badRequest("ASSIGNMENT_STATUS_INVALID");
        }
        CustomerEntity customer = CustomerEntity.findById(assignment.getCustomerId());
        if (customer == null || customer.structureId != structureId) {
            return ApiErrors.badRequest("ASSIGNMENT_CUSTOMER_UNKNOWN");
        }
        LocationEntity location = LocationEntity.findById(assignment.getLocationId());
        if (location == null || location.structureId != structureId) {
            return ApiErrors.badRequest("ASSIGNMENT_LOCATION_UNKNOWN");
        }
        // A location that belongs to another customer must not receive this customer's visit:
        // that is a data error the planner has to resolve, not something to guess around.
        // By value: both ids are Integer, so `!=` would compare references and only agree inside
        // the JVM's box cache (-128..127) — above that every visit would be rejected as a mismatch.
        if (location.customerId != null && !location.customerId.equals(customer.id)) {
            return ApiErrors.badRequest("ASSIGNMENT_LOCATION_CUSTOMER_MISMATCH");
        }
        if (assignment.getRequirements() == null || assignment.getRequirements().isEmpty()) {
            return ApiErrors.badRequest("ASSIGNMENT_REQUIREMENT_REQUIRED");
        }
        int total = 0;
        for (AssignmentRequirement requirement : assignment.getRequirements()) {
            if (requirement == null || isMissing(requirement.getRole())
                    || !AssignmentMaterializer.ROLES.contains(requirement.getRole())) {
                return ApiErrors.badRequest("ASSIGNMENT_ROLE_INVALID");
            }
            if (requirement.getHeadcount() < 1) {
                return ApiErrors.badRequest("ASSIGNMENT_HEADCOUNT_INVALID");
            }
            if (requirement.getSkillId() != null && SkillEntity.count(
                    "id = ?1 and structureId = ?2", requirement.getSkillId(), structureId) == 0) {
                return ApiErrors.badRequest("ASSIGNMENT_SKILL_UNKNOWN");
            }
            total += requirement.getHeadcount();
        }
        if (total > AssignmentService.MAX_POSITIONS) {
            return ApiErrors.badRequest("ASSIGNMENT_TOO_MANY_POSITIONS");
        }
        return null;
    }

    private static List<AssignmentRequirementEntity> toEntities(CustomerAssignment assignment) {
        List<AssignmentRequirementEntity> entities = new ArrayList<>();
        for (AssignmentRequirement requirement : assignment.getRequirements()) {
            AssignmentRequirementEntity entity = new AssignmentRequirementEntity();
            entity.role = requirement.getRole();
            entity.skillId = requirement.getSkillId();
            entity.headcount = requirement.getHeadcount();
            entities.add(entity);
        }
        return entities;
    }

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    /** @brief Assignments of a period, with their team and staffing counters. */
    @GET
    public Response getAssignments(@QueryParam("structureId") @DefaultValue("0") int structureId,
            @QueryParam("from") String from, @QueryParam("to") String to) {
        if (structureId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return Response.ok(assignments.list(structureId, from, to)).build();
    }

    /** @brief Open positions of a period — the explicitly unfilled ones. */
    @GET
    @Path("/unfilled")
    public Response getUnfilled(@QueryParam("structureId") @DefaultValue("0") int structureId,
            @QueryParam("from") String from, @QueryParam("to") String to) {
        if (structureId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return Response.ok(assignments.unfilledPositions(structureId, from, to)).build();
    }

    /** @brief Locked positions that no longer satisfy a requirement (absence, role, location, skill). */
    @GET
    @Path("/conflicts")
    public Response getConflicts(@QueryParam("structureId") @DefaultValue("0") int structureId,
            @QueryParam("from") String from, @QueryParam("to") String to) {
        if (structureId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return Response.ok(assignments.conflicts(structureId, from, to)).build();
    }

    /** @brief Single assignment. */
    @GET
    @Path("/{id}")
    public Response getAssignment(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        CustomerAssignment assignment = assignments.load(structureId, id);
        if (assignment == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(assignment).build();
    }

    /** @brief Creates an assignment and materialises its positions. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addAssignment(CustomerAssignment assignment,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (assignment == null || structureId <= 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Response invalid = validate(assignment, structureId);
        if (invalid != null) return invalid;
        CustomerAssignmentEntity entity = new CustomerAssignmentEntity();
        entity.structureId = structureId;
        entity.customerId = assignment.getCustomerId();
        entity.locationId = assignment.getLocationId();
        entity.title = assignment.getTitle().trim();
        entity.service = assignment.getService() == null ? "" : assignment.getService();
        entity.assignmentDate = assignment.getAssignmentDate();
        entity.startTime = assignment.getStartTime();
        entity.durationMinutes = assignment.getDurationMinutes();
        entity.priority = assignment.getPriority();
        entity.status = assignment.getStatus();
        entity.fixedTime = assignment.isFixedTime();
        entity.notes = assignment.getNotes() == null ? "" : assignment.getNotes();
        entity.seriesId = assignment.getSeriesId();
        int id = assignments.create(entity, toEntities(assignment));
        return Response.status(Response.Status.CREATED).entity(assignments.load(structureId, id)).build();
    }

    /**
     * @brief Updates an assignment and re-synchronises its positions.
     * @details A duration change rewrites the start/end of the existing shift rows and keeps the
     *          employees already assigned — the minimal-churn requirement. Only requirements that
     *          appear or disappear create or delete positions.
     */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateAssignment(@PathParam("id") int id, CustomerAssignment assignment,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (assignment == null || structureId <= 0 || assignment.getId() != id) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Response invalid = validate(assignment, structureId);
        if (invalid != null) return invalid;
        CustomerAssignmentEntity entity = CustomerAssignmentEntity.findById(id);
        if (entity == null || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        assignments.update(id, assignment, toEntities(assignment));
        return Response.ok(assignments.load(structureId, id)).build();
    }

    /** @brief Deletes an assignment together with its positions. */
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteAssignment(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        CustomerAssignmentEntity entity = CustomerAssignmentEntity.findById(id);
        if (entity == null || structureId <= 0 || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        assignments.delete(id);
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    /** @brief Locks or unlocks every position of the assignment before re-solving. */
    @POST
    @Path("/{id}/pin")
    public Response pinAssignment(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId,
            @QueryParam("pinned") @DefaultValue("true") boolean pinned) {
        CustomerAssignmentEntity entity = CustomerAssignmentEntity.findById(id);
        if (entity == null || structureId <= 0 || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        int count = assignments.setPinned(id, pinned);
        return Response.ok(Map.of("positions", count, "pinned", pinned)).build();
    }

    /** @brief Forces a re-synchronisation of the positions from the requirements. */
    @POST
    @Path("/{id}/regenerate")
    public Response regenerate(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        CustomerAssignmentEntity entity = CustomerAssignmentEntity.findById(id);
        if (entity == null || structureId <= 0 || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        int positions = assignments.regeneratePositions(id);
        return Response.ok(Map.of("positions", positions)).build();
    }
}
