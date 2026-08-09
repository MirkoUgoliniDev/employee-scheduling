package org.acme.employeescheduling.rest;

import java.util.Map;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * @brief Machine-code error responses, translatable by the frontend.
 *
 * @details The backend does not know the user's language: a message written in Italian in the
 *          response body reaches the screen unchanged for an English or German user too.
 *          By returning a stable code instead (for example {@code EMPLOYEE_CODE_REQUIRED}),
 *          the translation stays where all the other UI strings live: the i18n catalog.
 *          The frontend reads the code with the {@code errorCode()} helper and picks the key.
 *
 * <p>The body shape is {@code {"error": "CODICE"}}, the same one already adopted by
 * {@link StructureResource}.</p>
 */
final class ApiErrors {

    private ApiErrors() {
    }

    static Response badRequest(String code) {
        return of(Response.Status.BAD_REQUEST, code);
    }

    static Response conflict(String code) {
        return of(Response.Status.CONFLICT, code);
    }

    static Response notFound(String code) {
        return of(Response.Status.NOT_FOUND, code);
    }

    static Response tooManyRequests(String code) {
        return of(Response.Status.TOO_MANY_REQUESTS, code);
    }

    static Response serverError(String code) {
        return of(Response.Status.INTERNAL_SERVER_ERROR, code);
    }

    static Response unauthorized(String code) {
        return of(Response.Status.UNAUTHORIZED, code);
    }

    private static Response of(Response.Status status, String code) {
        return Response.status(status).entity(Map.of("error", code)).type(MediaType.APPLICATION_JSON).build();
    }
}
