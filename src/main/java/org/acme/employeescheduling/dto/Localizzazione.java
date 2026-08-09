package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Localizzazione {

    @JsonProperty("id")
    private int id;

    @JsonProperty("entityType")
    private String entityType;

    @JsonProperty("entityId")
    private int entityId;

    @JsonProperty("fieldName")
    private String fieldName;

    @JsonProperty("languageId")
    private int languageId;

    @JsonProperty("value")
    private String value;

    public Localizzazione() {}

    public Localizzazione(int id, String entityType, int entityId, String fieldName, int languageId, String value) {
        this.id = id;
        this.entityType = entityType;
        this.entityId = entityId;
        this.fieldName = fieldName;
        this.languageId = languageId;
        this.value = value;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public int getEntityId() { return entityId; }
    public void setEntityId(int entityId) { this.entityId = entityId; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public int getLanguageId() { return languageId; }
    public void setLanguageId(int languageId) { this.languageId = languageId; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    @Override
    public String toString() {
        return "Localizzazione{entityType='" + entityType + "', entityId=" + entityId
                + ", fieldName='" + fieldName + "', languageId=" + languageId
                + ", value='" + value + "'}";
    }
}
