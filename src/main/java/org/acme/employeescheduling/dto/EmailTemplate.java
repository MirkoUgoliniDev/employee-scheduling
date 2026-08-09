package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Per-structure email template: subject and HTML body with placeholders.
 *
 * @details The subject and body may contain placeholders (e.g., {{Nominativo}}, {{Giorno}})
 *          which will be replaced when sending. One row per structure (upsert).
 */
public class EmailTemplate {

    /** @brief Unique template identifier. */
    @JsonProperty("id")
    private int id;

    /** @brief Owning structure. */
    @JsonProperty("structure_id")
    private int structureId;

    /** @brief Email subject (text with placeholders). */
    @JsonProperty("subject")
    private String subject;

    /** @brief Email body (HTML with placeholders). */
    @JsonProperty("body")
    private String body;

    public EmailTemplate() {}

    public EmailTemplate(int id, int structureId, String subject, String body) {
        this.id = id;
        this.structureId = structureId;
        this.subject = subject;
        this.body = body;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getStructureId() { return structureId; }
    public void setStructureId(int structureId) { this.structureId = structureId; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
