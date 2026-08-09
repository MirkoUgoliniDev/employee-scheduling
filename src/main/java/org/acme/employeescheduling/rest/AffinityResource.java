package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.acme.employeescheduling.dto.SpecialistAffinity;
import org.acme.employeescheduling.persistence.AffinityEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.SpecialistEntity;

/**
 * @brief REST endpoints for Employee <-> Specialist compatibilities.
 *
 * @details The Employee modal loads the list with GET and saves the whole set with
 *          PUT (replace semantics): the backend deletes and re-inserts the rows
 *          of the employee in a transaction. Allowed types: 2=to avoid, 3=incompatible.
 *          Migrated to ORM (Panache) — REST contract unchanged from the legacy one.
 */
@RolesAllowed({"ADMIN", "CAPOSALA"})
@Path("/affinities")
@Produces(MediaType.APPLICATION_JSON)
public class AffinityResource {

    @Inject
    EntityManager em;

    /** @brief Non-neutral relations of an employee. */
    @GET
    @Path("/operator/{operatorId}")
    public Response getByOperator(@PathParam("operatorId") int operatorId,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (structureId <= 0) return Response.status(Response.Status.NOT_FOUND).build();
        List<Object[]> rows = em.createQuery(
                "select e.id, a.operatorId, s.id, a.type from EmployeeEntity e " +
                "left join AffinityEntity a on a.operatorId = e.id " +
                "left join SpecialistEntity s on s.id = a.specialistId and s.structureId = e.structureId " +
                "where e.id = ?1 and e.structureId = ?2 order by a.id",
                Object[].class).setParameter(1, operatorId).setParameter(2, structureId).getResultList();
        if (rows.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
        List<SpecialistAffinity> affinities = rows.stream().filter(row -> row[2] != null)
                .map(row -> new SpecialistAffinity(((Number) row[1]).intValue(),
                        ((Number) row[2]).intValue(), ((Number) row[3]).intValue()))
                .toList();
        return Response.ok(affinities).build();
    }

    /** @brief All relations of an organisation's employees (columns in the Employees table). */
    @GET
    public Response getByStructure(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        List<SpecialistAffinity> affinities = AffinityEntity.<AffinityEntity>list(
                "operatorId in (select e.id from EmployeeEntity e where e.structureId = ?1) " +
                "and specialistId in (select s.id from SpecialistEntity s where s.structureId = ?1) " +
                "order by operatorId, specialistId, id",
                structureId).stream().map(AffinityEntity::toDto).toList();
        return Response.ok(affinities).build();
    }

    /** @brief Replaces all relations of an employee in one go. */
    @PUT
    @Path("/operator/{operatorId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response replaceForOperator(@PathParam("operatorId") int operatorId,
            @QueryParam("structureId") @DefaultValue("0") int structureId,
            List<SpecialistAffinity> affinities) {
        if (structureId <= 0 || EmployeeEntity.count(
                "id = ?1 and structureId = ?2", operatorId, structureId) == 0)
            return Response.status(Response.Status.NOT_FOUND).build();
        if (affinities != null) {
            Set<Integer> specialistIds = new LinkedHashSet<>();
            for (SpecialistAffinity a : affinities) {
                if (a == null || (a.getOperatorId() != 0 && a.getOperatorId() != operatorId)
                        || (a.getType() != SpecialistAffinity.TYPE_AVOID
                            && a.getType() != SpecialistAffinity.TYPE_INCOMPATIBLE)) {
                    return ApiErrors.badRequest("AFFINITY_PAYLOAD_INVALID");
                }
                specialistIds.add(a.getSpecialistId());
            }
            if (!specialistIds.isEmpty() && SpecialistEntity.count(
                    "structureId = ?1 and id in ?2", structureId, specialistIds) != specialistIds.size()) {
                return ApiErrors.badRequest("AFFINITY_PAYLOAD_INVALID");
            }
        }
        // DELETE + INSERT in the same transaction: idempotent, no partial state
        // (same semantics as the legacy). The legacy used INSERT OR REPLACE: a payload
        // with the same pair repeated did not blow up (the last one won) -> that is replicated
        // by deduplicating per specialist before the persist (UNIQUE operator+specialist).
        AffinityEntity.delete("operatorId", operatorId);
        if (affinities != null && !affinities.isEmpty()) {
            Map<Integer, SpecialistAffinity> bySpecialist = new LinkedHashMap<>();
            for (SpecialistAffinity a : affinities) bySpecialist.put(a.getSpecialistId(), a);
            for (SpecialistAffinity a : bySpecialist.values()) {
                AffinityEntity entity = new AffinityEntity();
                entity.operatorId = operatorId;
                entity.specialistId = a.getSpecialistId();
                entity.type = a.getType();
                entity.persist();
            }
        }
        return Response.ok().build();
    }
}
