/**
 * @file pdfHelpers.ts
 * @brief Utilities for generating PDF reports with jsPDF.
 *
 * @details
 * Esporta due funzioni principali (entrambe restituiscono `{ blob, filename }`,
 * without downloading: the caller decides whether to open, download, or attach it):
 * - `generateEmployeePdf()` — employee shift report for the period
 * - `generateCoveragePdf()` — location coverage report for the period
 *
 * PDF structure:
 * - Intestazione colorata (blu, `addHeader`)
 * - Shift table with alternating light/dark rows
 * - Riepilogo ore totali
 * - Footer with page number and generation date (`addFooters`)
 *
 * Day/month labels were originally hardcoded in Italian (they do not use the i18n system
 * because the PDF is generated client-side).
 */

import { jsPDF } from 'jspdf'
import type { Shift, ScheduleData } from '../api/shifts'
import type { PdfTemplate } from '../api/pdfTemplates'

export type PdfBranding = Pick<PdfTemplate, 'header_text' | 'footer_text' | 'logo_data_url' | 'primary_color'> & {
  structure_name?: string
  filename_shifts?: string
  filename_coverage?: string
}

/** Sanitizes part of a filename while retaining Unicode letters, numbers, hyphens, and underscores. */
function safeFilePart(value: string, fallback: string): string {
  const safe = value.trim().replace(/\s+/g, '_').replace(/[^\p{L}\p{N}_-]+/gu, '')
  return safe || fallback
}

function rgb(hex?: string): [number, number, number] {
  const value = /^#[0-9a-f]{6}$/i.test(hex ?? '') ? hex!.slice(1) : '2980B9'
  return [parseInt(value.slice(0, 2), 16), parseInt(value.slice(2, 4), 16), parseInt(value.slice(4, 6), 16)]
}

// ─── i18n-aware day/month labels ─────────────────────────────────────────────
const DAYS_SHORT   = ['Dom','Lun','Mar','Mer','Gio','Ven','Sab']
const MONTHS_LONG  = ['Gennaio','Febbraio','Marzo','Aprile','Maggio','Giugno',
                      'Luglio','Agosto','Settembre','Ottobre','Novembre','Dicembre']
const MONTHS_SHORT = ['Gen','Feb','Mar','Apr','Mag','Giu','Lug','Ago','Set','Ott','Nov','Dic']

export const rptDay    = (idx: number) => DAYS_SHORT[idx]
export const rptMonth  = (idx: number) => MONTHS_LONG[idx]
export const rptMonthS = (idx: number) => MONTHS_SHORT[idx]

export function formatTime(d: Date): string {
  return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}

// ─── Intestazione PDF comune ──────────────────────────────────────────────────
function addHeader(doc: jsPDF, title: string, subtitle: string, monthLabel: string, branding?: PdfBranding) {
  const pageW = doc.internal.pageSize.getWidth()
  const color = rgb(branding?.primary_color)
  doc.setFillColor(...color)
  doc.rect(0, 0, pageW, 30, 'F')
  let textCenter = pageW / 2
  if (branding?.logo_data_url) {
    try {
      const props = doc.getImageProperties(branding.logo_data_url)
      const maxW = 30, maxH = 20
      const scale = Math.min(maxW / props.width, maxH / props.height)
      const w = props.width * scale, h = props.height * scale
      doc.addImage(branding.logo_data_url, props.fileType, 12, (30 - h) / 2, w, h)
      textCenter += 10
    } catch { /* A corrupted logo must not prevent report generation. */ }
  }
  doc.setTextColor(255, 255, 255)
  doc.setFont('helvetica', 'bold')
  const heading = branding?.header_text?.trim() || branding?.structure_name?.trim() || title
  doc.setFontSize(heading.length > 45 ? 12 : 15)
  doc.text(heading.substring(0, 80), textCenter, 10, { align: 'center', maxWidth: 135 })
  doc.setFontSize(11)
  doc.text(`${title} — ${subtitle}`, textCenter, 21, { align: 'center', maxWidth: 145 })
  doc.setTextColor(0, 0, 0)
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(12)
  doc.text(monthLabel, pageW / 2, 38, { align: 'center' })
  doc.setDrawColor(...color)
  doc.setLineWidth(0.6)
  doc.line(15, 42, pageW - 15, 42)
}

// ─── Footer ──────────────────────────────────────────────────────────────────
function addFooters(doc: jsPDF, branding?: PdfBranding) {
  const pageW   = doc.internal.pageSize.getWidth()
  const pageH   = doc.internal.pageSize.getHeight()
  const total   = doc.getNumberOfPages()
  const today   = new Date()
  const dateStr = `${String(today.getDate()).padStart(2,'0')}/${String(today.getMonth()+1).padStart(2,'0')}/${today.getFullYear()}`
  for (let p = 1; p <= total; p++) {
    doc.setPage(p)
    doc.setFontSize(8)
    doc.setFont('helvetica', 'italic')
    doc.setTextColor(130, 130, 130)
    const automatic = `Generato il ${dateStr}  –  Pagina ${p} di ${total}`
    const custom = branding?.footer_text?.trim()
    if (custom) {
      doc.text(custom.substring(0, 140), pageW / 2, pageH - 9, { align: 'center', maxWidth: pageW - 30 })
      doc.text(automatic, pageW / 2, pageH - 4, { align: 'center' })
    } else doc.text(automatic, pageW / 2, pageH - 6, { align: 'center' })
  }
}

// ─── Table column header ──────────────────────────────────────────────────────
function addTableHeader(doc: jsPDF, y: number, cols: { x: number; label: string }[], contentW: number, marginL: number, branding?: PdfBranding) {
  const ROW_H = 9
  doc.setFillColor(...rgb(branding?.primary_color))
  doc.rect(marginL, y - 6, contentW, ROW_H, 'F')
  doc.setTextColor(255, 255, 255)
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(9)
  cols.forEach(({ x, label }) => doc.text(label, x, y))
  return y + ROW_H
}

// ─── Employee shift report ────────────────────────────────────────────────────
export function generateEmployeePdf(
  employee: ScheduleData['employees'][0],
  shifts: Shift[],
  monthLabel: string,
  monthValue: string,
  branding?: PdfBranding,
) {
  const doc = new jsPDF({ orientation: 'portrait', unit: 'mm', format: 'a4' })
  const pageW = doc.internal.pageSize.getWidth()
  const pageH = doc.internal.pageSize.getHeight()
  const marginL = 15, marginR = 15
  const contentW = pageW - marginL - marginR

  addHeader(doc, 'Report Turni', employee.fullName, monthLabel, branding)

  let y = 52
  if (shifts.length === 0) {
    doc.setFontSize(12); doc.setTextColor(150, 0, 0)
    doc.text('Nessun turno assegnato per questo periodo.', pageW / 2, y, { align: 'center' })
  } else {
    const COLS = [
      { x: marginL, label: 'Giorno' },
      { x: 65,      label: 'Location' },
      { x: 145,     label: 'Inizio' },
      { x: 170,     label: 'Fine' },
    ]
    const ROW_H = 9
    y = addTableHeader(doc, y, COLS, contentW, marginL, branding)

    doc.setTextColor(0, 0, 0); doc.setFont('helvetica', 'normal'); doc.setFontSize(9)

    shifts.forEach((shift, i) => {
      if (y > pageH - 20) {
        doc.addPage(); y = 20
        y = addTableHeader(doc, y, COLS, contentW, marginL, branding)
        doc.setTextColor(0, 0, 0); doc.setFont('helvetica', 'normal'); doc.setFontSize(9)
      }
      if (i % 2 === 0) { doc.setFillColor(235, 245, 255); doc.rect(marginL, y - 6, contentW, ROW_H, 'F') }
      const s = new Date(shift.start), e = new Date(shift.end)
      const dayStr = `${rptDay(s.getDay())} ${String(s.getDate()).padStart(2,'0')} ${rptMonthS(s.getMonth())}`
      doc.text(dayStr,           COLS[0].x, y)
      doc.text((shift.location_desc ?? '').substring(0, 42), COLS[1].x, y)
      doc.text(formatTime(s),    COLS[2].x, y)
      doc.text(formatTime(e),    COLS[3].x, y)
      y += ROW_H
    })

    doc.setDrawColor(180, 180, 180); doc.setLineWidth(0.3)
    doc.line(marginL, y, pageW - marginR, y); y += 7
    doc.setFont('helvetica', 'bold'); doc.setFontSize(10)
    doc.text(`Totale turni: ${shifts.length}`, marginL, y)
    const mins = shifts.reduce((a, s) => a + Math.round((new Date(s.end).getTime() - new Date(s.start).getTime()) / 60000), 0)
    doc.text(`Ore totali: ${Math.floor(mins/60)}h ${String(mins%60).padStart(2,'0')}min`, marginL + 60, y)
  }

  addFooters(doc, branding)
  const label = safeFilePart(branding?.filename_shifts || 'Turni', 'Turni')
  const company = safeFilePart(branding?.structure_name || 'Azienda', 'Azienda')
  const safeName = safeFilePart(employee.fullName || 'Impiegato', 'Impiegato')
  const filename = `${label}_${company}_${safeName}_${monthValue}.pdf`
  return { blob: doc.output('blob') as Blob, filename }
}

// ─── Location coverage report ────────────────────────────────────────────────
/**
 * @brief Location coverage report, LANDSCAPE orientation (notice-board printout).
 * @details Uncovered shifts (without an employee) are highlighted in red and counted
 *          in the summary: they are the first information needed by shift planners.
 */
export function generateCoveragePdf(
  location: ScheduleData['locations'][0],
  shifts: Shift[],
  monthLabel: string,
  monthValue: string,
  branding?: PdfBranding,
) {
  const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })
  const pageW = doc.internal.pageSize.getWidth()
  const pageH = doc.internal.pageSize.getHeight()
  const marginL = 15, marginR = 15
  const contentW = pageW - marginL - marginR

  addHeader(doc, 'Copertura Turni', location.name, monthLabel, branding)

  let y = 52
  if (shifts.length === 0) {
    doc.setFontSize(12); doc.setTextColor(150, 0, 0)
    doc.text('Nessun turno assegnato per questo periodo.', pageW / 2, y, { align: 'center' })
  } else {
    const COLS = [
      { x: marginL, label: 'Giorno' },
      { x: 75,      label: 'Impiegato' },
      { x: 215,     label: 'Inizio' },
      { x: 250,     label: 'Fine' },
    ]
    const ROW_H = 9
    y = addTableHeader(doc, y, COLS, contentW, marginL, branding)

    doc.setTextColor(0, 0, 0); doc.setFont('helvetica', 'normal'); doc.setFontSize(9)

    shifts.forEach((shift, i) => {
      if (y > pageH - 20) {
        doc.addPage(); y = 20
        y = addTableHeader(doc, y, COLS, contentW, marginL, branding)
        doc.setTextColor(0, 0, 0); doc.setFont('helvetica', 'normal'); doc.setFontSize(9)
      }
      const uncovered = !shift.employee
      if (uncovered) { doc.setFillColor(252, 224, 224); doc.rect(marginL, y - 6, contentW, ROW_H, 'F') }
      else if (i % 2 === 0) { doc.setFillColor(235, 245, 255); doc.rect(marginL, y - 6, contentW, ROW_H, 'F') }
      const s = new Date(shift.start), e = new Date(shift.end)
      const dayStr = `${rptDay(s.getDay())} ${String(s.getDate()).padStart(2,'0')} ${rptMonthS(s.getMonth())}`
      doc.text(dayStr, COLS[0].x, y)
      if (uncovered) {
        doc.setTextColor(180, 0, 0); doc.setFont('helvetica', 'bold')
        doc.text('— SCOPERTO —', COLS[1].x, y)
        doc.setTextColor(0, 0, 0); doc.setFont('helvetica', 'normal')
      } else {
        doc.text((shift.employee?.fullName ?? '').substring(0, 60), COLS[1].x, y)
      }
      doc.text(formatTime(s), COLS[2].x, y)
      doc.text(formatTime(e), COLS[3].x, y)
      y += ROW_H
    })

    doc.setDrawColor(180, 180, 180); doc.setLineWidth(0.3)
    doc.line(marginL, y, pageW - marginR, y); y += 7
    doc.setFont('helvetica', 'bold'); doc.setFontSize(10)
    doc.text(`Totale turni: ${shifts.length}`, marginL, y)
    const mins = shifts.reduce((a, s) => a + Math.round((new Date(s.end).getTime() - new Date(s.start).getTime()) / 60000), 0)
    doc.text(`Ore totali: ${Math.floor(mins/60)}h ${String(mins%60).padStart(2,'0')}min`, marginL + 70, y)
    const uncoveredCount = shifts.filter(s => !s.employee).length
    if (uncoveredCount > 0) {
      doc.setTextColor(180, 0, 0)
      doc.text(`Turni scoperti: ${uncoveredCount}`, marginL + 140, y)
      doc.setTextColor(0, 0, 0)
    }
  }

  addFooters(doc, branding)
  const label = safeFilePart(branding?.filename_coverage || 'Copertura', 'Copertura')
  const company = safeFilePart(branding?.structure_name || 'Azienda', 'Azienda')
  const safeLoc = safeFilePart(location.name || 'Sede', 'Sede')
  const filename = `${label}_${company}_${safeLoc}_${monthValue}.pdf`
  return { blob: doc.output('blob') as Blob, filename }
}
