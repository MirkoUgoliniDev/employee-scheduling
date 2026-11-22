package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief One deviation from a series on a single date.
 *
 * @details {@code kind} is {@code CANCELLED} (the visit does not happen) or {@code MOVED} (it
 *          happens on {@code newDate} / at {@code newStartTime}). {@code occurrenceDate} stays the
 *          date the series defined, so the generator always knows which slot is accounted for.
 */
public class AssignmentException {

    @JsonProperty("id")
    public int id;

    @JsonProperty("seriesId")
    public int seriesId;

    @JsonProperty("occurrenceDate")
    public String occurrenceDate = "";

    @JsonProperty("kind")
    public String kind = "CANCELLED";

    @JsonProperty("newDate")
    public String newDate = "";

    @JsonProperty("newStartTime")
    public String newStartTime = "";

    @JsonProperty("notes")
    public String notes = "";
}
