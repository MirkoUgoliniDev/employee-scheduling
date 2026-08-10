/**
 * @file ReportPage.tsx
 * @brief Report page — two sections for two distinct tasks (sidebar on the left).
 *
 * @details
 * - **Location Coverage** (`?section=coverage`, default) — for shift planners:
 *   select one/all locations + period → one LANDSCAPE coverage PDF per location
 *   (suitable for printing/posting), with uncovered shifts highlighted and counted.
 * - **Send Shifts** (`?section=send`) — period only: one personal PDF per employee
 *   with shifts in the period (all locations) + individual/bulk email sending
 *   with persistent "Sent on ..." history.
 *
 * The Period navigator (‹ › + Today) follows the configured granularity (`shiftWindowMode`).
 * "Generate PDF" creates blobs in the background (click filename = open in browser,
 * icon = download); the same blobs are reused as email attachments.
 * Data is loaded once from `GET /demo-data/generate` (all shifts) and filtered client-side
 * by period. PDF generation lives in `utils/pdfHelpers.ts`.
 */

import { useEffect, useMemo, useRef, useState, useCallback } from 'react'
import { Form, Button, Table, Alert, Spinner, Row, Col, OverlayTrigger, Popover, Tooltip } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFilePdf, faLocationCrosshairs, faChevronLeft, faChevronRight, faDownload, faBuilding, faUser, faUserNurse, faEnvelope, faCheck, faTriangleExclamation, faSlash } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { shiftsApi, type ScheduleData, type Shift } from '../api/shifts'
import { emailApi, blobToBase64 } from '../api/email'
import { useAppStore } from '../store/useAppStore'
import { generateEmployeePdf, generateCoveragePdf } from '../utils/pdfHelpers'
import { pdfTemplatesApi, EMPTY_PDF_TEMPLATE } from '../api/pdfTemplates'
import { normalizeViewStart, windowFor, addPeriods, periodSlug, periodLabel } from '../utils/period'
import ShiftDaysCalendar from '../components/shifts/ShiftDaysCalendar'
import './ConfigPage.css'

/** @brief Active section: coverage for planners, sending for communication. */
type ReportSection = 'coverage' | 'send'

/** @brief Results-table row: a generated PDF (employee or location coverage). */
interface PdfRow {
  key: string
  /** @brief true = location-coverage row, false = employee row. */
  coverage: boolean
  /** @brief Employee ID (employee rows only, email recipient). */
  employeeId?: number
  label: string
  shiftCount: number
  totalMins: number
  /** @brief Shifts without an employee (coverage rows only): gaps to resolve. */
  uncovered?: number
  filename: string
  /** @brief PDF blob (reused as an email attachment). */
  blob: Blob
  /** @brief Object URL for the PDF blob (revoke when the row is destroyed). */
  url: string
}

/** @brief Email-sending state for a row. */
type EmailStatus = 'sending' | 'sent' | 'error'

/** Volatile session retained during SPA navigation (Blobs cannot be serialized in localStorage). */
interface ReportSession {
  locId: number
  viewStart: number
  rows: PdfRow[] | null
  emailStatus: Record<string, EmailStatus>
  emailErrors: Record<string, string>
  emailSentAt: Record<string, string>
}

/** Separate session per company, granularity, and section; survives Report page unmounting. */
const reportSessions = new Map<string, ReportSession>()

/** @brief Date → "yyyy-MM-dd HH:mm:ss" (same format as the backend send log). */
function toDbDateTime(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** @brief "yyyy-MM-dd HH:mm:ss" → "dd/MM/yyyy HH:mm" for display. */
function fmtSentAt(s: string): string {
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/)
  return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : s
}

/**
 * @brief Crossed-out envelope with tooltip: shown instead of send buttons
 *        when the SMTP server is not fully configured.
 */
function EmailDisabledIcon({ id, message }: { id: string; message: string }) {
  return (
    <OverlayTrigger placement="top" overlay={<Tooltip id={id}>{message}</Tooltip>}>
      <span className="fa-layers fa-fw text-muted fs-5" role="img" aria-label={message} style={{ cursor: 'help' }}>
        <FontAwesomeIcon icon={faEnvelope} />
        <FontAwesomeIcon icon={faSlash} className="text-danger" />
      </span>
    </OverlayTrigger>
  )
}

export default function ReportPage() {
  const { t } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const structureName = useAppStore(s => s.currentStructure?.name ?? '')
  const mode = useAppStore(s => s.shiftWindowMode)
  const [searchParams, setSearchParams] = useSearchParams()
  const section: ReportSection = searchParams.get('section') === 'send' ? 'send' : 'coverage'
  const sessionKey = `${structureId}:${mode}:${section}`
  const cachedSession = reportSessions.get(sessionKey)
  const [schedule, setSchedule] = useState<ScheduleData | null>(null)
  const [loading, setLoading] = useState(false)

  // Location filter (0 = all, Coverage section only) and period
  const [locId, setLocId] = useState<number>(() => cachedSession?.locId ?? 0)
  const [viewStart, setViewStart] = useState<Date>(() => cachedSession
    ? new Date(cachedSession.viewStart)
    : normalizeViewStart(new Date(), mode))

  // Generation results
  const [rows, setRows] = useState<PdfRow[] | null>(() => cachedSession?.rows ?? null)
  const [generating, setGenerating] = useState(false)

  // Per-row email state (key → sending/sent/error) + bulk sending in progress
  const [emailStatus, setEmailStatus] = useState<Record<string, EmailStatus>>(() => cachedSession?.emailStatus ?? {})
  // Readable per-row error message (tooltip on the red button)
  const [emailErrors, setEmailErrors] = useState<Record<string, string>>(() => cachedSession?.emailErrors ?? {})
  // Date/time of the latest successful send per row ("yyyy-MM-dd HH:mm:ss", from the persistent log)
  const [emailSentAt, setEmailSentAt] = useState<Record<string, string>>(() => cachedSession?.emailSentAt ?? {})
  const [bulkSending, setBulkSending] = useState(false)
  // Calendar popover for jumping directly to a period (days with shifts highlighted)
  const [calOpen, setCalOpen] = useState(false)
  // false = SMTP not fully configured → email disabled (crossed-out envelope + tooltip)
  const [smtpReady, setSmtpReady] = useState(true)

  // Check SMTP configuration when entering the Send Shifts section.
  // Keep it enabled on a network error: the backend still rejects sends
  // with SMTP_NOT_CONFIGURED.
  useEffect(() => {
    if (section !== 'send') return
    emailApi.getSettings()
      .then(s => setSmtpReady(s.configured !== false))
      .catch(() => setSmtpReady(true))
  }, [section])

  /** @brief Revokes object URLs for current rows (prevents blob leaks). */
  const rowsRef = useRef<PdfRow[]>(cachedSession?.rows ?? [])
  const previousSessionKey = useRef(sessionKey)
  const clearRows = useCallback(() => {
    rowsRef.current.forEach(r => URL.revokeObjectURL(r.url))
    rowsRef.current = []
    setRows(null)
    setEmailStatus({})
    setEmailErrors({})
    setEmailSentAt({})
    reportSessions.delete(sessionKey)
  }, [sessionKey])

  // Synchronize the cache after every change. Object URLs remain valid when navigating to other pages.
  useEffect(() => {
    if (previousSessionKey.current !== sessionKey) return
    reportSessions.set(sessionKey, {
      locId, viewStart: viewStart.getTime(), rows,
      emailStatus, emailErrors, emailSentAt,
    })
  }, [sessionKey, locId, viewStart, rows, emailStatus, emailErrors, emailSentAt])

  // When switching company/granularity/section, restore its session when present.
  useEffect(() => {
    if (previousSessionKey.current === sessionKey) return
    previousSessionKey.current = sessionKey
    const saved = reportSessions.get(sessionKey)
    setLocId(saved?.locId ?? 0)
    setViewStart(saved ? new Date(saved.viewStart) : normalizeViewStart(new Date(), mode))
    rowsRef.current = saved?.rows ?? []
    setRows(saved?.rows ?? null)
    setEmailStatus(saved?.emailStatus ?? {})
    setEmailErrors(saved?.emailErrors ?? {})
    setEmailSentAt(saved?.emailSentAt ?? {})
  }, [sessionKey, mode])

  const load = useCallback((clearGenerated: boolean) => {
    setLoading(true)
    shiftsApi.schedule(structureId)
      .then(s => { setSchedule(s); if (clearGenerated) clearRows() })
      .catch(() => toast.error(t('toast.errorLoad', 'Errore nel caricamento.')))
      .finally(() => setLoading(false))
  }, [structureId, t, clearRows])

  useEffect(() => { load(false) }, [load])

  // ── Periodo ────────────────────────────────────────────────────────────────

  function navigate(delta: number) {
    setViewStart(v => addPeriods(v, mode, delta))
    clearRows()
  }

  function goToday() {
    setViewStart(normalizeViewStart(new Date(), mode))
    clearRows()
  }

  const periodText = periodLabel(viewStart, mode, t)

  // Days with at least one shift (to highlight in the quick-jump calendar)
  const shiftDays = useMemo(() => {
    const days = new Set<string>()
    for (const s of schedule?.shifts ?? []) {
      if (s.start) days.add(s.start.slice(0, 10))
    }
    return days
  }, [schedule])

  /** @brief Jumps to the period containing the day selected in the calendar. */
  function jumpToDate(isoDate: string) {
    setViewStart(normalizeViewStart(new Date(isoDate.replace(/-/g, '/')), mode))
    clearRows()
    setCalOpen(false)
  }

  // ── Background PDF generation ──────────────────────────────────────────────

  /** @brief All shifts in the visible period, ordered by start time. */
  function shiftsInPeriod(): Shift[] {
    if (!schedule) return []
    const { start, end } = windowFor(viewStart, mode)
    return schedule.shifts
      .filter(s => {
        if (!s.start) return false
        const d = new Date(s.start)
        return d >= start && d < end
      })
      .sort((a, b) => new Date(a.start).getTime() - new Date(b.start).getTime())
  }

  function sumMins(shifts: Shift[]): number {
    return shifts.reduce((a, s) =>
      a + Math.round((new Date(s.end).getTime() - new Date(s.start).getTime()) / 60000), 0)
  }

  async function handleGenerate() {
    if (!schedule) return
    setGenerating(true)
    clearRows()
    try {
      const slug = periodSlug(viewStart, mode)
      let branding = {
        ...EMPTY_PDF_TEMPLATE,
        structure_name: structureName,
        filename_shifts: t('pdf.filenameShifts', 'Turni'),
        filename_coverage: t('pdf.filenameCoverage', 'Copertura'),
      }
      try {
        const template = await pdfTemplatesApi.get(structureId)
        branding = { header_text: template.header_text, footer_text: template.footer_text,
          logo_data_url: template.logo_data_url, primary_color: template.primary_color,
          structure_name: structureName,
          filename_shifts: t('pdf.filenameShifts', 'Turni'),
          filename_coverage: t('pdf.filenameCoverage', 'Copertura'),
        }
      } catch { /* The fallback keeps PDF generation available. */ }
      const inPeriod = shiftsInPeriod()
      const newRows: PdfRow[] = []

      if (section === 'coverage') {
        // Location Coverage: one landscape PDF per location (only one when selected).
        // With "All locations", include only those with at least one shift in the period.
        const targets = (locId
          ? schedule.locations.filter(l => l.id === locId)
          : schedule.locations.filter(l => inPeriod.some(s => s.location_id === l.id)))
          .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))

        for (const location of targets) {
          const locShifts = inPeriod.filter(s => s.location_id === location.id)
          const uncovered = locShifts.filter(s => !s.employee).length
          const { blob, filename } = generateCoveragePdf(location, locShifts, periodText, slug, branding, t)
          newRows.push({
            key: `loc-${location.id}`, coverage: true,
            label: location.name,
            shiftCount: locShifts.length, totalMins: sumMins(locShifts), uncovered,
            filename, blob, url: URL.createObjectURL(blob),
          })
          await new Promise(r => setTimeout(r)) // let the UI breathe between PDFs
        }
        rowsRef.current = newRows
        setRows(newRows)
        return
      }

      // Send Shifts: one personal PDF per employee with shifts in the period
      // (all locations — the employee receives THEIR complete list).
      const involvedIds = [...new Set(inPeriod.map(s => s.employee?.id).filter((id): id is number => id != null))]
      const involved = schedule.employees
        .filter(e => involvedIds.includes(e.id))
        .sort((a, b) => (a.fullName ?? '').localeCompare(b.fullName ?? ''))

      for (const employee of involved) {
        const personal = inPeriod.filter(s => s.employee?.id === employee.id)
        const { blob, filename } = generateEmployeePdf(employee, personal, periodText, slug, branding, t)
        newRows.push({
          key: `emp-${employee.id}`, coverage: false, employeeId: employee.id,
          label: employee.fullName,
          shiftCount: personal.length, totalMins: sumMins(personal),
          filename, blob, url: URL.createObjectURL(blob),
        })
        await new Promise(r => setTimeout(r)) // let the UI breathe between PDFs
      }

      rowsRef.current = newRows
      setRows(newRows)

      // Persistent send history: mark rows already sent for this period with "Sent on ..."
      try {
        const log = await emailApi.log(structureId, slug)
        const status: Record<string, EmailStatus> = {}
        const sentAt: Record<string, string> = {}
        for (const entry of log) {
          const key = `emp-${entry.employee_id}`
          if (newRows.some(r => r.key === key)) {
            status[key] = 'sent'
            sentAt[key] = entry.sent_at
          }
        }
        setEmailStatus(status)
        setEmailSentAt(sentAt)
      } catch { /* log unavailable: no history to show */ }
    } finally {
      setGenerating(false)
    }
  }

  // ── Email sending ──────────────────────────────────────────────────────────

  /**
   * @brief Translates a backend error into a clear message for the user.
   * @details The backend responds with typed codes ({"error":"AUTH_FAILED"}, ...):
   *          here each code becomes a readable explanation indicating what to check.
   *          Technical details remain in the server logs.
   */
  function emailErrorText(err: unknown): string {
    let code = ''
    try { code = JSON.parse(String((err as Error)?.message ?? '')).error ?? '' } catch { /* non-JSON text */ }
    switch (code) {
      case 'NO_EMAIL':
        return t('error.email.noEmail', 'L’operatore non ha un indirizzo email: aggiungilo dalla pagina Operatori.')
      case 'INVALID_EMAIL':
        return t('error.email.recipient', 'L’indirizzo email dell’operatore non è valido: correggilo dalla pagina Operatori.')
      case 'NO_EMPLOYEE':
        return t('error.email.notFound', 'Operatore non trovato: aggiorna la pagina e rigenera i PDF.')
      case 'AUTH_FAILED':
        return t('error.email.auth', 'Il server di posta ha rifiutato le credenziali: verifica utente e password SMTP nella configurazione.')
      case 'CONNECTION_FAILED':
        return t('error.email.connection', 'Impossibile raggiungere il server di posta: controlla la connessione a Internet e riprova.')
      case 'SENDER_REJECTED':
        return t('error.email.sender', 'Il server ha rifiutato il mittente: l’indirizzo mittente non è autorizzato sul servizio di posta.')
      case 'RECIPIENT_REJECTED':
        return t('error.email.recipient', 'Il server ha rifiutato il destinatario: controlla che l’indirizzo email dell’operatore sia corretto.')
      case 'QUOTA_EXCEEDED':
        return t('error.email.quota', 'Raggiunto il limite di invii del servizio di posta: riprova più tardi.')
      case 'SMTP_NOT_CONFIGURED':
        return t('error.email.notConfigured', 'Il server SMTP non è configurato: completa i Parametri Email nella Configurazione.')
      case 'BAD_REQUEST':
      case 'BAD_PDF':
        return t('error.email.badData', 'Dati non validi: rigenera i PDF e riprova.')
      default:
        return t('error.email.generic', 'Invio non riuscito per un problema del server di posta: riprova più tardi.')
    }
  }

  /** @brief Sends an employee an email with their PDF attached. @return true on success. */
  async function sendOne(row: PdfRow, quiet = false): Promise<boolean> {
    if (row.employeeId == null) return false
    setEmailStatus(m => ({ ...m, [row.key]: 'sending' }))
    try {
      await emailApi.sendShifts({
        employee_id: row.employeeId,
        structure_id: structureId,
        period_label: periodText,
        period_slug: periodSlug(viewStart, mode),
        filename: row.filename,
        pdf_base64: await blobToBase64(row.blob),
      })
      setEmailStatus(m => ({ ...m, [row.key]: 'sent' }))
      setEmailSentAt(m => ({ ...m, [row.key]: toDbDateTime(new Date()) }))
      setEmailErrors(m => {
        const next = { ...m }
        delete next[row.key]
        return next
      })
      if (!quiet) toast.success(`${t('toast.emailSent', 'Email inviata!')} — ${row.label}`)
      return true
    } catch (err: unknown) {
      const message = emailErrorText(err)
      setEmailStatus(m => ({ ...m, [row.key]: 'error' }))
      setEmailErrors(m => ({ ...m, [row.key]: message }))
      if (!quiet) toast.error(`${row.label}: ${message}`)
      return false
    }
  }

  /** @brief Sequentially emails all employee rows that have not yet been sent. */
  async function sendAll() {
    if (!rows) return
    const targets = rows.filter(r => r.employeeId != null && emailStatus[r.key] !== 'sent')
    setBulkSending(true)
    let ok = 0
    try {
      for (const row of targets) {
        if (await sendOne(row, true)) ok++
      }
    } finally {
      setBulkSending(false)
    }
    const ko = targets.length - ok
    if (ok > 0) toast.success(`${ok} ${t('toast.emailsSent', 'email inviate')}`)
    if (ko > 0) toast.error(`${ko} ${t('toast.emailsFailed', 'email non inviate')}`)
  }

  /** @brief Results heading: selected location, all locations, or all shifts. */
  function subjectName(): string {
    if (section === 'coverage') {
      return (locId ? schedule?.locations.find(l => l.id === locId)?.name : null)
        ?? t('report.allLocationsPlain', 'Tutte le sedi')
    }
    return t('report.allShifts', 'Tutti i turni')
  }

  if (loading) return <div className="text-center py-5"><Spinner /></div>
  if (!schedule) return <p className="text-muted">{t('msg.noData', 'Dati non disponibili.')}</p>

  const locations = [...schedule.locations].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  const fmtHours = (mins: number) => `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, '0')}min`

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('nav.report', 'Report')}</h5>
      </div>

      <Row className="g-3">
        <Col md={3} xl={2}>
          <nav className="config-sidebar" aria-label={t('nav.report', 'Report')}>
            {([
              ['coverage', t('report.menu.coverage', 'Report per Caposala'), t('report.menu.coverage.tooltip', 'Report di copertura per sede, da stampare'), faUserNurse],
              ['send', t('report.menu.send', 'Invio Turni'), t('report.menu.send.tooltip', 'Invia a ogni operatore i suoi turni via email'), faEnvelope],
            ] as const).map(([key, label, tooltip, icon]) => (
              <button
                key={key}
                type="button"
                className={`config-sidebar-item${section === key ? ' active' : ''}`}
                title={tooltip}
                aria-current={section === key ? 'page' : undefined}
                onClick={() => setSearchParams({ section: key }, { replace: true })}
              >
                <FontAwesomeIcon icon={icon} />
                <span>{label}</span>
              </button>
            ))}
          </nav>
        </Col>
        <Col md={9} xl={10}>

      <Row className="mb-3 align-items-end g-2">
        {section === 'coverage' && (
          <Col sm={3}>
            <Form.Label>{t('label.location', 'Sede')}</Form.Label>
            <Form.Select value={locId} onChange={e => { setLocId(parseInt(e.target.value)); clearRows() }}>
              <option value={0}>{t('report.allLocations', '— Tutte le sedi —')}</option>
              {locations.map(l => <option key={l.id} value={l.id}>{t('location.' + l.id, l.name)}</option>)}
            </Form.Select>
          </Col>
        )}
        <Col sm="auto">
          <Form.Label className="d-block">{t('label.period', 'Periodo')}</Form.Label>
          <div className="d-flex align-items-center gap-2">
            <Button variant="outline-primary" onClick={() => navigate(-1)} aria-label={t('nav.prevPeriod', 'Periodo precedente')}>
              <FontAwesomeIcon icon={faChevronLeft} />
            </Button>
            <OverlayTrigger
              trigger="click" placement="bottom" rootClose
              show={calOpen} onToggle={setCalOpen}
              overlay={
                <Popover id="report-cal-popover" style={{ maxWidth: 'none' }}>
                  <Popover.Body className="p-2">
                    <ShiftDaysCalendar
                      shiftDays={shiftDays}
                      value={`${viewStart.getFullYear()}-${String(viewStart.getMonth() + 1).padStart(2, '0')}-${String(viewStart.getDate()).padStart(2, '0')}`}
                      onChange={jumpToDate}
                    />
                  </Popover.Body>
                </Popover>
              }
            >
              <button
                type="button"
                className="btn btn-link fw-semibold text-nowrap text-decoration-none p-0"
                style={{ minWidth: 140 }}
                title={t('report.pickDate', 'Clicca per scegliere una data (i giorni con turni sono evidenziati)')}
              >
                {periodText}
              </button>
            </OverlayTrigger>
            <Button variant="outline-primary" onClick={() => navigate(1)} aria-label={t('nav.nextPeriod', 'Periodo successivo')}>
              <FontAwesomeIcon icon={faChevronRight} />
            </Button>
            <Button variant="outline-primary" onClick={goToday}>
              <FontAwesomeIcon icon={faLocationCrosshairs} className="me-1" />{t('btn.today', 'Oggi')}
            </Button>
          </div>
        </Col>
        <Col sm="auto">
          <Button variant="danger" onClick={handleGenerate} disabled={generating}>
            {generating
              ? <><Spinner size="sm" className="me-2" />{t('report.generating', 'Generazione PDF…')}</>
              : <><FontAwesomeIcon icon={faFilePdf} className="me-2" />{t('btn.generatePdf', 'Genera PDF')}</>}
          </Button>
        </Col>
      </Row>

      {rows !== null && (
        rows.length === 0 ? (
          <Alert variant="warning" className="mb-0">
            {t('report.noShiftsFor', 'Nessun turno per')} <strong>{subjectName()}</strong> — <strong>{periodText}</strong>.
          </Alert>
        ) : (
          <>
            <p className="text-muted mb-1">
              <strong>{subjectName()}</strong> &mdash; <strong>{periodText}</strong> &mdash; <strong>{rows.length}</strong> PDF
            </p>
            <Table size="sm" bordered hover className="mb-0 align-middle">
              <thead className="table-primary">
                <tr>
                  <th>{section === 'coverage' ? t('label.location', 'Sede') : t('label.employee', 'Impiegato')}</th>
                  <th className="text-end">{t('table.shifts', 'Turni')}</th>
                  {section === 'coverage' && <th className="text-end">{t('table.uncovered', 'Scoperti')}</th>}
                  <th className="text-end">{t('report.hours', 'Ore')}</th>
                  <th>PDF</th>
                  {section === 'send' && (
                    <th style={{ width: 170 }}>
                      {smtpReady ? (
                        <Button
                          variant="primary" size="sm"
                          onClick={sendAll}
                          disabled={bulkSending || !rows.some(r => r.employeeId != null && emailStatus[r.key] !== 'sent')}
                        >
                          {bulkSending
                            ? <Spinner size="sm" />
                            : <><FontAwesomeIcon icon={faEnvelope} className="me-1" />{t('btn.sendAllEmails', 'Invia tutte le email')}</>}
                        </Button>
                      ) : (
                        <EmailDisabledIcon
                          id="smtp-disabled-all"
                          message={t('email.smtpNotConfigured', 'Invio email disabilitato: configura il server SMTP in Configurazione → Parametri Email.')}
                        />
                      )}
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {rows.map(r => (
                  <tr key={r.key}>
                    <td>
                      <FontAwesomeIcon icon={r.coverage ? faBuilding : faUser} className="me-2 text-secondary" />
                      {r.label}
                    </td>
                    <td className="text-end">{r.shiftCount}</td>
                    {section === 'coverage' && (
                      <td className="text-end">
                        {(r.uncovered ?? 0) > 0 ? (
                          <span className="text-danger fw-semibold">
                            <FontAwesomeIcon icon={faTriangleExclamation} className="me-1" />{r.uncovered}
                          </span>
                        ) : (
                          <span className="text-muted">0</span>
                        )}
                      </td>
                    )}
                    <td className="text-end">{fmtHours(r.totalMins)}</td>
                    <td>
                      <a href={r.url} target="_blank" rel="noopener" title={t('report.openPdf', 'Apri il PDF nel browser')}>
                        <FontAwesomeIcon icon={faFilePdf} className="me-1" />{r.filename}
                      </a>
                      <a href={r.url} download={r.filename} className="ms-3" title={t('report.downloadPdf', 'Scarica il PDF')}>
                        <FontAwesomeIcon icon={faDownload} />
                      </a>
                    </td>
                    {section === 'send' && <td>
                      {r.employeeId == null ? (
                        <span className="text-muted">—</span>
                      ) : emailStatus[r.key] === 'sent' ? (
                        <span className="text-nowrap">
                          <span className="text-success" title={t('report.emailSent', 'Email inviata')}>
                            <FontAwesomeIcon icon={faCheck} className="me-1" />
                            {t('report.sentOn', 'Inviata il')} {emailSentAt[r.key] ? fmtSentAt(emailSentAt[r.key]) : ''}
                          </span>
                          {smtpReady ? (
                            <Button
                              variant="link" size="sm" className="p-0 ms-2"
                              onClick={() => sendOne(r)}
                              disabled={bulkSending}
                              title={t('btn.resend', 'Reinvia')}
                            >
                              <FontAwesomeIcon icon={faEnvelope} />
                            </Button>
                          ) : (
                            <span className="ms-2">
                              <EmailDisabledIcon
                                id={`smtp-disabled-${r.key}`}
                                message={t('email.smtpNotConfigured', 'Invio email disabilitato: configura il server SMTP in Configurazione → Parametri Email.')}
                              />
                            </span>
                          )}
                        </span>
                      ) : !smtpReady ? (
                        <EmailDisabledIcon
                          id={`smtp-disabled-${r.key}`}
                          message={t('email.smtpNotConfigured', 'Invio email disabilitato: configura il server SMTP in Configurazione → Parametri Email.')}
                        />
                      ) : (
                        <Button
                          variant={emailStatus[r.key] === 'error' ? 'outline-danger' : 'outline-primary'}
                          size="sm"
                          onClick={() => sendOne(r)}
                          disabled={emailStatus[r.key] === 'sending' || bulkSending}
                          title={emailStatus[r.key] === 'error'
                            ? `${emailErrors[r.key] ?? ''} — ${t('report.retrySend', 'Clicca per riprovare')}`
                            : undefined}
                        >
                          {emailStatus[r.key] === 'sending'
                            ? <Spinner size="sm" />
                            : <><FontAwesomeIcon icon={emailStatus[r.key] === 'error' ? faTriangleExclamation : faEnvelope} className="me-1" />{t('btn.sendEmail', 'Invia Email')}</>}
                        </Button>
                      )}
                    </td>}
                  </tr>
                ))}
              </tbody>
            </Table>
          </>
        )
      )}
        </Col>
      </Row>
    </div>
  )
}
