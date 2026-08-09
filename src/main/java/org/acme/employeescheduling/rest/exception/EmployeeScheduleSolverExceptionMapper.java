package org.acme.employeescheduling.rest.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * @brief JAX-RS exception mapper for EmployeeScheduleSolverException.
 * @details Converts EmployeeScheduleSolverException instances into structured
 *          JSON error responses containing the job ID and error message.
 *          Automatically registered as a JAX-RS provider.
 * @author Employee Scheduling Team
 * @version 1.0
 */
@Provider
public class EmployeeScheduleSolverExceptionMapper implements ExceptionMapper<EmployeeScheduleSolverException> {

    /**
     * @brief Maps an EmployeeScheduleSolverException to a JAX-RS Response.
     * @param exception the solver exception to convert
     * @return a Response containing the appropriate HTTP status and an ErrorInfo JSON body
     */
    @Override
    public Response toResponse(EmployeeScheduleSolverException exception) {
        return Response
                .status(exception.getStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorInfo(exception.getJobId(), exception.getMessage()))
                .build();
    }
}
