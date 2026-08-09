package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Shared appearance of PDFs generated for a structure.
 *
 * @details Report content remains dynamic; this object configures only the logo, header,
 *          footer, and primary color. Only one row may exist for each structure.
 */
public class PdfTemplate {

    @JsonProperty("id")
    private int id;

    @JsonProperty("structure_id")
    private int structureId;

    @JsonProperty("header_text")
    private String headerText;

    @JsonProperty("footer_text")
    private String footerText;

    /** PNG/JPEG logo as a data URL; empty string when not configured. */
    @JsonProperty("logo_data_url")
    private String logoDataUrl;

    /** Hex color used for the header and table (for example, #2980B9). */
    @JsonProperty("primary_color")
    private String primaryColor;

    public PdfTemplate() {}

    public PdfTemplate(int id, int structureId, String headerText, String footerText,
                       String logoDataUrl, String primaryColor) {
        this.id = id;
        this.structureId = structureId;
        this.headerText = headerText;
        this.footerText = footerText;
        this.logoDataUrl = logoDataUrl;
        this.primaryColor = primaryColor;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getStructureId() { return structureId; }
    public void setStructureId(int structureId) { this.structureId = structureId; }
    public String getHeaderText() { return headerText; }
    public void setHeaderText(String headerText) { this.headerText = headerText; }
    public String getFooterText() { return footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }
    public String getLogoDataUrl() { return logoDataUrl; }
    public void setLogoDataUrl(String logoDataUrl) { this.logoDataUrl = logoDataUrl; }
    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
}
