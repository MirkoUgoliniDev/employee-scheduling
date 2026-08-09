package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.employeescheduling.dto.SolverSettings;
import org.acme.employeescheduling.persistence.StructureEntity;

@RolesAllowed("ADMIN")
@Path("/solver-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SolverSettingsResource {
    @Inject DemoDataRepository repository;

    @GET
    @Transactional
    public Response get(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (structureId <= 0) return Response.status(Response.Status.BAD_REQUEST).build();
        if (StructureEntity.count("id", structureId) == 0) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(repository.getSolverSettingsOrm(structureId)).build();
    }

    @PUT
    @Transactional
    public Response save(@QueryParam("structureId") @DefaultValue("0") int structureId, SolverSettings s) {
        if (structureId <= 0 || s == null || s.getMaxSolveSeconds() < 5 || s.getMaxSolveSeconds() > 600
                || s.getUnimprovedSeconds() < 0 || s.getUnimprovedSeconds() > s.getMaxSolveSeconds()
                || s.getMinimumRestHours() < 0 || s.getMinimumRestHours() > 24
                || s.getMaxShiftsPerDay() < 1 || s.getMaxShiftsPerDay() > 5
                || s.getDesiredDateWeight() < 0 || s.getDesiredDateWeight() > 10
                || s.getUndesiredDateWeight() < 0 || s.getUndesiredDateWeight() > 10
                || s.getBalanceWeight() < 0 || s.getBalanceWeight() > 10
                || s.getOptionalSkillWeight() < 0 || s.getOptionalSkillWeight() > 10
                || s.getMaxWeeklyHours() < 0 || s.getMaxWeeklyHours() > 168
                || s.getMinWeeklyShifts() < 0 || s.getMinWeeklyShifts() > 21
                || s.getMaxWeeklyShifts() < 0 || s.getMaxWeeklyShifts() > 21
                || (s.getMaxWeeklyShifts() > 0 && s.getMinWeeklyShifts() > s.getMaxWeeklyShifts())
                || s.getMaxConsecutiveDays() < 0 || s.getMaxConsecutiveDays() > 31
                || s.getMinDaysOffPerWeek() < 0 || s.getMinDaysOffPerWeek() > 7
                || s.getUnassignedWeight() < 1 || s.getUnassignedWeight() > 100
                || s.getSameLocationWeight() < 0 || s.getSameLocationWeight() > 10
                || s.getNightBalanceWeight() < 0 || s.getNightBalanceWeight() > 10
                || s.getNightStartHour() < 0 || s.getNightStartHour() > 23
                || s.getNightEndHour() < 0 || s.getNightEndHour() > 23
                || s.getAvoidSpecialistWeight() < 0 || s.getAvoidSpecialistWeight() > 10
                || s.getContextDays() < 0 || s.getContextDays() > 7
                || s.getDiminishedWindowSeconds() < 0 || s.getDiminishedWindowSeconds() > 600
                || s.getDiminishedRatioPct() < 1 || s.getDiminishedRatioPct() > 100
                || s.getWeeklyShiftWeight() < 0 || s.getWeeklyShiftWeight() > 10
                || s.getDaysOffWeight() < 0 || s.getDaysOffWeight() > 10
                || s.getConsecutiveDaysWeight() < 0 || s.getConsecutiveDaysWeight() > 10)
            return Response.status(Response.Status.BAD_REQUEST).build();
        if (StructureEntity.count("id", structureId) == 0)
            return Response.status(Response.Status.NOT_FOUND).build();
        return repository.saveSolverSettingsOrm(structureId, s)
                ? Response.ok(repository.getSolverSettingsOrm(structureId)).build()
                : Response.serverError().build();
    }
}
