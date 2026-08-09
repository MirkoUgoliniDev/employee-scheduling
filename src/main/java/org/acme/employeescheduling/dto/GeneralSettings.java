package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * General settings associated with a structure (contextual, like Solver Settings).
 * - shiftWindowMode: shift-window granularity in Shift Management ("week" | "month").
 * - autoPopulateFromTemplate: automatically populates empty periods from the location template.
 */
public class GeneralSettings {
    @JsonProperty("id") private int id;
    @JsonProperty("structure_id") private int structureId;
    @JsonProperty("shift_window_mode") private String shiftWindowMode = "month";
    @JsonProperty("auto_populate_from_template") private boolean autoPopulateFromTemplate = false;

    public GeneralSettings() {}

    public GeneralSettings(int id, int structureId, String shiftWindowMode, boolean autoPopulateFromTemplate) {
        this.id = id;
        this.structureId = structureId;
        this.shiftWindowMode = shiftWindowMode;
        this.autoPopulateFromTemplate = autoPopulateFromTemplate;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getStructureId() { return structureId; }
    public void setStructureId(int structureId) { this.structureId = structureId; }

    public String getShiftWindowMode() { return shiftWindowMode; }
    public void setShiftWindowMode(String shiftWindowMode) { this.shiftWindowMode = shiftWindowMode; }

    public boolean isAutoPopulateFromTemplate() { return autoPopulateFromTemplate; }
    public void setAutoPopulateFromTemplate(boolean autoPopulateFromTemplate) { this.autoPopulateFromTemplate = autoPopulateFromTemplate; }
}
