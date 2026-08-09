package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Global Home UI settings (cover image + text).
 */
public class HomeUiSettings {
    @JsonProperty("id") private int id;
    @JsonProperty("cover_key") private String coverKey = "";
    @JsonProperty("cover_data_url") private String coverDataUrl = "";
    @JsonProperty("title_key") private String titleKey = "home.title";
    @JsonProperty("body_key") private String bodyKey = "home.body";
    @JsonProperty("hint_key") private String hintKey = "home.hint";

    public HomeUiSettings() {}

    public HomeUiSettings(int id, String coverKey, String coverDataUrl, String titleKey, String bodyKey, String hintKey) {
        this.id = id;
        this.coverKey = coverKey;
        this.coverDataUrl = coverDataUrl;
        this.titleKey = titleKey;
        this.bodyKey = bodyKey;
        this.hintKey = hintKey;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getCoverKey() { return coverKey; }
    public void setCoverKey(String coverKey) { this.coverKey = coverKey; }

    public String getCoverDataUrl() { return coverDataUrl; }
    public void setCoverDataUrl(String coverDataUrl) { this.coverDataUrl = coverDataUrl; }

    public String getTitleKey() { return titleKey; }
    public void setTitleKey(String titleKey) { this.titleKey = titleKey; }

    public String getBodyKey() { return bodyKey; }
    public void setBodyKey(String bodyKey) { this.bodyKey = bodyKey; }

    public String getHintKey() { return hintKey; }
    public void setHintKey(String hintKey) { this.hintKey = hintKey; }
}
