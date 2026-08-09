package org.acme.employeescheduling.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.acme.employeescheduling.dto.AppUser;
import org.acme.employeescheduling.persistence.AppUserEntity;

import java.util.List;
import java.util.Map;

/**
 * @brief Application-user CRUD — restricted to ADMIN users.
 *
 * @details The first administrator is created through public registration (email + OTP, first
 *          user of the installation); all others are managed here: creation, role/active changes,
 *          passwords, deactivation, and approval of HEAD_NURSE users registered via OTP.
 *          Deletion is forbidden: accounts are deactivated, preserving the account and its
 *          latest login information for auditing.
 */
@RolesAllowed("ADMIN")
@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UsersResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "quarkus.datasource.db-kind")
    String dbKind;

    @GET
    public Response list() {
        List<AppUser> users = AppUserEntity.<AppUserEntity>listAll()
                .stream()
                .map(UsersResource::toDto)
                .toList();
        return Response.ok(users).build();
    }

    @POST
    @Transactional
    public Response create(AppUser user) {
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return ApiErrors.badRequest("USER_USERNAME_REQUIRED");
        }
        if (user.getRawPassword() == null || user.getRawPassword().isBlank()) {
            return ApiErrors.badRequest("USER_PASSWORD_REQUIRED");
        }
        if (!isValidRole(user.getRole())) {
            return ApiErrors.badRequest("USER_ROLE_INVALID");
        }
        if (AppUserEntity.findByUsername(user.getUsername()) != null) {
            return ApiErrors.conflict("USER_DUPLICATE");
        }
        AppUserEntity entity = AppUserEntity.create(
                user.getUsername(), user.getRawPassword(),
                normalizeRole(user.getRole()),
                user.getDisplayName());
        String email = normalizeEmail(user.getEmail());
        if (email != null && !email.isBlank()) {
            if (!isValidEmail(email)) {
                return ApiErrors.badRequest("EMAIL_INVALID");
            }
            if (AppUserEntity.findByEmail(email) != null) {
                return ApiErrors.conflict("USER_EMAIL_DUPLICATE");
            }
            entity.email = email;
        }
        entity.persist();
        return Response.status(Response.Status.CREATED).entity(toDto(entity)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") int id, AppUser user) {
        AppUserEntity entity = AppUserEntity.findById(id);
        if (entity == null) {
            return ApiErrors.notFound("USER_NOT_FOUND");
        }
        boolean isSelf = identity.getPrincipal() != null
                && identity.getPrincipal().getName().equals(entity.username);

        // Self-demotion/deactivation: forbidden just like DELETE (permanent lockout).
        if (isSelf && (Boolean.FALSE.equals(user.getActive())
                || (user.getRole() != null && !user.getRole().isBlank()
                    && AppUserEntity.ROLE_CAPOSALA.equalsIgnoreCase(user.getRole())
                    && AppUserEntity.ROLE_ADMIN.equals(entity.role)))) {
            return ApiErrors.badRequest("USER_CANNOT_SELF_DEACTIVATE");
        }

        // The installation cannot be left without an active ADMIN.
        if (AppUserEntity.ROLE_ADMIN.equals(entity.role) && entity.active
                && (Boolean.FALSE.equals(user.getActive())
                    || (user.getRole() != null && !user.getRole().isBlank()
                        && AppUserEntity.ROLE_CAPOSALA.equalsIgnoreCase(user.getRole())))
                && countActiveAdmins() <= 1) {
            return ApiErrors.conflict("USER_LAST_ADMIN");
        }

        if (user.getUsername() != null && !user.getUsername().isBlank()
                && !user.getUsername().equals(entity.username)) {
            if (AppUserEntity.findByUsername(user.getUsername()) != null) {
                return ApiErrors.conflict("USER_DUPLICATE");
            }
            entity.username = user.getUsername();
        }
        if (user.getDisplayName() != null) {
            entity.displayName = user.getDisplayName();
        }
        String email = normalizeEmail(user.getEmail());
        if (user.getEmail() != null) {
            if (email.isBlank()) {
                entity.email = null;
            } else {
                if (!isValidEmail(email)) {
                    return ApiErrors.badRequest("EMAIL_INVALID");
                }
                AppUserEntity existing = AppUserEntity.findByEmail(email);
                if (existing != null && !existing.id.equals(entity.id)) {
                    return ApiErrors.conflict("USER_EMAIL_DUPLICATE");
                }
                entity.email = email;
            }
        }
        // `active` is nullable in the payload: when absent (while changing other fields), do NOT
        // touch it, so a pending user is not accidentally reactivated.
        if (user.getActive() != null) {
            entity.active = user.getActive();
        }
        if (user.getRawPassword() != null && !user.getRawPassword().isBlank()) {
            entity.changePassword(user.getRawPassword());
        }
        if (user.getRole() != null && !user.getRole().isBlank()) {
            if (!isValidRole(user.getRole())) {
                return ApiErrors.badRequest("USER_ROLE_INVALID");
            }
            entity.role = normalizeRole(user.getRole());
        }
        return Response.ok(toDto(entity)).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deactivate(@PathParam("id") int id) {
        AppUserEntity entity = AppUserEntity.findById(id);
        if (entity == null) {
            return ApiErrors.notFound("USER_NOT_FOUND");
        }
        if (identity.getPrincipal().getName().equals(entity.username)) {
            return ApiErrors.badRequest("USER_CANNOT_SELF_DEACTIVATE");
        }
        // The installation cannot be left without an active ADMIN.
        if (AppUserEntity.ROLE_ADMIN.equals(entity.role) && entity.active
                && countActiveAdmins() <= 1) {
            return ApiErrors.conflict("USER_LAST_ADMIN");
        }
        entity.active = false;
        return Response.ok().entity(Map.of("deactivated", true)).build();
    }

    /**
     * @brief Counts the remaining active ADMIN users without allowing two requests to overlap.
     *
     * @details Counting alone is a "check then act" race: two ADMIN users deactivating each other
     *          at the same instant both read 2, both pass the check, and both commit — leaving the
     *          installation without an active administrator, recoverable only through a manual
     *          database UPDATE. A write lock on active ADMIN rows (SELECT ... FOR UPDATE) queues
     *          the two transactions: the second rereads after the first commits and receives
     *          the USER_LAST_ADMIN rejection.
     *
     *          The lock applies only to PostgreSQL: SQLite allows only one writer at a time and
     *          does not support FOR UPDATE, so the race does not exist there and the clause would
     *          be a syntax error.
     */
    private long countActiveAdmins() {
        if (!"postgresql".equalsIgnoreCase(dbKind)) {
            return AppUserEntity.count("active = ?1 and role = ?2",
                    true, AppUserEntity.ROLE_ADMIN);
        }
        return entityManager
                .createQuery("select u from AppUserEntity u where u.active = true and u.role = :role",
                        AppUserEntity.class)
                .setParameter("role", AppUserEntity.ROLE_ADMIN)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .size();
    }

    private static boolean isValidRole(String role) {
        return role != null && (AppUserEntity.ROLE_ADMIN.equals(role)
                || AppUserEntity.ROLE_CAPOSALA.equals(role)
                || AppUserEntity.ROLE_ADMIN.equalsIgnoreCase(role)
                || AppUserEntity.ROLE_CAPOSALA.equalsIgnoreCase(role));
    }

    private static String normalizeRole(String role) {
        return role == null ? null : role.toUpperCase();
    }

    private static AppUser toDto(AppUserEntity entity) {
        AppUser dto = new AppUser(
                entity.id,
                entity.username,
                entity.role,
                entity.active,
                entity.displayName,
                entity.createdAt,
                entity.lastLoginAt);
        dto.setEmail(entity.email);
        return dto;
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    /** @brief Normalizes the email (trim + lowercase) as in the registration flow. */
    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
