package org.acme.employeescheduling.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Recurring-visit template that generates concrete customer assignments.
 *
 * @details {@code patternType} is one of {@code WEEKLY}, {@code BIWEEKLY} or
 *          {@code MONTHLY_NTH_WEEKDAY}; {@code weekdays} holds ISO weekday numbers 1..7 (Monday
 *          to Sunday) comma separated. For {@code MONTHLY_NTH_WEEKDAY}, {@code nth} selects which
 *          occurrence of that weekday in the month is meant, and <b>5 means the last one</b>
 *          (a real planning need: "the last Friday of the month", which exists in every month).
 *
 *          <p>The series owns no positions of its own — {@code generatedCount} on the response is
 *          informational, and everything the solver sees hangs off the occurrences.
 */
public class AssignmentSeries {

    @JsonProperty("id")
    public int id;

    @JsonProperty("structureId")
    public int structureId = 1;

    @JsonProperty("customerId")
    public int customerId;

    @JsonProperty("customerName")
    public String customerName = "";

    @JsonProperty("locationId")
    public int locationId;

    @JsonProperty("locationName")
    public String locationName = "";

    @JsonProperty("title")
    public String title = "";

    @JsonProperty("service")
    public String service = "";

    @JsonProperty("startTime")
    public String startTime = "08:00";

    @JsonProperty("durationMinutes")
    public int durationMinutes = 480;

    @JsonProperty("priority")
    public int priority = 2;

    @JsonProperty("fixedTime")
    public boolean fixedTime;

    @JsonProperty("notes")
    public String notes = "";

    @JsonProperty("patternType")
    public String patternType = "WEEKLY";

    @JsonProperty("weekdays")
    public String weekdays = "1";

    @JsonProperty("nth")
    public int nth;

    @JsonProperty("effectiveFrom")
    public String effectiveFrom = "";

    @JsonProperty("effectiveTo")
    public String effectiveTo = "";

    @JsonProperty("active")
    public boolean active = true;

    /** How many occurrences of this series exist right now (read-only, filled by the resource). */
    @JsonProperty("occurrenceCount")
    public int occurrenceCount;

    @JsonProperty("requirements")
    public List<AssignmentSeriesRequirement> requirements = new ArrayList<>();
}
