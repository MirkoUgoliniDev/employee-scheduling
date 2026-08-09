package org.acme.employeescheduling.dto;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Label {

    @JsonProperty("id")
    private int id;

    @JsonProperty("key")
    private String key;

    @JsonProperty("description")
    private String description;

    @JsonProperty("translatedValue")
    private String translatedValue;

    @JsonProperty("translations")
    private Map<Integer, String> translations;

    /**
     * For entity-name pseudo-labels (skill.&lt;id&gt;, location.&lt;id&gt;), identifies the
     * target localization table ("skills"/"locations"). null/"labels" for regular UI labels.
     */
    @JsonProperty("entityType")
    private String entityType;

    @JsonProperty("entityId")
    private Integer entityId;

    public Label() {}

    public Label(int id, String key, String description) {
        this.id = id;
        this.key = key;
        this.description = description;
    }

    public Label(int id, String key, String description, String translatedValue) {
        this.id = id;
        this.key = key;
        this.description = description;
        this.translatedValue = translatedValue;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTranslatedValue() { return translatedValue; }
    public void setTranslatedValue(String translatedValue) { this.translatedValue = translatedValue; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public Integer getEntityId() { return entityId; }
    public void setEntityId(Integer entityId) { this.entityId = entityId; }

    public Map<Integer, String> getTranslations() { return translations; }
    public void setTranslations(Map<Integer, String> translations) { this.translations = translations; }

    @Override
    public String toString() {
        return "Label{id=" + id + ", key='" + key + "', description='" + description + "'}";
    }
}
