package org.acme.employeescheduling.rest.exception;

/**
 * @brief Data record representing structured error information.
 * @details Used as the JSON response body for solver-related errors,
 *          containing the associated job ID and a human-readable error message.
 * @author Employee Scheduling Team
 * @version 1.0
 *
 * @param jobId the unique identifier of the solving job that caused the error
 * @param message a human-readable description of the error
 */
public record ErrorInfo(String jobId, String message) {
}
