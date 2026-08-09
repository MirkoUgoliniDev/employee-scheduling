import { api } from './client'

/** Shared appearance of PDF reports; shift and employee data remains dynamic. */
export interface PdfTemplate {
  id: number
  structure_id: number
  header_text: string
  footer_text: string
  logo_data_url: string
  primary_color: string
}

export const EMPTY_PDF_TEMPLATE: Omit<PdfTemplate, 'id' | 'structure_id'> = {
  header_text: '',
  footer_text: '',
  logo_data_url: '',
  primary_color: '#2980B9',
}

export const pdfTemplatesApi = {
  get: (structureId: number) =>
    api.get<PdfTemplate>(`/demo-data/pdf-template?structureId=${structureId}`),
  save: (structureId: number, payload: Omit<PdfTemplate, 'id' | 'structure_id'>) =>
    api.put<PdfTemplate>(`/demo-data/pdf-template?structureId=${structureId}`, payload),
  delete: (structureId: number) =>
    api.delete<void>(`/demo-data/pdf-template?structureId=${structureId}`),
}
