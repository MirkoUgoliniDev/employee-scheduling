---
name: add-api-endpoint
description: Add or change a REST endpoint — the JAX-RS resource method, its DTO, the role that guards it, the error contract, and the matching frontend client function. Use for a new operation on data that already exists; for a whole new persisted concept use add-entity instead.
---

# Adding a REST endpoint

## 1. The resource method

In the existing `rest/*Resource.java` for that area, following its conventions:

```java
@GET
@Path("/{id}/thing")
public Thing getThing(@PathParam("id") Integer id) { … }
```

- Writes get `@Transactional`.
- Input and output are **DTOs** from `dto/`, never entities. The entity's `toDto()` is
  the boundary.
- Validate inputs explicitly; `RequiredFieldValidationTest` is the model for what the
  project expects.

## 2. The role — decide it, do not inherit it by accident

`@RolesAllowed` at class level covers the whole resource. If the new method needs a
different role, annotate the method.

- `ADMIN` — configuration, backups, users, the skill catalogue
- `CAPOSALA` — shifts, employees, records

An endpoint reachable without the right role is a security bug. `/backup/*` additionally
always requires `BACKUP_ADMIN_TOKEN`, sent as `X-Backup-Admin-Token` — `client.ts`
attaches it automatically for those paths.

Add a case to `AuthenticationEnforcementTest`: the endpoint must be proven to reject the
wrong role, not assumed to.

## 3. The error contract

Errors go through `rest/exception/` with a **machine-readable code** in a structured JSON
body. The frontend reads it with `errorCode()` and decides what to show the user; a bare
500 leaves it with nothing to say but a generic message.

A 401 anywhere triggers the shared listener in `client.ts`, which returns the user to the
login page **once** rather than firing a toast per in-flight request. Do not re-implement
that per call site.

## 4. The frontend client function

In the matching `frontend/src/api/*.ts`, on top of `api` from `client.ts`. Export a
TypeScript `interface` for any new payload shape. Doxygen comments, as in the other
modules.

**New top-level path?** Add it to the `proxy` block in `frontend/vite.config.ts`.
Otherwise it 404s in dev and works in production — a failure that wastes an afternoon.
Also check it does not collide with SPA routing (`SpaRoutingFilter`,
`SpaFallbackResourceTest`).

## 5. Anything the user reads

Error messages, confirmations and button labels are UI text: `t()` plus five languages
plus the `CACHE_KEY` bump. See the `add-ui-string` skill.

## 6. Verify

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
cd frontend && npm run lint -- --max-warnings=0 && npm run build
```

Both profiles: an endpoint that touches persistence can pass on one engine and fail on
the other.
