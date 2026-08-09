package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Email delivery log entry: latest successful delivery per employee and period.
 *
 * @details Returned by GET /email/log: the frontend uses it to show
 *          "Sent on ..." on Report rows even after changing pages.
 */
public class EmailLogEntry {

    /** @brief Recipient employee. */
    @JsonProperty("employee_id")
    private int employeeId;

    /** @brief Date/time of the latest delivery, local "yyyy-MM-dd HH:mm:ss". */
    @JsonProperty("sent_at")
    private String sentAt;

    /** @brief Address to which it was sent. */
    @JsonProperty("sent_to")
    private String sentTo;

    public EmailLogEntry() {}

    public EmailLogEntry(int employeeId, String sentAt, String sentTo) {
        this.employeeId = employeeId;
        this.sentAt = sentAt;
        this.sentTo = sentTo;
    }

    public int getEmployeeId() { return employeeId; }
    public void setEmployeeId(int employeeId) { this.employeeId = employeeId; }

    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    public String getSentTo() { return sentTo; }
    public void setSentTo(String sentTo) { this.sentTo = sentTo; }
}
