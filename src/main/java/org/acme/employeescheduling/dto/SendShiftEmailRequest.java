package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Request to email shifts to an employee, with an attached PDF.
 *
 * @details The PDF is generated client-side (jsPDF) and arrives Base64-encoded.
 *          Subject and body are taken from the structure's email template, with the
 *          {{Nominativo}} and {{Giorno}} placeholders replaced server-side.
 */
public class SendShiftEmailRequest {

    /** @brief Recipient employee (the address is read from the DB). */
    @JsonProperty("employee_id")
    private int employeeId;

    /** @brief Structure: determines which email template to use. */
    @JsonProperty("structure_id")
    private int structureId;

    /** @brief Period label (for example, "29 Jun – 5 Jul 2026") for {{Giorno}}. */
    @JsonProperty("period_label")
    private String periodLabel;

    /** @brief Period slug ("2026-06-29" for a week, "2026-06" for a month) — delivery log key. */
    @JsonProperty("period_slug")
    private String periodSlug;

    /** @brief PDF attachment filename. */
    @JsonProperty("filename")
    private String filename;

    /** @brief PDF content, Base64-encoded. */
    @JsonProperty("pdf_base64")
    private String pdfBase64;

    public SendShiftEmailRequest() {}

    public int getEmployeeId() { return employeeId; }
    public void setEmployeeId(int employeeId) { this.employeeId = employeeId; }

    public int getStructureId() { return structureId; }
    public void setStructureId(int structureId) { this.structureId = structureId; }

    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }

    public String getPeriodSlug() { return periodSlug; }
    public void setPeriodSlug(String periodSlug) { this.periodSlug = periodSlug; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getPdfBase64() { return pdfBase64; }
    public void setPdfBase64(String pdfBase64) { this.pdfBase64 = pdfBase64; }
}
