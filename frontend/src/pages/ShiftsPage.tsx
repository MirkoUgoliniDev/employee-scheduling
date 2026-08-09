/**
 * @file ShiftsPage.tsx
 * @brief Main shift-management page with a vis-timeline timeline.
 *
 * @details
 * ## Layout
 * The page is organized into two tabs:
 * - **Operators** — timeline with one row per employee; colored background bands
 *   indicate availability ranges (green/orange/red)
 * - **Locations** — timeline with one row per location
 *
 * ## Features
 * - Click a shift → ShiftModal in edit mode
 * - Click an empty area → ShiftModal in add mode (prepopulated date/time)
 * - Right click → ContextMenu with contextual actions
 * - Click group-label icons (left column) → related modals
 * - Solve button → starts the Timefold solver with a blocking overlay
 * - When solving finishes → SolveResultModal with constraint analysis
 *
 * ## Integrazione Timefold Solver
 * 1. `handleSolve()` chiama `shiftsApi.solve()` → ottiene `jobId`
 * 2. Poll `shiftsApi.getJob(jobId)` every 2s until `solverStatus !== "SOLVING_ACTIVE"`
 * 3. `handleStopSolve()` calls `shiftsApi.stopJob()` and clears polling
 * 4. On completion: `shiftsApi.analyze()` → opens `SolveResultModal`
 *
 * ## Left column (group HTML)
 * vis-timeline groups use injected HTML to display clickable icons.
 * Icon clicks are intercepted through event delegation on the container
 * (`data-action` and `data-id` attributes).
 */

import { useEffect, useState, useCallback, useMemo, useRef } from 'react'
import { Spinner, Button, Modal, Form, ListGroup } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faRotate, faPlay, faStop, faLocationCrosshairs, faCalendarPlus, faChevronLeft, faChevronRight, faUserGroup, faLocationDot, faFloppyDisk } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import i18n from '../i18n'
import { shiftsApi, getShiftColor, type ScheduleData } from '../api/shifts'
import { errorCode } from '../api/client'
import { templatesApi, type SavedTemplate } from '../api/templates'
import { useAppStore } from '../store/useAppStore'
import emptySchedule from '../assets/empty-schedule.svg'
import ShiftModal from '../components/shifts/ShiftModal'
import EmployeeDatesModal from '../components/employees/EmployeeDatesModal'
import LocationShiftsModal from '../components/locations/LocationShiftsModal'
import ConfirmModal from '../components/ConfirmModal'
import VisTimeline, { type TimelineItem, type TimelineGroup } from '../components/shifts/VisTimeline'
import { SAFE_TIMELINE_XSS } from '../components/shifts/timelineXss'
import ContextMenu, { type ContextMenuAction } from '../components/ContextMenu'
import SolveResultModal from '../components/shifts/SolveResultModal'
import type { ScoreAnalysis } from '../api/shifts'
import './ShiftsPage.css'

// ─── Availability colors ─────────────────────────────────────────────────────
const AVAIL_COLORS: Record<string, string> = {
  desired:     'rgba(0,200,0,0.18)',
  undesired:   'rgba(255,165,0,0.22)',
  unavailable: 'rgba(220,0,0,0.22)',
}

function escHtml(s: string): string {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')
}

/** @brief Translation function (key, fallback) → localized string. */
type TFn = (key: string, fallback: string) => string

// ─── Operator left-column HTML ───────────────────────────────────────────────
// UX: calendar icon only (date management). Editing/deletion is done
// from the Employees page.
function employeeGroupHtml(emp: ScheduleData['employees'][0], hasDates: boolean, t: TFn): string {
  const calColor = hasDates ? '#006400' : '#aaaaaa'
  const calTitle = hasDates
    ? t('tooltip.employeeDatesWithData', 'Gestione date (con date assegnate)')
    : t('tooltip.employeeDatesEmpty', 'Gestione date (nessuna data)')
  const id = emp.id
  return `
    <table style="width:240px;border-collapse:collapse;border:none;padding:2px 0">
      <tr>
        <td style="width:100%;vertical-align:middle;border:none;overflow-wrap:anywhere;font-size:0.88rem;font-weight:500">
          ${escHtml(emp.fullName ?? '')}
        </td>
        <td style="white-space:nowrap;text-align:right;vertical-align:middle;border:none;padding-left:4px">
          <i class="fas fa-calendar-alt"
             data-action="dates-emp" data-id="${id}"
             style="cursor:pointer;color:${calColor};font-size:0.85rem"
             title="${escHtml(calTitle)}"></i>
        </td>
      </tr>
    </table>`
}

// ─── Location left-column HTML ───────────────────────────────────────────────
// UX: calendar icon only (location-shift management). Editing/deletion is done
// from the Locations page.
function locationGroupHtml(loc: ScheduleData['locations'][0], hasShifts: boolean, t: TFn): string {
  const calColor = hasShifts ? '#006400' : '#aaaaaa'
  const calTitle = hasShifts
    ? t('tooltip.locationShiftsWithData', 'Gestione turni (con turni)')
    : t('tooltip.locationShiftsEmpty', 'Gestione turni (nessun turno)')
  const id = loc.id
  return `
    <table style="width:240px;border-collapse:collapse;border:none;padding:2px 0">
      <tr>
        <td style="width:100%;vertical-align:middle;border:none;overflow-wrap:anywhere;font-size:0.88rem;font-weight:500">
          ${escHtml(loc.id ? i18n.t('location.' + loc.id, loc.name ?? '') : (loc.name ?? ''))}
        </td>
        <td style="white-space:nowrap;text-align:right;vertical-align:middle;border:none;padding-left:4px">
          <i class="fas fa-calendar-alt"
             data-action="dates-loc" data-id="${id}"
             style="cursor:pointer;color:${calColor};font-size:0.85rem"
             title="${escHtml(calTitle)}"></i>
        </td>
      </tr>
    </table>`
}

/** Localized skill name for DISPLAY (never for comparisons, which keep using the base name). */
function skillLabel(s: { id?: number; name?: string }): string {
  return s.id ? i18n.t('skill.' + s.id, s.name ?? '') : (s.name ?? '')
}

function skillBadges(skills: { id?: number; name?: string }[]): string {
  return skills.map(s =>
    `<span class="badge me-1" style="background-color:#4a90d9;font-size:0.7rem">${escHtml(skillLabel(s))}</span>`
  ).join('')
}

// ─── Professional tooltip (card) ──────────────────────────────────────────────
// vis-timeline renders an item's `title` field as innerHTML inside `.vis-tooltip`.
// We make that container transparent (VisTimeline.css) and draw a dark card with
// an accent bar, icon, title, time, and skills.

/** "HH:mm" time from a local Date. */
function fmtHm(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

// Inline SVG icons (currentColor). FontAwesome is SVG-per-component here: `fa-*`
// classes have no effect in raw tooltip HTML, so use SVG directly.
const ICON_USER = '<svg viewBox="0 0 448 512" width="11" height="11" fill="currentColor" style="vertical-align:-1px"><path d="M224 256A128 128 0 1 0 224 0a128 128 0 1 0 0 256zm-45.7 48C79.8 304 0 383.8 0 482.3 0 498.7 13.3 512 29.7 512l388.6 0c16.4 0 29.7-13.3 29.7-29.7C448 383.8 368.2 304 269.7 304l-91.4 0z"/></svg>'
const ICON_PIN = '<svg viewBox="0 0 384 512" width="11" height="11" fill="currentColor" style="vertical-align:-1px"><path d="M215.7 499.2C267 435 384 279.4 384 192 384 86 298 0 192 0S0 86 0 192c0 87.4 117 243 168.3 307.2 12.3 15.3 35.1 15.3 47.4 0zM192 128a64 64 0 1 1 0 128 64 64 0 1 1 0-128z"/></svg>'

/** Pill-shaped skill badge for the tooltip card (light on a dark background). */
function tipSkillBadges(skills: { id?: number; name?: string }[]): string {
  return skills.map(s => `<span class="tl-tip-badge">${escHtml(skillLabel(s))}</span>`).join('')
}

/**
 * Tooltip card. `title`/`subtitle` are escaped here; `badgesHtml` is already safe HTML.
 */
function tipCard(opts: { accent: string; icon: string; title: string; subtitle?: string; badgesHtml?: string }): string {
  return `<div class="tl-tip" style="border-left-color:${opts.accent}">`
    + `<div class="tl-tip-head"><span class="tl-tip-ico" style="color:${opts.accent}">${opts.icon}</span>`
    + `<span class="tl-tip-title">${escHtml(opts.title)}</span></div>`
    + (opts.subtitle ? `<div class="tl-tip-sub">${escHtml(opts.subtitle)}</div>` : '')
    + (opts.badgesHtml ? `<div class="tl-tip-badges">${opts.badgesHtml}</div>` : '')
    + `</div>`
}

// ─── Data for the "by operator" tab ───────────────────────────────────────────
function buildEmployeeData(schedule: ScheduleData, t: TFn) {
  const groups: TimelineGroup[] = schedule.employees.map(emp => {
    const hasDates = [emp.unavailableDates, emp.undesiredDates, emp.desiredDates]
      .some(arr => (arr ?? []).some(d => d.dateStart && d.dateEnd))
    return { id: emp.id, content: employeeGroupHtml(emp, hasDates, t) }
  })

  const items: TimelineItem[] = []

  schedule.shifts.forEach((shift, i) => {
    if (!shift.start || !shift.end || !shift.employee) return
    const color = getShiftColor(shift, schedule.employees)
    const reqSkills = (shift.requiredSkills ?? []).filter(s => s.used)
    items.push({
      id: `emp-shift-${i}`,
      group: shift.employee.id,
      content: `<div style="padding:2px 5px;font-size:0.82em;line-height:1.4">
        <strong>${escHtml(shift.location_id ? i18n.t('location.' + shift.location_id, shift.location_desc ?? '') : (shift.location_desc ?? ''))}</strong>
        <div>${skillBadges(reqSkills)}</div>
      </div>`,
      start: new Date(shift.start),
      end: new Date(shift.end),
      style: `background-color:${color};border-color:${color};border-radius:4px`,
      // Tooltip card in By operator view: the row already identifies the operator → show the location.
      title: tipCard({
        accent: color,
        icon: ICON_PIN,
        title: shift.location_id ? i18n.t('location.' + shift.location_id, shift.location_desc ?? '') : (shift.location_desc ?? ''),
        subtitle: `${fmtHm(new Date(shift.start))} – ${fmtHm(new Date(shift.end))}`,
        badgesHtml: reqSkills.length > 0 ? tipSkillBadges(reqSkills) : undefined,
      }),
      editable: false,
    })
  })

  // Availability ranges as backgrounds
  schedule.employees.forEach(emp => {
    const allDates = [
      { arr: emp.unavailableDates ?? [], type: 'unavailable' },
      { arr: emp.undesiredDates ?? [],   type: 'undesired' },
      { arr: emp.desiredDates ?? [],     type: 'desired' },
    ]
    allDates.forEach(({ arr, type }) => {
      arr.forEach((d, di) => {
        if (!d.dateStart || !d.dateEnd) return
        items.push({
          id: `avail-${type}-${emp.id}-${di}`,
          group: emp.id,
          start: new Date(d.dateStart),
          end: new Date(d.dateEnd),
          content: '',
          type: 'background',
          style: `background-color:${AVAIL_COLORS[type]}`,
        })
      })
    })
  })

  return { groups, items }
}

// ─── Data for the "by location" tab ───────────────────────────────────────────
function buildLocationData(schedule: ScheduleData, t: TFn) {
  const locationsWithShifts = new Set(schedule.shifts.map(s => s.location_id))
  const groups: TimelineGroup[] = schedule.locations.map(loc => {
    const hasShifts = locationsWithShifts.has(loc.id)
    return { id: loc.id, content: locationGroupHtml(loc, hasShifts, t) }
  })

  const items: TimelineItem[] = schedule.shifts
    .filter(s => s.start && s.end)
    .map<TimelineItem>((shift, i) => {
      const reqSkills = (shift.requiredSkills ?? []).filter(s => s.used)
      if (shift.employee == null) {
        return {
          id: `loc-shift-${i}`,
          group: shift.location_id,
          content: reqSkills.length > 0
            ? `<div style="padding:2px 5px"><span class="badge" style="background-color:#aaa;font-size:0.7rem">${escHtml(reqSkills.map(s => skillLabel(s)).join(', '))}</span></div>`
            : '',
          start: new Date(shift.start),
          end: new Date(shift.end),
          style: 'background-color:#f1948a;border-color:#e74c3c;border-radius:4px',
          // Tooltip card: unassigned shift → show status + required skills.
          title: tipCard({
            accent: '#e74c3c',
            icon: ICON_USER,
            title: i18n.t('msg.unassigned', 'Non assegnato'),
            subtitle: `${fmtHm(new Date(shift.start))} – ${fmtHm(new Date(shift.end))}`,
            badgesHtml: reqSkills.length > 0 ? tipSkillBadges(reqSkills) : undefined,
          }),
          editable: false,
        }
      }
      // Owned skills = only those with the `used` flag (the full catalog includes everything);
      // compare by ID because names may be localized.
      const empSkillIds = new Set((shift.employee.skills ?? []).filter(s => s.used).map(s => s.id))
      const hasSkills = reqSkills.length === 0 || reqSkills.every(rs => empSkillIds.has(rs.id))
      const skillColor = hasSkills ? '#5cb85c' : '#d9534f'
      return {
        id: `loc-shift-${i}`,
        group: shift.location_id,
        content: `<div style="padding:2px 5px;font-size:0.82em;line-height:1.4">
          <strong>${escHtml(shift.employee.fullName ?? '')}</strong>
          <div><span class="badge" style="background-color:${skillColor};font-size:0.7rem">${escHtml(reqSkills.map(s => skillLabel(s)).join(', '))}</span></div>
        </div>`,
        start: new Date(shift.start),
        end: new Date(shift.end),
        style: 'background-color:#a9dfbf;border-color:#27ae60;border-radius:4px',
        // Tooltip card in By location view: the row already identifies the location → show the full name;
        // use a green/red accent depending on whether the operator has the required skills.
        title: tipCard({
          accent: skillColor,
          icon: ICON_USER,
          title: shift.employee.fullName ?? '',
          subtitle: `${fmtHm(new Date(shift.start))} – ${fmtHm(new Date(shift.end))}`,
          badgesHtml: reqSkills.length > 0 ? tipSkillBadges(reqSkills) : undefined,
        }),
        editable: false,
      }
    })

  return { groups, items }
}

/** Formats a local Date as "yyyy-MM-dd HH:mm:ss" (format expected by the backend). */
function toDbString(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

type WindowMode = 'week' | 'month'

/** Monday 00:00 of the week containing `d` (ISO, consistent with the Italian locale). */
function startOfWeekMonday(d: Date): Date {
  const diff = (d.getDay() + 6) % 7 // getDay: 0=Sun..6=Sat → 0=Mon..6=Sun
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - diff)
}

/** Normalizes a date to the start of its period (Monday for week, first day for month). Idempotent. */
function normalizeViewStart(d: Date, mode: WindowMode): Date {
  return mode === 'week'
    ? startOfWeekMonday(d)
    : new Date(d.getFullYear(), d.getMonth(), 1)
}

/** Right-exclusive [start, end) window for the selected granularity. */
function windowFor(viewStart: Date, mode: WindowMode): { start: Date; end: Date } {
  const start = normalizeViewStart(viewStart, mode)
  const end = mode === 'week'
    ? new Date(start.getFullYear(), start.getMonth(), start.getDate() + 7)
    : new Date(start.getFullYear(), start.getMonth() + 1, 1)
  return { start, end }
}

/** Clamps a date to the [min, max] range (already normalized); null = no bound on that side. */
function clampPeriod(d: Date, mode: WindowMode, min: Date | null, max: Date | null): Date {
  let v = normalizeViewStart(d, mode)
  if (min && v.getTime() < min.getTime()) v = min
  if (max && v.getTime() > max.getTime()) v = max
  return v
}

function shiftIdFromItemId(itemId: string | number, schedule: ScheduleData): number | null {
  const m = String(itemId).match(/shift-(\d+)$/)
  if (!m) return null
  return schedule.shifts[parseInt(m[1])]?.id ?? null
}

// ─── Main component ───────────────────────────────────────────────────────────

type CtxMenu = { x: number; y: number; actions: ContextMenuAction[] }

const TL_OPTIONS_BASE = {
  groupOrder: 'content',
  selectable: false,
  moveable: true,
  zoomable: true,
  zoomMin: 1 * 24 * 60 * 60 * 1000,
  zoomMax: 367 * 24 * 60 * 60 * 1000,
  orientation: { axis: 'top' as const },
  showCurrentTime: true,
  stack: true,
  xss: SAFE_TIMELINE_XSS,
}

export default function ShiftsPage() {
  const { t } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const mode = useAppStore(s => s.shiftWindowMode)
  const autoPopulate = useAppStore(s => s.autoPopulateFromTemplate)
  const language = useAppStore(s => s.language)
  const [schedule, setSchedule] = useState<ScheduleData | null>(null)
  const [loading, setLoading] = useState(false)
  const [activeTab, setActiveTab] = useState<'employee' | 'location'>('employee')
  const [tabSwitching, setTabSwitching] = useState(false)

  // ShiftModal
  const [shiftModalOpen, setShiftModalOpen] = useState(false)
  const [editShiftId, setEditShiftId] = useState<number | null>(null)
  const [prefillStart, setPrefillStart] = useState<string | undefined>()
  const [prefillLocationId, setPrefillLocationId] = useState<number | undefined>()

  // EmployeeDatesModal
  const [datesModalEmpId, setDatesModalEmpId] = useState<number | null>(null)
  const [datesModalEmpName, setDatesModalEmpName] = useState('')
  const [datesModalOpen, setDatesModalOpen] = useState(false)

  // LocationShiftsModal
  const [locShiftsModalId, setLocShiftsModalId] = useState<number | null>(null)
  const [locShiftsModalName, setLocShiftsModalName] = useState('')
  const [locShiftsModalOpen, setLocShiftsModalOpen] = useState(false)

  // ConfirmModal (delete)
  const [confirm, setConfirm] = useState<{ message: string; onConfirm: () => void } | null>(null)

  // "Save as template" modal (new-template description)
  const [saveTplOpen, setSaveTplOpen] = useState(false)
  const [saveTplDesc, setSaveTplDesc] = useState('')
  const [saveTplBusy, setSaveTplBusy] = useState(false)

  // "Load from template" modal (list of saved templates to choose from)
  const [loadTplOpen, setLoadTplOpen] = useState(false)
  const [savedTemplates, setSavedTemplates] = useState<SavedTemplate[]>([])
  const [loadTplBusy, setLoadTplBusy] = useState(false)
  const [applyingTplId, setApplyingTplId] = useState<number | null>(null)

  // Context menu
  const [ctxMenu, setCtxMenu] = useState<CtxMenu | null>(null)

  // Period navigation (week/month)
  const [viewStart, setViewStart] = useState<Date | null>(null)
  // Structure data range (minimum/maximum shift date), used for arrow constraints
  const [dataRange, setDataRange] = useState<{ min: Date | null; max: Date | null }>({ min: null, max: null })
  // Race prevention: identifies the latest fetch and discards older responses
  const reqIdRef = useRef(0)
  // Auto-population: periods already attempted ("structureId:periodStart") — one attempt per
  // period to prevent loops (even if the template is empty or application fails).
  const autoPopulatedRef = useRef<Set<string>>(new Set())
  // Auto-population in progress: prevents overlap between fetching and template application.
  const autoPopulatingRef = useRef(false)

  // Navigation bounds (normalized period starts):
  // - periodMin = period of the first shift (backward navigation limited by history).
  // - periodMax = null: no forward limit, so the head nurse can prepare shifts for
  //   subsequent weeks/months (optionally populating them from a template).
  const { periodMin, periodMax } = useMemo(() => {
    const min = dataRange.min ? normalizeViewStart(dataRange.min, mode) : null
    return { periodMin: min, periodMax: null as Date | null }
  }, [dataRange, mode])

  // Solver
  const [solving, setSolving] = useState(false)
  const solverJobId = useRef<string | null>(null)
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [solveResult, setSolveResult] = useState<ScoreAnalysis | null>(null)
  const [savingAssignments, setSavingAssignments] = useState(false)

  const stopPolling = useCallback(() => {
    if (pollTimer.current) { clearTimeout(pollTimer.current); pollTimer.current = null }
  }, [])

  // WINDOWED loading: download only shifts in the visible period (week/month).
  // Load nothing without a structure or selected period (avoids unnecessary fetches).
  // Depends on viewStart and mode: navigating or changing granularity fetches again.
  // Race guard: if multiple responses arrive, only the latest request wins.
  const load = useCallback(() => {
    if (!structureId || !viewStart) { setSchedule(null); return }
    const { start, end } = windowFor(viewStart, mode)
    const myReq = ++reqIdRef.current
    setLoading(true)
    shiftsApi.schedule(structureId, toDbString(start), toDbString(end), true) // activeOnly: exclude disabled shifts
      .then(data => { if (myReq === reqIdRef.current) setSchedule(data) })
      .catch(() => { if (myReq === reqIdRef.current) toast.error(i18n.t('toast.errorLoadCalendar', 'Errore nel caricamento del calendario.')) })
      .finally(() => { if (myReq === reqIdRef.current) setLoading(false) })
  }, [structureId, viewStart, mode])

  useEffect(() => { load() }, [load])

  // Template auto-population (opt-in through General Settings): when landing on a CURRENT
  // or FUTURE period without shifts, populate it from the location template.
  // - One attempt per period (autoPopulatedRef) → no loop if the template is empty.
  // - Only after fetching completes (!loading) and the current window schedule has zero shifts
  //   → no risk of overwriting real shifts (the window is empty).
  // - Never for past periods: history must not be rewritten.
  useEffect(() => {
    if (!autoPopulate || !structureId || !viewStart) return
    if (loading || solving || autoPopulatingRef.current) return
    if (!schedule || schedule.shifts.length > 0) return

    const { start, end } = windowFor(viewStart, mode)
    const todayPeriod = normalizeViewStart(new Date(), mode)
    if (start.getTime() < todayPeriod.getTime()) return

    const key = `${structureId}:${toDbString(start)}`
    if (autoPopulatedRef.current.has(key)) return
    autoPopulatedRef.current.add(key)
    autoPopulatingRef.current = true

    const capturedStructureId = structureId
    const capturedStart = start
    const capturedEnd = end

    templatesApi.listSaved(capturedStructureId)
      .then(list => {
        if (list.length === 0) return
        return templatesApi.applySaved(list[0].id, capturedStructureId, toDbString(capturedStart), toDbString(capturedEnd))
          .then(res => {
            if (res.created > 0) {
              toast.success(`${t('toast.autoPopulated', 'Periodo popolato automaticamente dal template')}: ${res.created}`)
              load()
            }
          })
      })
      .catch(() => { /* silent: the head nurse can always populate manually */ })
      .finally(() => { autoPopulatingRef.current = false })
  }, [autoPopulate, structureId, viewStart, mode, schedule, loading, solving, load, t])

  // When the structure changes, ask the backend for the first shift date (lightweight endpoint)
  // and move to its month WITHOUT downloading all shifts. If the structure has no shifts,
  // fall back to the current month. This replaces the old viewStart calculation from loaded
  // data (which now includes only the month's shifts, not all shifts).
  useEffect(() => {
    autoPopulatedRef.current.clear()
    setSchedule(null)
    setSolveResult(null)
    if (solving) {
      const jobId = solverJobId.current
      if (jobId) {
        shiftsApi.stopJob(jobId).catch(() => {})
        solverJobId.current = null
      }
      stopPolling()
      setSolving(false)
    }
    if (!structureId) { setViewStart(null); setDataRange({ min: null, max: null }); return }
    let cancelled = false
    shiftsApi.dateRange(structureId)
      .then(r => {
        if (cancelled) return
        const min = r.min ? new Date(r.min.replace(' ', 'T')) : null
        const max = r.max ? new Date(r.max.replace(' ', 'T')) : null
        setDataRange({ min, max })
        setViewStart(normalizeViewStart(min ?? new Date(), mode))
      })
      .catch(() => {
        if (cancelled) return
        setDataRange({ min: null, max: null })
        setViewStart(normalizeViewStart(new Date(), mode))
      })
    return () => { cancelled = true }
  }, [structureId]) // eslint-disable-line react-hooks/exhaustive-deps -- only on structure change; mode is handled by the effect below

  // When granularity changes (week↔month), re-normalize the current period and clamp it
  // to the range again. Depend only on [mode] to prevent fetch loops.
  useEffect(() => {
    setViewStart(prev => {
      if (!prev) return prev
      const next = clampPeriod(prev, mode, periodMin, periodMax)
      return prev.getTime() === next.getTime() ? prev : next
    })
  }, [mode]) // eslint-disable-line react-hooks/exhaustive-deps

  // Clean up polling on unmount
  useEffect(() => stopPolling, [stopPolling])

  async function handleSolve() {
    const solvedStructureId = structureId
    const solvedViewStart = viewStart
    setSolving(true)
    try {
      // The solver operates on the same window as the timeline (visible period).
      const win = solvedViewStart ? windowFor(solvedViewStart, mode) : null
      const jobId = win
        ? await shiftsApi.solve(solvedStructureId, toDbString(win.start), toDbString(win.end))
        : await shiftsApi.solve(solvedStructureId)
      solverJobId.current = jobId
      const poll = async () => {
        try {
          const result = await shiftsApi.getJob(jobId)
          // Structure changed while the solver was in flight: discard the result.
          if (solvedStructureId !== useAppStore.getState().currentStructure?.id) return
          // Stop may occur while the request is in flight: ignore the response
          // and do not restart polling for a job that is already closed.
          if (solverJobId.current !== jobId) return
          // Context shifts (outside the window, pinned for boundary constraints) must
          // neither appear in the timeline nor be saved by handleSaveAssignments.
          setSchedule({ ...result, shifts: result.shifts.filter(s => !s.context) })
          if (result.solverStatus === 'NOT_SOLVING') {
            stopPolling()
            setSolving(false)
            // Constraint analysis
            try {
              const analysis = await shiftsApi.analyze(jobId)
              setSolveResult(analysis)
            } catch {
              toast.success(t('toast.solveCompleted', 'Solve completato!'))
            }
            solverJobId.current = null
          } else {
            // Schedule the next check only after the current response:
            // no overlap if the backend takes more than two seconds.
            pollTimer.current = setTimeout(poll, 2000)
          }
        } catch {
          stopPolling()
          setSolving(false)
          solverJobId.current = null
          toast.error(t('toast.errorSolverPolling', 'Errore durante il polling del solver.'))
        }
      }
      pollTimer.current = setTimeout(poll, 2000)
    } catch {
      setSolving(false)
      toast.error(t('toast.errorSolverStart', "Errore durante l'avvio del solver."))
    }
  }

  /** @brief Persists the displayed solution's assignments (current schedule) to the database. */
  async function handleSaveAssignments() {
    if (!schedule) return
    setSavingAssignments(true)
    const savedStructureId = structureId
    const savedViewStart = viewStart
    // Restrict server-side saving to the displayed window: exclude context shifts
    // from adjacent weeks even if the payload is malformed.
    const win = savedViewStart ? windowFor(savedViewStart, mode) : null
    try {
      await shiftsApi.saveAssignments(
        // `version` travels with each assignment and is the value read by the solver. If a shift
        // was modified in the meantime, the server rejects the entire save operation.
        schedule.shifts.map(s => ({
          shift_id: s.id,
          employee_id: s.employee?.id ?? null,
          version: s.version,
        })),
        savedStructureId,
        win ? toDbString(win.start) : undefined,
        win ? toDbString(win.end) : undefined,
      )
      toast.success(t('toast.assignmentsSaved', 'Assegnazioni salvate!'))
      setSolveResult(null)
      load()
    } catch (err) {
      if (errorCode(err) === 'SHIFTS_CHANGED') {
        toast.error(t('toast.shiftsChanged',
          'I turni sono stati modificati dopo il calcolo: niente è stato salvato. Ricarica e rilancia il calcolo.'))
        setSolveResult(null)
        load()
      } else {
        toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
      }
    } finally {
      setSavingAssignments(false)
    }
  }

  /** @brief Discards the solution: the view returns to the state persisted in the database. */
  function handleDiscardSolution() {
    setSolveResult(null)
    load()
    toast(t('toast.solutionDiscarded', 'Soluzione scartata.'))
  }

  async function handleStopSolve() {
    const jobId = solverJobId.current
    if (!jobId) return
    try {
      await shiftsApi.stopJob(jobId)
    } catch { /* ignore */ }
    stopPolling()
    setSolving(false)
    solverJobId.current = null
    load()
  }

  // Prepare data only for the active view: with large schedules, building both
  // representations simultaneously doubled work and allocations.
  const timelineData = useMemo(() => {
    if (!schedule) return { groups: [], items: [] }
    return activeTab === 'employee'
      ? buildEmployeeData(schedule, t)
      : buildLocationData(schedule, t)
  }, [schedule, activeTab, t])

  // Timeline window = period (week/month) of the selected date.
  const tlWindow = useMemo(() => {
    const w = windowFor(viewStart ?? new Date(), mode)
    return { start: w.start, end: new Date(w.end.getTime() - 1) } // exclusive → inclusive
  }, [viewStart, mode])

  const tlOptions = useMemo(() => ({ ...TL_OPTIONS_BASE, start: tlWindow.start, end: tlWindow.end }), [tlWindow])

  const MONTHS = [
    t('month.january','Gennaio'), t('month.february','Febbraio'), t('month.march','Marzo'),
    t('month.april','Aprile'), t('month.may','Maggio'), t('month.june','Giugno'),
    t('month.july','Luglio'), t('month.august','Agosto'), t('month.september','Settembre'),
    t('month.october','Ottobre'), t('month.november','Novembre'), t('month.december','Dicembre'),
  ]
  const MONTHS_SHORT = [
    t('monthShort.jan','Gen'), t('monthShort.feb','Feb'), t('monthShort.mar','Mar'),
    t('monthShort.apr','Apr'), t('monthShort.may','Mag'), t('monthShort.jun','Giu'),
    t('monthShort.jul','Lug'), t('monthShort.aug','Ago'), t('monthShort.sep','Set'),
    t('monthShort.oct','Ott'), t('monthShort.nov','Nov'), t('monthShort.dec','Dic'),
  ]

  // Period label: month ("December 2024") or week ("16–22 Dec 2024").
  const viewLabel = !viewStart ? '' : mode === 'month'
    ? `${MONTHS[viewStart.getMonth()]} ${viewStart.getFullYear()}`
    : (() => {
        const s = startOfWeekMonday(viewStart)
        const e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 6) // inclusive Sunday
        if (s.getMonth() === e.getMonth())
          return `${s.getDate()}–${e.getDate()} ${MONTHS_SHORT[s.getMonth()]} ${s.getFullYear()}`
        if (s.getFullYear() === e.getFullYear())
          return `${s.getDate()} ${MONTHS_SHORT[s.getMonth()]} – ${e.getDate()} ${MONTHS_SHORT[e.getMonth()]} ${e.getFullYear()}`
        return `${s.getDate()} ${MONTHS_SHORT[s.getMonth()]} ${s.getFullYear()} – ${e.getDate()} ${MONTHS_SHORT[e.getMonth()]} ${e.getFullYear()}`
      })()

  // Backward navigation is limited by the first shift; forward navigation is unlimited (preparing future shifts).
  const canPrev = !!viewStart && !!periodMin && viewStart.getTime() > periodMin.getTime()
  const canNext = !!viewStart && (!periodMax || viewStart.getTime() < periodMax.getTime())

  // Navigate by ±1 period (week or month), clamped to the range. Return prev if unchanged
  // (avoids unnecessary fetches when the normalized period is identical).
  // Note: ALWAYS return a new Date (no "if equal, return prev" short circuit).
  // This makes every click reattach the timeline window even when the period does not change,
  // e.g. after manual timeline pan/zoom caused it to drift out of sync with state.
  function navigate(delta: number) {
    setViewStart(prev => {
      const base = normalizeViewStart(prev ?? new Date(), mode)
      const raw = mode === 'week'
        ? new Date(base.getFullYear(), base.getMonth(), base.getDate() + 7 * delta)
        : new Date(base.getFullYear(), base.getMonth() + delta, 1)
      return clampPeriod(raw, mode, periodMin, periodMax)
    })
  }

  const handleTimelineVisible = useCallback(() => setTabSwitching(false), [])

  function changeTab(tab: 'employee' | 'location') {
    if (tab === activeTab) return
    setTabSwitching(true)
    setActiveTab(tab)
  }

  // "Today" button: always moves to the current period (clamped to the navigable range).
  function goToday() {
    setViewStart(clampPeriod(new Date(), mode, periodMin, periodMax))
  }

  // ─── Clicks on group icons (event delegation) ──────────────────────────────
  function handleGroupIconClick(e: React.MouseEvent<HTMLDivElement>) {
    const icon = (e.target as HTMLElement).closest<HTMLElement>('[data-action]')
    if (!icon) return
    e.stopPropagation()

    const action = icon.dataset.action!
    const id = parseInt(icon.dataset.id!)

    // Only the calendar icon appears in groups: operators and locations are edited/deleted
    // from their respective pages.
    switch (action) {
      case 'dates-emp': {
        const emp = schedule?.employees.find(e => e.id === id)
        setDatesModalEmpId(id)
        setDatesModalEmpName(emp?.fullName ?? '')
        setDatesModalOpen(true)
        break
      }
      case 'dates-loc': {
        const loc = schedule?.locations.find(l => l.id === id)
        setLocShiftsModalId(id)
        setLocShiftsModalName(loc?.name ?? '')
        setLocShiftsModalOpen(true)
        break
      }
    }
  }

  // ─── Click on a shift item ─────────────────────────────────────────────────
  function handleItemClick(itemId: string | number) {
    if (!schedule) return
    const shiftId = shiftIdFromItemId(itemId, schedule)
    if (shiftId == null) return
    setEditShiftId(shiftId)
    setPrefillStart(undefined)
    setPrefillLocationId(undefined)
    setShiftModalOpen(true)
  }

  // ─── Click on an empty area ────────────────────────────────────────────────
  function handleCanvasClick(time: Date, groupId: string | number | null) {
    setEditShiftId(null)
    setPrefillStart(time.toISOString())
    setPrefillLocationId(activeTab === 'location' && groupId != null ? Number(groupId) : undefined)
    setShiftModalOpen(true)
  }

  // ─── Context menu ──────────────────────────────────────────────────────────
  function handleContextMenu(
    itemId: string | number | null,
    groupId: string | number | null,
    time: Date,
    x: number, y: number,
  ) {
    if (!schedule) return
    if (itemId != null) {
      const shiftId = shiftIdFromItemId(itemId, schedule)
      if (shiftId == null) return
      setCtxMenu({
        x, y,
        actions: [
          {
            label: t('ctx.editShift', 'Modifica turno'),
            icon: 'fas fa-pencil-alt',
            onClick: () => { setEditShiftId(shiftId); setPrefillStart(undefined); setPrefillLocationId(undefined); setShiftModalOpen(true) },
          },
          {
            label: t('ctx.deleteShift', 'Elimina turno'),
            icon: 'fas fa-trash',
            variant: 'danger',
            onClick: () => setConfirm({
              message: t('confirm.deleteShiftMessage', 'Eliminare questo turno?'),
              onConfirm: async () => {
                try { await shiftsApi.delete(shiftId, structureId); load() }
                catch { toast.error(t('toast.errorDelete', "Errore durante l'eliminazione del turno.")) }
              },
            }),
          },
        ],
      })
    } else {
      setCtxMenu({
        x, y,
        actions: [{
          label: t('ctx.addShift', 'Aggiungi turno'),
          icon: 'fas fa-plus',
          variant: 'primary',
          onClick: () => {
            setEditShiftId(null)
            setPrefillStart(time.toISOString())
            setPrefillLocationId(activeTab === 'location' && groupId != null ? Number(groupId) : undefined)
            setShiftModalOpen(true)
          },
        }],
      })
    }
  }

  // Opens the "Load from template" modal: list of saved templates to choose from.
  async function handleOpenLoadTemplate() {
    if (!structureId) return
    setLoadTplOpen(true)
    setLoadTplBusy(true)
    try {
      setSavedTemplates(await templatesApi.listSaved(structureId))
    } catch {
      toast.error(t('toast.errorLoad', 'Errore nel caricamento.'))
      setSavedTemplates([])
    } finally {
      setLoadTplBusy(false)
    }
  }

  // Applies the selected template to the visible window (REPLACES shifts in the window).
  async function handleApplySavedTemplate(id: number) {
    if (!structureId || !viewStart) return
    const { start, end } = windowFor(viewStart, mode)
    setApplyingTplId(id)
    try {
      const res = await templatesApi.applySaved(id, structureId, toDbString(start), toDbString(end))
      toast.success(`${t('toast.templateApplied', 'Turni creati dal template')}: ${res.created}`)
      setLoadTplOpen(false)
      load()
    } catch {
      toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
    } finally {
      setApplyingTplId(null)
    }
  }

  // Opens the "Save as template" modal to provide a description for the new template.
  function handleOpenSaveTemplate() {
    if (!structureId || !viewStart || mode !== 'week') return
    setSaveTplDesc('')
    setSaveTplOpen(true)
  }

  // Creates a NEW template from the visible week (adds rather than replaces).
  // Saves ONLY shift structure (day/time/location/skills), never assigned operators.
  async function handleConfirmSaveTemplate() {
    if (!structureId || !viewStart) return
    const weekMonday = startOfWeekMonday(viewStart)
    setSaveTplBusy(true)
    try {
      await templatesApi.addSaved(structureId, toDbString(weekMonday), saveTplDesc.trim())
      toast.success(t('toast.savedTemplateAdded', 'Template salvato!'))
      setSaveTplOpen(false)
    } catch {
      toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
    } finally {
      setSaveTplBusy(false)
    }
  }

  // ─── Legenda ───────────────────────────────────────────────────────────────
  const LEGEND = [
    { color: '#aaaaaa', label: t('msg.unassigned', 'Non assegnato') },
    { color: '#729fcf', label: t('msg.assigned', 'Assegnato') },
    { color: '#00cc00', label: t('dateType.desired', 'Desiderato') },
    { color: '#FFA500', label: t('dateType.undesired', 'Indesiderato') },
    { color: '#FF4444', label: t('dateType.unavailable', 'Non disponibile') },
  ]

  return (
    <div>
      {/* Header */}
      <div className="d-flex justify-content-between align-items-center mb-3">
        <div className="d-flex align-items-center gap-3">
          <h5 className="mb-0">{t('nav.shiftManagement', 'Gestione Turni')}</h5>
        </div>
        <div className="d-flex align-items-center gap-3">
          <div className="d-flex gap-2 align-items-center flex-wrap">
            {LEGEND.map(({ color, label }) => (
              <span key={label} className="d-flex align-items-center gap-1 small">
                <span style={{ width: 12, height: 12, borderRadius: 2, background: color, display: 'inline-block', flexShrink: 0 }} />
                {label}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* Period navigation (week / month) */}
      <div className="d-flex align-items-center justify-content-center mb-2 gap-1">
        <Button
          variant="outline-primary"
          size="sm"
          className="d-inline-flex align-items-center justify-content-center"
          style={{ width: 36, height: 32 }}
          onClick={() => navigate(-1)}
          disabled={!canPrev}
          title={t('tooltip.prevPeriod', 'Periodo precedente')}
          aria-label={t('tooltip.prevPeriod', 'Periodo precedente')}
        >
          <FontAwesomeIcon icon={faChevronLeft} />
        </Button>
        <span className="fw-semibold mx-2" style={{ minWidth: 210, textAlign: 'center', fontSize: '1rem' }}>
          {viewLabel}
        </span>
        <Button
          variant="outline-primary"
          size="sm"
          className="d-inline-flex align-items-center justify-content-center"
          style={{ width: 36, height: 32 }}
          onClick={() => navigate(1)}
          disabled={!canNext}
          title={t('tooltip.nextPeriod', 'Periodo successivo')}
          aria-label={t('tooltip.nextPeriod', 'Periodo successivo')}
        >
          <FontAwesomeIcon icon={faChevronRight} />
        </Button>
        <Button
          variant="outline-primary"
          size="sm"
          className="ms-3"
          onClick={goToday}
          title={t('tooltip.today', 'Vai a oggi')}
        >
          <FontAwesomeIcon icon={faLocationCrosshairs} className="me-1" />{t('btn.today', 'Oggi')}
        </Button>
        {activeTab === 'location' && (
          <>
            <Button
              variant="outline-primary" size="sm" className="ms-1"
              onClick={handleOpenLoadTemplate}
              disabled={loading || solving || !schedule}
              title={t('tooltip.applyTemplate', 'Sostituisci i turni del periodo visibile con quelli del template')}
            >
              <FontAwesomeIcon icon={faCalendarPlus} className="me-1" />{t('btn.applyTemplate', 'Popola da template')}
            </Button>
            <Button
              variant="outline-primary" size="sm" className="ms-1"
              onClick={handleOpenSaveTemplate}
              disabled={loading || solving || !schedule || mode !== 'week'}
              title={mode !== 'week'
                ? t('tooltip.saveToTemplateWeekOnly', 'Disponibile solo in vista settimanale')
                : t('tooltip.saveToTemplate', 'Salva i turni della settimana visibile come nuovo template (senza operatori assegnati)')}
            >
              <FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.saveToTemplate', 'Salva in template')}
            </Button>
          </>
        )}
        {solving ? (
          <Button variant="danger" size="sm" className="ms-3" onClick={handleStopSolve}>
            <FontAwesomeIcon icon={faStop} className="me-1" />Stop
          </Button>
        ) : (
          <Button variant="success" size="sm" className="ms-3" onClick={handleSolve} disabled={loading || !schedule}>
            <FontAwesomeIcon icon={faPlay} className="me-1" />Solve
          </Button>
        )}
        <Button variant="outline-secondary" size="sm" className="ms-1" onClick={load} disabled={loading || solving}>
          <FontAwesomeIcon icon={faRotate} spin={loading} />
        </Button>
      </div>

      {/* Tabs */}
      <div className="shift-view-tabs" role="tablist">
        {([['employee', t('tab.byEmployee', 'Per Operatore'), faUserGroup], ['location', t('tab.byLocation', 'Per Sede'), faLocationDot]] as const).map(([key, label, icon]) => {
          const active = activeTab === key
          return (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={active}
              className={`shift-view-tab${active ? ' active' : ''}`}
              onClick={() => changeTab(key)}
            >
              <FontAwesomeIcon icon={icon} className="me-2" />
              {label}
            </button>
          )
        })}
      </div>

      <div className="timeline-stage border border-top-0" style={{ minHeight: 400 }}>
        {tabSwitching && (
          <div className="timeline-loading-overlay" role="status" aria-live="polite">
            <Spinner animation="border" variant="secondary" />
          </div>
        )}
        {loading && !schedule ? (
          <div className="text-center py-5"><Spinner /></div>
        ) : !schedule ? (
          <div className="empty-schedule">
            <img src={emptySchedule} alt="" className="empty-schedule__art" />
            <div className="empty-schedule__text">
              <h5>{t('empty.title', 'Nessun turno pianificato')}</h5>
              <p className="mb-1">{t('empty.body', 'Questa vista si popola appena crei il primo turno.')}</p>
              <p className="text-muted mb-0">{t('empty.hint', 'Scegli una struttura e aggiungi un turno per iniziare.')}</p>
            </div>
          </div>
        ) : timelineData.groups.length === 0 ? (
          <div className="empty-schedule">
            <img src={emptySchedule} alt="" className="empty-schedule__art" />
            <div className="empty-schedule__text">
              <h5>{t('empty.title', 'Nessun turno pianificato')}</h5>
              <p className="mb-1">{t('empty.body', 'Questa vista si popola appena crei il primo turno.')}</p>
              <p className="text-muted mb-0">{t('empty.hint', 'Scegli una struttura e aggiungi un turno per iniziare.')}</p>
            </div>
          </div>
        ) : (
          /* Wrapper with event delegation for group icons */
          <div onClick={handleGroupIconClick}>
            <VisTimeline
              key={`${activeTab}-tl-${language}`}
              locale={language}
              groups={timelineData.groups}
              items={timelineData.items}
              options={tlOptions}
              visible
              onVisibleReady={handleTimelineVisible}
              onItemClick={handleItemClick}
              onCanvasClick={handleCanvasClick}
              onContextMenu={handleContextMenu}
            />
          </div>
        )}
      </div>

      {/* Modals */}
      <ShiftModal
        show={shiftModalOpen}
        shiftId={editShiftId}
        prefillStart={prefillStart}
        prefillLocationId={prefillLocationId}
        structureId={structureId}
        onClose={() => setShiftModalOpen(false)}
        onSaved={load}
        onDeleted={load}
      />

      <LocationShiftsModal
        show={locShiftsModalOpen}
        locationId={locShiftsModalId}
        locationName={locShiftsModalName}
        shifts={schedule?.shifts ?? []}
        structureId={structureId}
        onClose={() => setLocShiftsModalOpen(false)}
        onChanged={() => { load() }}
      />

      <EmployeeDatesModal
        show={datesModalOpen}
        employeeId={datesModalEmpId}
        employeeName={datesModalEmpName}
        structureId={structureId}
        onClose={() => { setDatesModalOpen(false); load() }}
      />

      <ConfirmModal
        show={confirm !== null}
        message={confirm?.message ?? ''}
        onConfirm={() => { confirm?.onConfirm(); setConfirm(null) }}
        onClose={() => setConfirm(null)}
      />

      {/* "Save as template" modal: asks for a description of the new template */}
      <Modal show={saveTplOpen} onHide={() => setSaveTplOpen(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>{t('modal.saveTemplate', 'Salva in template')}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Form.Group>
            <Form.Label>{t('label.description', 'Descrizione')}</Form.Label>
            <Form.Control
              autoFocus
              value={saveTplDesc}
              onChange={e => setSaveTplDesc(e.target.value)}
              placeholder={t('placeholder.templateDescription', 'Descrizione del template…')}
              maxLength={200}
              onKeyDown={e => { if (e.key === 'Enter' && !saveTplBusy) handleConfirmSaveTemplate() }}
            />
          </Form.Group>
          <p className="text-muted small mt-2 mb-0">
            {t('hint.saveTemplateNoOperators', 'Vengono salvati solo i turni per sede, senza gli operatori assegnati.')}
          </p>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setSaveTplOpen(false)} disabled={saveTplBusy}>
            {t('btn.cancel', 'Annulla')}
          </Button>
          <Button variant="primary" onClick={handleConfirmSaveTemplate} disabled={saveTplBusy}>
            {saveTplBusy ? <Spinner size="sm" /> : t('btn.save', 'Salva')}
          </Button>
        </Modal.Footer>
      </Modal>

      {/* "Load from template" modal: list of saved templates to apply */}
      <Modal show={loadTplOpen} onHide={() => setLoadTplOpen(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>{t('modal.loadTemplate', 'Carica da template')}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          {loadTplBusy ? (
            <div className="text-center py-3"><Spinner /></div>
          ) : savedTemplates.length === 0 ? (
            <p className="text-muted mb-0">{t('savedTemplates.empty', 'Nessun template salvato.')}</p>
          ) : (
            <ListGroup>
              {savedTemplates.map(tpl => (
                <ListGroup.Item key={tpl.id} className="d-flex justify-content-between align-items-center gap-2">
                  <div className="flex-grow-1">
                    <div className="fw-semibold">{tpl.description || `#${tpl.id}`}</div>
                    <div className="text-muted small">
                      {tpl.item_count} {t('config.template.shiftsConfigured', 'Turni configurati').toLowerCase()} · {tpl.created_at}
                    </div>
                  </div>
                  <Button
                    variant="outline-primary" size="sm"
                    onClick={() => handleApplySavedTemplate(tpl.id)}
                    disabled={applyingTplId !== null}
                  >
                    {applyingTplId === tpl.id ? <Spinner size="sm" /> : t('btn.apply', 'Applica')}
                  </Button>
                </ListGroup.Item>
              ))}
            </ListGroup>
          )}
          {savedTemplates.length > 0 && (
            <p className="text-muted small mt-2 mb-0">
              {t('hint.loadTemplateReplaces', 'Applicando un template, i turni del periodo visibile verranno sostituiti.')}
            </p>
          )}
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setLoadTplOpen(false)} disabled={applyingTplId !== null}>
            {t('common.close', 'Chiudi')}
          </Button>
        </Modal.Footer>
      </Modal>

      {ctxMenu && (
        <ContextMenu
          x={ctxMenu.x}
          y={ctxMenu.y}
          actions={ctxMenu.actions}
          onClose={() => setCtxMenu(null)}
        />
      )}

      <SolveResultModal
        show={solveResult !== null}
        analysis={solveResult}
        onSave={handleSaveAssignments}
        onDiscard={handleDiscardSolution}
        saving={savingAssignments}
      />

      {/* Solver-in-progress overlay */}
      {solving && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 9999,
          background: 'rgba(0,0,0,0.55)',
          display: 'flex', flexDirection: 'column',
          alignItems: 'center', justifyContent: 'center',
          gap: 20,
        }}>
          <Spinner animation="border" variant="light" style={{ width: 64, height: 64, borderWidth: 6 }} />
          <div style={{ color: '#fff', fontSize: '1.2rem', fontWeight: 500 }}>
            {t('msg.solvingInProgress', 'Solving in corso…')}
          </div>
          <Button variant="danger" onClick={handleStopSolve}>
            <FontAwesomeIcon icon={faStop} className="me-2" />{t('btn.interrupt', 'Interrompi')}
          </Button>
        </div>
      )}
    </div>
  )
}
