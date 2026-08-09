package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardsoftbigdecimal.HardSoftBigDecimalScore;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.config.solver.termination.DiminishedReturnsTerminationConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.rest.exception.EmployeeScheduleSolverException;
import org.acme.employeescheduling.rest.exception.ErrorInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * @brief REST resource for managing employee schedule solving jobs.
 * @details Provides JAX-RS endpoints for submitting, retrieving, analyzing,
 *          and terminating employee scheduling optimization jobs. Uses the
 *          Timefold Solver to find optimal shift assignments for employees.
 *          Each submitted schedule is tracked as a job identified by a unique UUID.
 * @author Employee Scheduling Team
 * @version 1.0
 */
@Tag(name = "Employee Schedules", description = "Employee Schedules service for assigning employees to shifts.")
@RolesAllowed({"ADMIN", "CAPOSALA"})
@Path("schedules")
public class EmployeeScheduleResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmployeeScheduleResource.class);

    SolverManager<EmployeeSchedule, String> solverManager;
    SolutionManager<EmployeeSchedule, HardSoftBigDecimalScore> solutionManager;

    /** @brief Maximum number of concurrent solving jobs allowed. */
    private static final int MAX_JOBS = 100;

    /** Completed jobs are retained briefly for polling, then released to bound heap usage. */
    private static final Duration COMPLETED_JOB_TTL = Duration.ofMinutes(30);
    /**
     * @brief Grace period during which a newly created job cannot be removed.
     * @details Between insertion into the map and solver startup, the job is NOT_SOLVING and
     *          indistinguishable from a completed one: without this window, a concurrent request
     *          could discard it an instant before it starts.
     */
    private static final Duration EVICTION_GRACE = Duration.ofSeconds(30);

    /** @brief Concurrent map storing job ID to Job record associations. */
    private final ConcurrentMap<String, Job> jobIdToJob = new ConcurrentHashMap<>();

    /** Serializes capacity checks with insertion, preventing concurrent submissions from exceeding the limit. */
    private final Object jobSubmissionLock = new Object();

    /**
     * @brief Constructs the resource with injected solver and solution managers.
     * @param solverManager the Timefold solver manager for scheduling problems
     * @param solutionManager the Timefold solution manager for score analysis
     */
    @Inject
    public EmployeeScheduleResource(SolverManager<EmployeeSchedule, String> solverManager,
            SolutionManager<EmployeeSchedule, HardSoftBigDecimalScore> solutionManager) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    /**
     * @brief Lists all submitted job IDs.
     * @return a collection of job ID strings currently tracked
     */
    @Operation(summary = "List the job IDs of all submitted schedules.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "List of all job IDs.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(type = SchemaType.ARRAY, implementation = String.class))) })
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<String> list() {
        evictExpiredJobs();
        return java.util.List.copyOf(jobIdToJob.keySet());
    }

    /**
     * @brief Submits a schedule problem for solving.
     * @details Creates a new solving job, assigns it a unique UUID, and starts
     *          the solver asynchronously. Old jobs are evicted when the maximum
     *          number of jobs is exceeded.
     * @param problem the employee schedule problem to solve
     * @return the generated job ID string
     */
    @Operation(summary = "Submit a schedule to start solving as soon as CPU resources are available.")
    @APIResponses(value = {
            @APIResponse(responseCode = "202",
                    description = "The job ID. Use that ID to get the solution with the other methods.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(implementation = String.class))) })
    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces(MediaType.TEXT_PLAIN)
    public String solve(EmployeeSchedule problem) {
        final String jobId;
        synchronized (jobSubmissionLock) {
            evictOldJobs();
            if (jobIdToJob.size() >= MAX_JOBS) {
                throw new WebApplicationException("Too many schedule jobs are currently active. Try again later.",
                        Response.Status.TOO_MANY_REQUESTS);
            }
            jobId = UUID.randomUUID().toString();
            jobIdToJob.put(jobId, Job.ofSchedule(problem));
        }
        // The payload comes from the client and does not pass through /solver-settings validation:
        // clamp to the same ranges (5-600s) to prevent max_solve_seconds=0 from terminating
        // immediately with an almost-empty solution.
        long spentLimit = Math.min(Math.max(problem.getSolverSettings().getMaxSolveSeconds(), 5), 600);
        var termination = new TerminationConfig().withSecondsSpentLimit(spentLimit);
        if (problem.getSolverSettings().getUnimprovedSeconds() > 0)
            termination.withUnimprovedSecondsSpentLimit(
                    Math.min(problem.getSolverSettings().getUnimprovedSeconds(), spentLimit));
        if (problem.getSolverSettings().isStopWhenFeasible()) termination.withBestScoreFeasible(true);
        // Diminished-returns stop: stops when improvement within the window falls below ratio%
        // of the initial rate. Clamp ratio to [1,100] for the same reason as clamping seconds.
        if (problem.getSolverSettings().getDiminishedWindowSeconds() > 0)
            termination.withDiminishedReturnsConfig(new DiminishedReturnsTerminationConfig()
                    .withSlidingWindowSeconds(Math.min(
                            (long) problem.getSolverSettings().getDiminishedWindowSeconds(), spentLimit))
                    .withMinimumImprovementRatio(
                            Math.min(Math.max(problem.getSolverSettings().getDiminishedRatioPct(), 1), 100) / 100.0));
        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withConfigOverride(new SolverConfigOverride<EmployeeSchedule>().withTerminationConfig(termination))
                // The problem comes from the parameter, not a reread of the map: if the job were
                // removed in the meantime, rereading would cause NullPointerException and solving
                // would die with an uninformative error.
                .withProblemFinder(jobId_ -> problem)
                .withBestSolutionEventConsumer(event -> jobIdToJob.computeIfPresent(jobId,
                        (ignored, current) -> current.withSchedule(event.solution())))
                .withExceptionHandler((jobId_, exception) -> {
                    jobIdToJob.computeIfPresent(jobId, (ignored, current) -> current.withException(exception));
                    LOGGER.error("Failed solving jobId ({}).", jobId, exception);
                })
                .run();
        return jobId;
    }

    /**
     * @brief Analyzes the score of a given schedule.
     * @details Evaluates the schedule against defined constraints and returns
     *          a detailed score analysis. An optional fetch policy controls
     *          whether constraint matches are included.
     * @param problem the employee schedule to analyze
     * @param fetchPolicy optional policy controlling how much detail to include in the analysis
     * @return the score analysis result containing hard and soft score breakdowns
     */
    @Operation(summary = "Submit a schedule to analyze its score.")
    @APIResponses(value = {
            @APIResponse(responseCode = "202",
                    description = "Resulting score analysis, optionally without constraint matches.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ScoreAnalysis.class))) })
    @PUT
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces(MediaType.APPLICATION_JSON)
    @Path("analyze")
    public ScoreAnalysis<HardSoftBigDecimalScore> analyze(EmployeeSchedule problem,
            @QueryParam("fetchPolicy") ScoreAnalysisFetchPolicy fetchPolicy) {
        // Match details can dominate CPU and response size on large schedules. Keep them available
        // when explicitly requested, while making the unspecified/default request bounded.
        ScoreAnalysisFetchPolicy effectiveFetchPolicy = fetchPolicy == null
                ? ScoreAnalysisFetchPolicy.FETCH_MATCH_COUNT
                : fetchPolicy;
        return solutionManager.analyze(problem, effectiveFetchPolicy);
    }

    @Operation(
            summary = "Get the solution and score for a given job ID. This is the best solution so far, as it might still be running or not even started.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "The best solution of the schedule so far.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = EmployeeSchedule.class))),
            @APIResponse(responseCode = "404", description = "No schedule found.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class))),
            @APIResponse(responseCode = "500", description = "Exception during solving a schedule.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class)))
    })
    /**
     * @brief Retrieves the best solution found so far for a given job.
     * @param jobId the unique identifier of the solving job
     * @return the current best employee schedule solution with solver status
     * @throws EmployeeScheduleSolverException if the job is not found or an exception occurred during solving
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}")
    public EmployeeSchedule getEmployeeSchedule(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") String jobId) {
        EmployeeSchedule schedule = getEmployeeScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus);
        return schedule;
    }

    /**
     * @brief Retrieves the schedule for a job and checks for exceptions.
     * @param jobId the unique identifier of the solving job
     * @return the employee schedule associated with the job
     * @throws EmployeeScheduleSolverException if no job is found or an exception occurred during solving
     */
    private EmployeeSchedule getEmployeeScheduleAndCheckForExceptions(String jobId) {
        evictExpiredJobs();
        Job job = jobIdToJob.get(jobId);
        if (job == null) {
            throw new EmployeeScheduleSolverException(jobId, Response.Status.NOT_FOUND, "No schedule found.");
        }
        if (job.exception != null) {
            throw new EmployeeScheduleSolverException(jobId, job.exception);
        }
        return job.schedule;
    }

    @Operation(
            summary = "Terminate solving for a given job ID. Returns the best solution of the schedule so far, as it might still be running or not even started.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "The best solution of the schedule so far.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = EmployeeSchedule.class))),
            @APIResponse(responseCode = "404", description = "No schedule found.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class))),
            @APIResponse(responseCode = "500", description = "Exception during solving a schedule.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class)))
    })
    /**
     * @brief Terminates solving for a given job and returns the best solution found so far.
     * @param jobId the unique identifier of the solving job to terminate
     * @return the best employee schedule solution at the time of termination
     * @throws EmployeeScheduleSolverException if the job is not found or an exception occurred during solving
     */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}")
    public EmployeeSchedule terminateSolving(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") String jobId) {
        // Asynchronous fire-and-forget stop: terminateEarly returns immediately, and the client
        // (handleStopSolve) discards this response anyway by reloading the schedule from the DB.
        // Blocking for the final solution would provide no observable benefit;
        // terminateEarlyAndWait does not yet exist in API 1.33 (upstream issue #77).
        solverManager.terminateEarly(jobId);
        return getEmployeeSchedule(jobId);
    }

    @Operation(
            summary = "Get the schedule status and score for a given job ID.")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "The schedule status and the best score so far.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = EmployeeSchedule.class))),
            @APIResponse(responseCode = "404", description = "No schedule found.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class))),
            @APIResponse(responseCode = "500", description = "Exception during solving a schedule.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ErrorInfo.class)))
    })
    /**
     * @brief Retrieves the solver status and current score for a given job.
     * @param jobId the unique identifier of the solving job
     * @return an EmployeeSchedule containing only the score and solver status
     * @throws EmployeeScheduleSolverException if the job is not found or an exception occurred during solving
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}/status")
    public EmployeeSchedule getStatus(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") String jobId) {
        EmployeeSchedule schedule = getEmployeeScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        return new EmployeeSchedule(schedule.getScore(), solverStatus);
    }

    /**
     * @brief Evicts completed jobs when the maximum job limit is exceeded.
     * @details Iterates through stored jobs and removes those with a NOT_SOLVING
     *          status until the job count is at or below the maximum threshold.
     */
    private void evictOldJobs() {
        evictExpiredJobs();
        if (jobIdToJob.size() < MAX_JOBS) {
            return;
        }
        Instant evictableBefore = Instant.now().minus(EVICTION_GRACE);
        jobIdToJob.entrySet().stream()
                .filter(entry -> solverManager.getSolverStatus(entry.getKey()) == SolverStatus.NOT_SOLVING)
                .filter(entry -> entry.getValue().lastUpdatedAt.isBefore(evictableBefore))
                .sorted(Comparator.comparing(entry -> entry.getValue().lastUpdatedAt))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(jobIdToJob::remove);
    }

    /** Removes completed jobs whose polling retention window elapsed; active jobs are never evicted. */
    private void evictExpiredJobs() {
        Instant expiryThreshold = Instant.now().minus(COMPLETED_JOB_TTL);
        for (Map.Entry<String, Job> entry : jobIdToJob.entrySet()) {
            SolverStatus status = solverManager.getSolverStatus(entry.getKey());
            if (status == SolverStatus.NOT_SOLVING && entry.getValue().lastUpdatedAt.isBefore(expiryThreshold)) {
                jobIdToJob.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * @brief Internal record representing a solving job.
     * @details Holds either a schedule solution or an exception that occurred during solving.
     * @param schedule the employee schedule solution, or null if an exception occurred
     * @param exception the exception that occurred, or null if solving was successful
     */
    private record Job(EmployeeSchedule schedule, Throwable exception, Instant createdAt, Instant lastUpdatedAt) {

        /**
         * @brief Creates a Job containing a schedule solution.
         * @param schedule the employee schedule solution
         * @return a new Job instance with the given schedule and no exception
         */
        static Job ofSchedule(EmployeeSchedule schedule) {
            Instant now = Instant.now();
            return new Job(schedule, null, now, now);
        }

        /**
         * @brief Creates a Job containing an exception.
         * @param error the exception that occurred during solving
         * @return a new Job instance with no schedule and the given exception
         */
        Job withSchedule(EmployeeSchedule updatedSchedule) {
            return new Job(updatedSchedule, null, createdAt, Instant.now());
        }

        Job withException(Throwable error) {
            return new Job(null, error, createdAt, Instant.now());
        }
    }
}
