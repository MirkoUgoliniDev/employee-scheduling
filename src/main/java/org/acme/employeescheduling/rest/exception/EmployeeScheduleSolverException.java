package org.acme.employeescheduling.rest.exception;

import jakarta.ws.rs.core.Response;

/**
 * @brief Custom runtime exception for employee schedule solver errors.
 * @details Encapsulates error information including the associated job ID
 *          and HTTP response status, enabling structured error responses
 *          through the corresponding exception mapper.
 * @author Employee Scheduling Team
 * @version 1.0
 */
public class EmployeeScheduleSolverException extends RuntimeException {

    /** @brief The unique identifier of the solving job that caused the error. */
    private final String jobId;

    /** @brief The HTTP response status code associated with this exception. */
    private final Response.Status status;

    /**
     * @brief Constructs an exception with a specific job ID, HTTP status, and message.
     * @param jobId the unique identifier of the solving job
     * @param status the HTTP response status to return
     * @param message the error message describing the problem
     */
    public EmployeeScheduleSolverException(String jobId, Response.Status status, String message) {
        super(message);
        this.jobId = jobId;
        this.status = status;
    }

    /**
     * @brief Constructs an exception wrapping a cause, defaulting to INTERNAL_SERVER_ERROR.
     * @param jobId the unique identifier of the solving job
     * @param cause the underlying throwable that caused this exception
     */
    public EmployeeScheduleSolverException(String jobId, Throwable cause) {
        super(cause.getMessage(), cause);
        this.jobId = jobId;
        this.status = Response.Status.INTERNAL_SERVER_ERROR;
    }

    /**
     * @brief Returns the job ID associated with this exception.
     * @return the job ID string
     */
    public String getJobId() {
        return jobId;
    }

    /**
     * @brief Returns the HTTP response status associated with this exception.
     * @return the HTTP response status
     */
    public Response.Status getStatus() {
        return status;
    }
}
