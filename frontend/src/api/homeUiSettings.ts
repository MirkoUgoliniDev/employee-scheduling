import { api } from './client'

export interface HomeUiSettings {
  id: number
  cover_key: string
  cover_data_url: string
  title_key: string
  body_key: string
  hint_key: string
}

export const homeUiSettingsApi = {
  get: () => api.get<HomeUiSettings>('/home-ui-settings'),
  save: (payload: HomeUiSettings) => api.put<HomeUiSettings>('/home-ui-settings', payload),
}
