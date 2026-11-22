/**
 * @file AssignmentSeriesPanel.tsx
 * @brief Recurring visits ("Serientermine") attached to the customer-assignment page.
 *
 * @details
 * Planning a month ahead by hand means one assignment per visit per customer. This panel keeps
 * the recurrence rule instead: a series holds customer, location, time, duration and team, and
 * "Erzeugen" materialises the visits of the visible period. Generation is a top-up — it skips
 * dates that already exist and dates the planner cancelled — so moving the period forward and
 * generating again only adds what is missing.
 *
 * A deviation on a single date is an exception on that date and never rewrites the series; the
 * per-date cancel is offered on the assignment rows in {@link AssignmentsPage}.
 */

import { useCallback, useEffect, useState } from 'react'
import { Alert, Badge, Button, Card, Col, Form, Modal, Row, Spinner, Table } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faCalendarPlus, faChevronDown, faChevronUp, faPlus, faTrash, faWandMagicSparkles } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import type { Customer } from '../api/customers'
import type { Location } from '../api/locations'
import type { Skill } from '../api/skills'
import { errorCode } from '../api/client'
import { translateSeriesError } from '../utils/assignmentSeriesErrors'
import {
  assignmentSeriesApi,
  type AssignmentSeries,
  type AssignmentSeriesPayload,
  type SeriesPattern,
} from '../api/assignmentSeries'

/** @brief Roles the backend accepts in a staffing requirement. */
const ROLES = ['PHYSICIAN', 'MEDICAL_ASSISTANT'] as const

/** @brief ISO weekdays in the order a German planner reads them. */
const WEEKDAYS = [
  { value: 1, key: 'day.mon.s', fallback: 'Mo' },
  { value: 2, key: 'day.tue.s', fallback: 'Di' },
  { value: 3, key: 'day.wed.s', fallback: 'Mi' },
  { value: 4, key: 'day.thu.s', fallback: 'Do' },
  { value: 5, key: 'day.fri.s', fallback: 'Fr' },
  { value: 6, key: 'day.sat.s', fallback: 'Sa' },
  { value: 7, key: 'day.sun.s', fallback: 'So' },
] as const

function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`)
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

function parseWeekdays(csv: string): number[] {
  return csv.split(',').map(v => Number(v.trim())).filter(v => v >= 1 && v <= 7)
}

function emptyDraft(from: string, customer: Customer | undefined): AssignmentSeriesPayload {
  return {
    customerId: customer?.id ?? 0,
    locationId: customer?.locationIds[0] ?? 0,
    title: customer?.name ?? '',
    service: '',
    startTime: customer?.defaultStartTime ?? '08:00',
    durationMinutes: customer?.defaultDurationMinutes ?? 120,
    priority: 2,
    fixedTime: false,
    notes: '',
    patternType: 'WEEKLY',
    weekdays: '1',
    nth: 1,
    effectiveFrom: from,
    effectiveTo: addDays(from, 182),
    active: true,
    requirements: [{
      role: 'MEDICAL_ASSISTANT',
      skillId: customer?.defaultSkillId ?? null,
      headcount: 1,
    }],
  }
}

interface Props {
  structureId: number
  customers: Customer[]
  locations: Location[]
  skills: Skill[]
  /** First day of the period currently shown on the page. */
  from: string
  /** Last day of the period currently shown on the page. */
  to: string
  /** Called after visits were created, so the page reloads its assignments. */
  onGenerated: () => void
}

/** @brief Series list plus the create dialog. */
export default function AssignmentSeriesPanel({
  structureId, customers, locations, skills, from, to, onGenerated,
}: Props) {
  const { t } = useTranslation()

  const [series, setSeries] = useState<AssignmentSeries[]>([])
  const [expanded, setExpanded] = useState(false)
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [draft, setDraft] = useState<AssignmentSeriesPayload>(() => emptyDraft(from, undefined))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadSeries = useCallback(() => {
    if (structureId <= 0) return
    setLoading(true)
    assignmentSeriesApi.list(structureId)
      .then(setSeries)
      .catch(() => setSeries([]))
      .finally(() => setLoading(false))
  }, [structureId])

  useEffect(loadSeries, [loadSeries])

  function customerOf(id: number): Customer | undefined {
    return customers.find(c => c.id === id)
  }

  const customerLocations = locations.filter(
    l => customerOf(draft.customerId)?.locationIds.includes(l.id))

  function patternLabel(pattern: string): string {
    switch (pattern) {
      case 'WEEKLY': return t('assignments.series.pattern.weekly', 'Wöchentlich')
      case 'BIWEEKLY': return t('assignments.series.pattern.biweekly', 'Alle 2 Wochen')
      case 'MONTHLY_NTH_WEEKDAY': return t('assignments.series.pattern.monthly', 'Monatlich (n-ter Wochentag)')
      default: return pattern
    }
  }

  function describe(s: AssignmentSeries): string {
    const days = parseWeekdays(s.weekdays)
      .map(d => WEEKDAYS.find(w => w.value === d))
      .filter(w => w !== undefined)
      .map(w => t(w.key, w.fallback))
    const parts = [patternLabel(s.patternType)]
    if (days.length > 0) {
      const prefix = s.patternType === 'MONTHLY_NTH_WEEKDAY'
        ? (s.nth >= 5
          ? t('assignments.series.nthLast', 'letzter')
          : t('assignments.series.nthValue', '{{n}}. Wochentag', { n: s.nth }))
        : ''
      parts.push(prefix ? `${prefix}: ${days.join(', ')}` : days.join(', '))
    }
    parts.push(`${s.startTime} · ${s.durationMinutes}′`)
    return parts.join(' · ')
  }

  function openNew(): void {
    const customer = customers.find(c => c.active) ?? customers[0]
    setDraft(emptyDraft(from, customer))
    setError(null)
    setModalOpen(true)
  }

  async function handleSave(): Promise<void> {
    setBusy(true)
    setError(null)
    try {
      const created = await assignmentSeriesApi.create(draft, structureId)
      const result = await assignmentSeriesApi.generate(created.id, from, to)
      toast.success(t('assignments.series.created',
        'Serie angelegt: {{count}} Termine erzeugt.', { count: result.generated }))
      setModalOpen(false)
      loadSeries()
      onGenerated()
    } catch (err) {
      setError(translateSeriesError(errorCode(err), (k, f) => t(k, f)))
    } finally {
      setBusy(false)
    }
  }

  async function handleGenerate(s: AssignmentSeries): Promise<void> {
    setBusy(true)
    try {
      const result = await assignmentSeriesApi.generate(s.id, from, to)
      toast.success(t('assignments.series.generated',
        '{{count}} Termine erzeugt, {{skipped}} bereits vorhanden.',
        { count: result.generated, skipped: result.skipped }))
      loadSeries()
      onGenerated()
    } catch (err) {
      toast.error(translateSeriesError(errorCode(err), (k, f) => t(k, f)))
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete(s: AssignmentSeries): Promise<void> {
    if (!window.confirm(t('assignments.series.confirmDelete',
      'Serie löschen? Die bereits erzeugten Termine bleiben erhalten.'))) return
    try {
      await assignmentSeriesApi.remove(s.id, false)
      toast.success(t('assignments.series.deleted', 'Serie gelöscht.'))
      loadSeries()
    } catch (err) {
      toast.error(translateSeriesError(errorCode(err), (k, f) => t(k, f)))
    }
  }

  function toggleWeekday(day: number): void {
    const current = parseWeekdays(draft.weekdays)
    const next = current.includes(day) ? current.filter(d => d !== day) : [...current, day].sort()
    setDraft({ ...draft, weekdays: next.join(',') })
  }

  return (
    <Card className="mb-3">
      <Card.Header className="d-flex align-items-center gap-2">
        <FontAwesomeIcon icon={faCalendarPlus} />
        <strong>{t('assignments.series.title', 'Serientermine')}</strong>
        <Badge bg="light" text="dark">{series.length}</Badge>
        <div className="ms-auto d-flex gap-2">
          <Button size="sm" variant="outline-primary" onClick={openNew}>
            <FontAwesomeIcon icon={faPlus} className="me-1" />
            {t('assignments.series.new', 'Neue Serie')}
          </Button>
          <Button size="sm" variant="outline-secondary" onClick={() => setExpanded(!expanded)}>
            <FontAwesomeIcon icon={expanded ? faChevronUp : faChevronDown} />
          </Button>
        </div>
      </Card.Header>
      {expanded && (
        <Card.Body className="py-2">
          {loading ? (
            <div className="text-center py-3"><Spinner size="sm" animation="border" /></div>
          ) : series.length === 0 ? (
            <div className="text-muted small">
              {t('assignments.series.empty', 'Keine Serientermine angelegt.')}
            </div>
          ) : (
            <Table size="sm" borderless className="align-middle mb-0">
              <tbody>
                {series.map(s => (
                  <tr key={s.id}>
                    <td>
                      <div>{s.customerName} – {s.title}</div>
                      <div className="text-muted small">{s.locationName} · {describe(s)}</div>
                      <div className="text-muted small">
                        {t('assignments.series.range', 'Gültig {{from}} bis {{to}}',
                          { from: s.effectiveFrom, to: s.effectiveTo })}
                        {' · '}
                        {t('assignments.series.occurrences', '{{count}} Termine erzeugt',
                          { count: s.occurrenceCount })}
                      </div>
                    </td>
                    <td className="text-nowrap text-end" style={{ width: 190 }}>
                      <Button size="sm" variant="outline-success" className="me-1" disabled={busy}
                        onClick={() => handleGenerate(s)}
                        title={t('assignments.series.generateHint',
                          'Fehlende Termine im sichtbaren Zeitraum erzeugen')}>
                        <FontAwesomeIcon icon={faWandMagicSparkles} className="me-1" />
                        {t('assignments.series.generate', 'Erzeugen')}
                      </Button>
                      <Button size="sm" variant="outline-danger" disabled={busy}
                        onClick={() => handleDelete(s)}
                        title={t('assignments.delete', 'Löschen')}>
                        <FontAwesomeIcon icon={faTrash} />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card.Body>
      )}

      <Modal show={modalOpen} onHide={() => setModalOpen(false)} size="lg">
        <Modal.Header closeButton>
          <Modal.Title>{t('assignments.series.new', 'Neue Serie')}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          {error && <Alert variant="danger" className="py-2">{error}</Alert>}
          <Row className="g-2">
            <Col md={6}>
              <Form.Label>{t('assignments.customer', 'Kunde')}</Form.Label>
              <Form.Select value={draft.customerId} onChange={e => {
                const id = Number(e.target.value)
                const customer = customerOf(id)
                setDraft({
                  ...draft,
                  customerId: id,
                  title: customer?.name ?? draft.title,
                  startTime: customer?.defaultStartTime ?? draft.startTime,
                  durationMinutes: customer?.defaultDurationMinutes ?? draft.durationMinutes,
                  locationId: customer?.locationIds[0] ?? draft.locationId,
                })
              }}>
                <option value={0}>{t('assignments.select', '– auswählen –')}</option>
                {customers.filter(c => c.active || c.id === draft.customerId).map(c => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </Form.Select>
            </Col>
            <Col md={6}>
              <Form.Label>{t('assignments.location', 'Ort')}</Form.Label>
              <Form.Select value={draft.locationId}
                onChange={e => setDraft({ ...draft, locationId: Number(e.target.value) })}>
                <option value={0}>{t('assignments.select', '– auswählen –')}</option>
                {(customerLocations.length > 0 ? customerLocations : locations).map(l => (
                  <option key={l.id} value={l.id}>{l.name}</option>
                ))}
              </Form.Select>
            </Col>
            <Col md={6}>
              <Form.Label>{t('assignments.titleLabel', 'Bezeichnung')}</Form.Label>
              <Form.Control value={draft.title}
                onChange={e => setDraft({ ...draft, title: e.target.value })} />
            </Col>
            <Col md={6}>
              <Form.Label>{t('assignments.service', 'Leistung')}</Form.Label>
              <Form.Control value={draft.service ?? ''}
                onChange={e => setDraft({ ...draft, service: e.target.value })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('assignments.start', 'Beginn')}</Form.Label>
              <Form.Control type="time" value={draft.startTime}
                onChange={e => setDraft({ ...draft, startTime: e.target.value })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('assignments.duration', 'Dauer (Minuten)')}</Form.Label>
              <Form.Control type="number" min={15} step={15} value={draft.durationMinutes}
                onChange={e => setDraft({ ...draft, durationMinutes: Number(e.target.value) })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('assignments.priority', 'Priorität')}</Form.Label>
              <Form.Select value={draft.priority ?? 2}
                onChange={e => setDraft({ ...draft, priority: Number(e.target.value) })}>
                <option value={1}>{t('assignments.priorityHigh', 'Hoch')}</option>
                <option value={2}>{t('assignments.priorityNormal', 'Normal')}</option>
                <option value={3}>{t('assignments.priorityLow', 'Niedrig')}</option>
              </Form.Select>
            </Col>
            <Col md={6}>
              <Form.Label>{t('assignments.series.pattern', 'Wiederholung')}</Form.Label>
              <Form.Select value={draft.patternType}
                onChange={e => setDraft({ ...draft, patternType: e.target.value as SeriesPattern })}>
                <option value="WEEKLY">{patternLabel('WEEKLY')}</option>
                <option value="BIWEEKLY">{patternLabel('BIWEEKLY')}</option>
                <option value="MONTHLY_NTH_WEEKDAY">{patternLabel('MONTHLY_NTH_WEEKDAY')}</option>
              </Form.Select>
            </Col>
            {draft.patternType === 'MONTHLY_NTH_WEEKDAY' && (
              <Col md={6}>
                <Form.Label>{t('assignments.series.nth', 'Wochentag im Monat')}</Form.Label>
                <Form.Select value={draft.nth ?? 1}
                  onChange={e => setDraft({ ...draft, nth: Number(e.target.value) })}>
                  {[1, 2, 3, 4].map(n => (
                    <option key={n} value={n}>
                      {t('assignments.series.nthValue', '{{n}}. Wochentag', { n })}
                    </option>
                  ))}
                  <option value={5}>{t('assignments.series.nthLast', 'letzter Wochentag')}</option>
                </Form.Select>
              </Col>
            )}
            <Col md={12}>
              <Form.Label>{t('assignments.series.weekdays', 'Wochentage')}</Form.Label>
              <div className="d-flex flex-wrap gap-3">
                {WEEKDAYS.map(w => (
                  <Form.Check key={w.value} type="checkbox" id={`series-day-${w.value}`}
                    label={t(w.key, w.fallback)}
                    checked={parseWeekdays(draft.weekdays).includes(w.value)}
                    onChange={() => toggleWeekday(w.value)} />
                ))}
              </div>
            </Col>
            <Col md={4}>
              <Form.Label>{t('assignments.series.effectiveFrom', 'Gültig ab')}</Form.Label>
              <Form.Control type="date" value={draft.effectiveFrom}
                onChange={e => setDraft({ ...draft, effectiveFrom: e.target.value })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('assignments.series.effectiveTo', 'Gültig bis')}</Form.Label>
              <Form.Control type="date" value={draft.effectiveTo}
                onChange={e => setDraft({ ...draft, effectiveTo: e.target.value })} />
            </Col>
            <Col md={4} className="d-flex align-items-end">
              <Form.Check type="checkbox" id="series-fixed-time" checked={draft.fixedTime ?? false}
                label={t('assignments.fixedTime', 'Fixzeit')}
                onChange={e => setDraft({ ...draft, fixedTime: e.target.checked })} />
            </Col>
          </Row>

          <hr />
          <div className="d-flex align-items-center mb-2">
            <strong>{t('assignments.requirements', 'Teamzusammensetzung')}</strong>
            <Button size="sm" variant="outline-primary" className="ms-auto"
              onClick={() => setDraft({
                ...draft,
                requirements: [...draft.requirements, { role: 'MEDICAL_ASSISTANT', skillId: null, headcount: 1 }],
              })}>
              <FontAwesomeIcon icon={faPlus} className="me-1" />
              {t('assignments.addRequirement', 'Anforderung')}
            </Button>
          </div>
          {draft.requirements.map((requirement, index) => (
            <Row className="g-2 mb-2" key={index}>
              <Col md={4}>
                <Form.Select value={requirement.role} onChange={e => {
                  const next = [...draft.requirements]
                  next[index] = { ...requirement, role: e.target.value }
                  setDraft({ ...draft, requirements: next })
                }}>
                  {ROLES.map(r => (
                    <option key={r} value={r}>
                      {r === 'PHYSICIAN'
                        ? t('assignments.rolePhysician', 'Arzt/Ärztin')
                        : t('assignments.roleAssistant', 'MFA')}
                    </option>
                  ))}
                </Form.Select>
              </Col>
              <Col md={5}>
                <Form.Select value={requirement.skillId ?? ''} onChange={e => {
                  const next = [...draft.requirements]
                  next[index] = { ...requirement, skillId: e.target.value ? Number(e.target.value) : null }
                  setDraft({ ...draft, requirements: next })
                }}>
                  <option value="">{t('assignments.anyQualification', 'Keine Zusatzqualifikation')}</option>
                  {skills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </Form.Select>
              </Col>
              <Col md={2}>
                <Form.Control type="number" min={1} value={requirement.headcount} onChange={e => {
                  const next = [...draft.requirements]
                  next[index] = { ...requirement, headcount: Number(e.target.value) }
                  setDraft({ ...draft, requirements: next })
                }} />
              </Col>
              <Col md={1}>
                <Button variant="outline-danger" size="sm" disabled={draft.requirements.length <= 1}
                  onClick={() => setDraft({
                    ...draft,
                    requirements: draft.requirements.filter((_, i) => i !== index),
                  })}>
                  <FontAwesomeIcon icon={faTrash} />
                </Button>
              </Col>
            </Row>
          ))}

          <Form.Label className="mt-2">{t('assignments.notes', 'Hinweise')}</Form.Label>
          <Form.Control as="textarea" rows={2} value={draft.notes ?? ''}
            onChange={e => setDraft({ ...draft, notes: e.target.value })} />
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setModalOpen(false)} disabled={busy}>
            {t('btn.cancel', 'Abbrechen')}
          </Button>
          <Button variant="primary" onClick={handleSave} disabled={busy}>
            {busy && <Spinner size="sm" animation="border" className="me-1" />}
            {t('btn.save', 'Speichern')}
          </Button>
        </Modal.Footer>
      </Modal>
    </Card>
  )
}
