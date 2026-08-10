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
 * Day/month labels, and every other user-visible string in the generated PDF, go through
 * the `Translator` passed in by the caller: this module runs outside React (jsPDF draws
 * directly onto a canvas-like document), so there is no `useTranslation()` hook here.
 * `ReportPage.tsx` — the only caller — already has `const { t } = useTranslation()` and
 * simply forwards it; that keeps a single source of truth for "what does `t()` do when a
 * key is missing" (see `i18n/index.ts`: it falls back to the second argument), instead of
 * reimplementing a parallel lookup in this file.
 */

import { jsPDF } from 'jspdf'
import type { Shift, ScheduleData } from '../api/shifts'
import type { PdfTemplate } from '../api/pdfTemplates'

/**
 * @brief Shape of `react-i18next`'s `t()` that this module needs.
 * @details Deliberately narrow (two required positional args) so callers can pass their
 *          `t` directly without adapting it.
 */
export type Translator = (key: string, fallback: string, options?: Record<string, unknown>) => string

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
const DOW_KEYS = ['pdf.dow.sun', 'pdf.dow.mon', 'pdf.dow.tue', 'pdf.dow.wed', 'pdf.dow.thu', 'pdf.dow.fri', 'pdf.dow.sat']
const DAYS_SHORT_IT = ['Dom', 'Lun', 'Mar', 'Mer', 'Gio', 'Ven', 'Sab']

const MONTH_LONG_KEYS = ['month.january', 'month.february', 'month.march', 'month.april', 'month.may', 'month.june',
                          'month.july', 'month.august', 'month.september', 'month.october', 'month.november', 'month.december']
const MONTHS_LONG_IT = ['Gennaio', 'Febbraio', 'Marzo', 'Aprile', 'Maggio', 'Giugno',
                        'Luglio', 'Agosto', 'Settembre', 'Ottobre', 'Novembre', 'Dicembre']

const MONTH_SHORT_KEYS = ['month.jan.s', 'month.feb.s', 'month.mar.s', 'month.apr.s', 'month.may.s', 'month.jun.s',
                            'month.jul.s', 'month.aug.s', 'month.sep.s', 'month.oct.s', 'month.nov.s', 'month.dec.s']
const MONTHS_SHORT_IT = ['Gen', 'Feb', 'Mar', 'Apr', 'Mag', 'Giu', 'Lug', 'Ago', 'Set', 'Ott', 'Nov', 'Dic']

export const rptDay    = (t: Translator, idx: number) => t(DOW_KEYS[idx], DAYS_SHORT_IT[idx])
export const rptMonth  = (t: Translator, idx: number) => t(MONTH_LONG_KEYS[idx], MONTHS_LONG_IT[idx])
export const rptMonthS = (t: Translator, idx: number) => t(MONTH_SHORT_KEYS[idx], MONTHS_SHORT_IT[idx])

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
function addFooters(doc: jsPDF, t: Translator, branding?: PdfBranding) {
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
    const automatic = t('pdf.footer.generated', `Generato il ${dateStr}  –  Pagina ${p} di ${total}`, { date: dateStr, page: p, total })
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
  branding: PdfBranding | undefined,
  t: Translator,
) {
  const doc = new jsPDF({ orientation: 'portrait', unit: 'mm', format: 'a4' })
  const pageW = doc.internal.pageSize.getWidth()
  const pageH = doc.internal.pageSize.getHeight()
  const marginL = 15, marginR = 15
  const contentW = pageW - marginL - marginR

  addHeader(doc, t('pdf.titleMonthlyShifts', 'Report Turni'), employee.fullName, monthLabel, branding)

  let y = 52
  if (shifts.length === 0) {
    doc.setFontSize(12); doc.setTextColor(150, 0, 0)
    doc.text(t('pdf.noShiftsPeriod', 'Nessun turno assegnato per questo periodo.'), pageW / 2, y, { align: 'center' })
  } else {
    const COLS = [
      { x: marginL, label: t('pdf.col.day', 'Giorno') },
      { x: 65,      label: t('pdf.col.location', 'Sede') },
      { x: 145,     label: t('pdf.col.start', 'Inizio') },
      { x: 170,     label: t('pdf.col.end', 'Fine') },
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
      const dayStr = `${rptDay(t, s.getDay())} ${String(s.getDate()).padStart(2,'0')} ${rptMonthS(t, s.getMonth())}`
      doc.text(dayStr,           COLS[0].x, y)
      doc.text((shift.location_desc ?? '').substring(0, 42), COLS[1].x, y)
      doc.text(formatTime(s),    COLS[2].x, y)
      doc.text(formatTime(e),    COLS[3].x, y)
      y += ROW_H
    })

    doc.setDrawColor(180, 180, 180); doc.setLineWidth(0.3)
    doc.line(marginL, y, pageW - marginR, y); y += 7
    doc.setFont('helvetica', 'bold'); doc.setFontSize(10)
    doc.text(`${t('pdf.totalShifts', 'Totale turni')}: ${shifts.length}`, marginL, y)
    const mins = shifts.reduce((a, s) => a + Math.round((new Date(s.end).getTime() - new Date(s.start).getTime()) / 60000), 0)
    doc.text(`${t('pdf.totalHours', 'Ore totali')}: ${Math.floor(mins/60)}h ${String(mins%60).padStart(2,'0')}min`, marginL + 60, y)
  }

  addFooters(doc, t, branding)
  const label = safeFilePart(branding?.filename_shifts || t('pdf.filenameShifts', 'Turni'), 'Turni')
  const company = safeFilePart(branding?.structure_name || t('pdf.filenameCompany', 'Azienda'), 'Azienda')
  const safeName = safeFilePart(employee.fullName || t('pdf.filenameEmployee', 'Operatore'), 'Operatore')
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
  branding: PdfBranding | undefined,
  t: Translator,
) {
  const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })
  const pageW = doc.internal.pageSize.getWidth()
  const pageH = doc.internal.pageSize.getHeight()
  const marginL = 15, marginR = 15
  const contentW = pageW - marginL - marginR

  addHeader(doc, t('pdf.titleCoverage', 'Copertura Turni'), location.name, monthLabel, branding)

  let y = 52
  if (shifts.length === 0) {
    doc.setFontSize(12); doc.setTextColor(150, 0, 0)
    doc.text(t('pdf.noShiftsPeriod', 'Nessun turno assegnato per questo periodo.'), pageW / 2, y, { align: 'center' })
  } else {
    const COLS = [
      { x: marginL, label: t('pdf.col.day', 'Giorno') },
      { x: 75,      label: t('pdf.col.employee', 'Operatore') },
      { x: 215,     label: t('pdf.col.start', 'Inizio') },
      { x: 250,     label: t('pdf.col.end', 'Fine') },
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
      const dayStr = `${rptDay(t, s.getDay())} ${String(s.getDate()).padStart(2,'0')} ${rptMonthS(t, s.getMonth())}`
      doc.text(dayStr, COLS[0].x, y)
      if (uncovered) {
        doc.setTextColor(180, 0, 0); doc.setFont('helvetica', 'bold')
        doc.text(t('pdf.uncovered', '— SCOPERTO —'), COLS[1].x, y)
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
    doc.text(`${t('pdf.totalShifts', 'Totale turni')}: ${shifts.length}`, marginL, y)
    const mins = shifts.reduce((a, s) => a + Math.round((new Date(s.end).getTime() - new Date(s.start).getTime()) / 60000), 0)
    doc.text(`${t('pdf.totalHours', 'Ore totali')}: ${Math.floor(mins/60)}h ${String(mins%60).padStart(2,'0')}min`, marginL + 70, y)
    const uncoveredCount = shifts.filter(s => !s.employee).length
    if (uncoveredCount > 0) {
      doc.setTextColor(180, 0, 0)
      doc.text(`${t('pdf.uncoveredCount', 'Turni scoperti')}: ${uncoveredCount}`, marginL + 140, y)
      doc.setTextColor(0, 0, 0)
    }
  }

  addFooters(doc, t, branding)
  const label = safeFilePart(branding?.filename_coverage || t('pdf.filenameCoverage', 'Copertura'), 'Copertura')
  const company = safeFilePart(branding?.structure_name || t('pdf.filenameCompany', 'Azienda'), 'Azienda')
  const safeLoc = safeFilePart(location.name || t('pdf.filenameLocation', 'Sede'), 'Sede')
  const filename = `${label}_${company}_${safeLoc}_${monthValue}.pdf`
  return { blob: doc.output('blob') as Blob, filename }
}
