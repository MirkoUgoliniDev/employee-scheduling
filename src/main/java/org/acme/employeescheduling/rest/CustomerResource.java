package org.acme.employeescheduling.rest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
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

import org.acme.employeescheduling.dto.Customer;
import org.acme.employeescheduling.persistence.CustomerAssignmentEntity;
import org.acme.employeescheduling.persistence.CustomerEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.StructureEntity;

/**
 * @brief REST endpoint for the customer master data.
 *
 * @details Structure-scoped CRUD following the {@link SpecialistResource} conventions: machine
 *          error codes via {@link ApiErrors}, unique code per structure, application-level
 *          cleanup instead of relying on database cascades (legacy SQLite files do not always
 *          enforce foreign keys).
 *
 *          <p>{@code locationIds} is the inverse side of {@code locations.customer_id}: the
 *          association is written on the location rows, so assigning a customer to a location is
 *          one {@code UPDATE} and can never leave an orphan join row behind.
 */
@RolesAllowed({"ADMIN", "CAPOSALA"})
@Path("/customers")
@Produces(MediaType.APPLICATION_JSON)
public class CustomerResource {

    private static boolean isMissing(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** @brief True if the code is already used by another customer in the structure. */
    private static boolean codeInUse(int structureId, String code, int excludeId) {
        return CustomerEntity.count("structureId = ?1 and code = ?2 and id != ?3",
                structureId, code, excludeId) > 0;
    }

    private static Response validate(Customer customer) {
        if (isMissing(customer.getCode())) return ApiErrors.badRequest("CUSTOMER_CODE_REQUIRED");
        if (isMissing(customer.getName())) return ApiErrors.badRequest("CUSTOMER_NAME_REQUIRED");
        if (isMissing(customer.getDefaultStartTime())) {
            return ApiErrors.badRequest("CUSTOMER_START_TIME_REQUIRED");
        }
        if (customer.getDefaultDurationMinutes() <= 0 || customer.getDefaultDurationMinutes() > 24 * 60) {
            return ApiErrors.badRequest("CUSTOMER_DURATION_INVALID");
        }
        return null;
    }

    /** @brief Assigns the listed locations to the customer and clears the others. */
    private static void syncLocations(CustomerEntity entity, List<Integer> locationIds) {
        LocationEntity.update("customerId = null where customerId = ?1", entity.id);
        if (locationIds == null) {
            return;
        }
        for (Integer locationId : locationIds) {
            LocationEntity.update("customerId = ?1 where id = ?2 and structureId = ?3",
                    entity.id, locationId, entity.structureId);
        }
    }

    private static List<Integer> locationIdsOf(int customerId) {
        return LocationEntity.<LocationEntity>list("customerId = ?1 order by displayOrder, id", customerId)
                .stream().map(location -> location.id).toList();
    }

    /** @brief Lists the customers of a structure, optionally including inactive ones. */
    @GET
    public Response getCustomers(@QueryParam("structureId") @DefaultValue("0") int structureId,
            @QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly) {
        List<CustomerEntity> entities = activeOnly
                ? CustomerEntity.list("structureId = ?1 and active = true", Sort.by("name"), structureId)
                : CustomerEntity.list("structureId = ?1", Sort.by("name"), structureId);
        Map<Integer, List<Integer>> locationsByCustomer = LocationEntity
                .<LocationEntity>list("structureId = ?1 and customerId is not null", structureId)
                .stream().collect(Collectors.groupingBy(location -> location.customerId,
                        Collectors.mapping(location -> location.id, Collectors.toList())));
        List<Customer> customers = entities.stream().map(entity -> {
            Customer dto = entity.toDto();
            dto.setLocationIds(locationsByCustomer.getOrDefault(entity.id, List.of()));
            return dto;
        }).toList();
        return Response.ok(customers).build();
    }

    /** @brief Single customer by ID. */
    @GET
    @Path("/{id}")
    public Response getCustomer(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        CustomerEntity entity = CustomerEntity.findById(id);
        if (entity == null || structureId <= 0 || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Customer dto = entity.toDto();
        dto.setLocationIds(locationIdsOf(id));
        return Response.ok(dto).build();
    }

    /** @brief Creates a customer. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response addCustomer(Customer customer,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (StructureEntity.count("id", structureId) == 0) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (customer == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Response invalid = validate(customer);
        if (invalid != null) return invalid;
        String code = customer.getCode().trim();
        if (codeInUse(structureId, code, 0)) {
            return ApiErrors.conflict("CUSTOMER_CODE_IN_USE");
        }
        CustomerEntity entity = new CustomerEntity();
        entity.applyDto(customer);
        entity.code = code;
        entity.name = customer.getName().trim();
        entity.structureId = structureId;
        entity.persist();
        syncLocations(entity, customer.getLocationIds());
        return Response.status(Response.Status.CREATED).entity(entity.toDto()).build();
    }

    /** @brief Updates a customer; the location association is rewritten from {@code locationIds}. */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateCustomer(@PathParam("id") int id, Customer customer,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (customer == null || structureId <= 0 || customer.getId() != id) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Response invalid = validate(customer);
        if (invalid != null) return invalid;
        String code = customer.getCode().trim();
        if (codeInUse(structureId, code, id)) {
            return ApiErrors.conflict("CUSTOMER_CODE_IN_USE");
        }
        CustomerEntity entity = CustomerEntity.findById(id);
        if (entity == null || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        entity.applyDto(customer);
        entity.code = code;
        entity.name = customer.getName().trim();
        syncLocations(entity, customer.getLocationIds());
        return Response.ok(entity.toDto()).build();
    }

    /**
     * @brief Deletes a customer.
     * @details Assignments referencing the customer are kept — they are the planning history —
     *          but the deletion is refused while assignments still point at it, because a
     *          {@code NOT NULL} customer would otherwise dangle. The UI must show the count.
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteCustomer(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        CustomerEntity entity = CustomerEntity.findById(id);
        if (entity == null || structureId <= 0 || entity.structureId != structureId) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (CustomerAssignmentEntity.count("customerId = ?1", id) > 0) {
            return ApiErrors.conflict("CUSTOMER_IN_USE");
        }
        LocationEntity.update("customerId = null where customerId = ?1", id);
        entity.delete();
        return Response.status(Response.Status.NO_CONTENT).build();
    }
}
