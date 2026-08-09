import { api } from './client'

/** Structure-specific general settings (window granularity and automatic population). */
export interface GeneralSettings {
  id: number
  structure_id: number
  /** Shift-window granularity ('week' | 'month'). */
  shift_window_mode: 'week' | 'month'
  /** Automatically populates empty periods from the location template. */
  auto_populate_from_template: boolean
}

export const generalSettingsApi = {
  get: (structureId: number) => api.get<GeneralSettings>(`/general-settings?structureId=${structureId}`),
  save: (structureId: number, value: GeneralSettings) => api.put<GeneralSettings>(`/general-settings?structureId=${structureId}`, value),
}
