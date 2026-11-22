/**
 * @file AssignmentsPage.tsx
 * @brief Customer-centric planning page (MVZ): assignments, teams, optimisation.
 *
 * @details
 * This page is the operational home of the new customer layer. A row is a
 * <b>Kundeneinsatz</b> (customer assignment) — who is visited, where, when, for how long and
 * with which team — and not a shift. Each row expands into its positions, one per required team
 * member, showing the assigned operator or an explicit "offen" (unfilled) marker.
 *
 * The optimisation flow reuses the existing solver pipeline unchanged:
 * `shiftsApi.solve()` → poll `getJob()` → keep the solution in local state → the planner
 * approves with "Plan übernehmen" → `shiftsApi.saveAssignments()`. Nothing is written to the
 * database before that approval, so an unsatisfactory proposal is discarded without trace.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Badge, Button, Col, Form, Modal, Row, Spinner, Table } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faPlus, faTrash, faWandMagicSparkles, faLock, faLockOpen, faRotateLeft, faCheck } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../store/useAppStore'
import { customersApi, type Customer } from '../api/customers'
import { assignmentsApi, type AssignmentConflict, type AssignmentPayload, type CustomerAssignment } from '../api/assignments'
import { locationsApi, type Location } from '../api/locations'
import { skillsApi, type Skill } from '../api/skills'
import { shiftsApi } from '../api/shifts'
import { errorCode } from '../api/client'

/** @brief Roles the backend accepts in a staffing requirement. */
const ROLES = ['PHYSICIAN', 'MEDICAL_ASSISTANT'] as const

/** @brief Assignment statuses, in the order the planner normally moves through them. */
const STATUSES = ['FIXED', 'FLEXIBLE', 'RECURRING', 'RESCHEDULED', 'CANCELLED', 'COMPLETED'] as const

/** @brief Employee proposed by the solver for one position, held until the plan is approved. */
interface ProposedSlot {
  employeeId: number | null
  employeeName: string
  version: number
}

/** @brief Empty form, used both for "new" and as the shape the modal edits. */
function emptyDraft(customer: Customer | undefined, locationId: number): AssignmentPayload {
  const today = new Date().toISOString().slice(0, 10)
  return {
    customerId: customer?.id ?? 0,
    locationId,
    title: customer ? customer.name : '',
    service: '',
    assignmentDate: today,
    startTime: customer?.defaultStartTime ?? '08:00',
    durationMinutes: customer?.defaultDurationMinutes ?? 120,
    priority: 2,
    status: 'FLEXIBLE',
    fixedTime: false,
    notes: '',
    requirements: [{ role: 'MEDICAL_ASSISTANT', skillId: customer?.defaultSkillId ?? null, headcount: 1 }],
  }
}

function dateAddDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`)
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

export default function AssignmentsPage() {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)

  const today = useMemo(() => new Date().toISOString().slice(0, 10), [])
  const [from, setFrom] = useState(today)
  const [to, setTo] = useState(dateAddDays(today, 6))

  const [assignments, setAssignments] = useState<CustomerAssignment[]>([])
  const [customers, setCustomers] = useState<Customer[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [skills, setSkills] = useState<Skill[]>([])
  const [conflicts, setConflicts] = useState<AssignmentConflict[]>([])
  const [loading, setLoading] = useState(false)

  const [solving, setSolving] = useState(false)
  const [solution, setSolution] = useState<Map<number, ProposedSlot> | null>(null)
  const [saving, setSaving] = useState(false)
  const jobId = useRef<string | null>(null)
  const pollTimer = useRef<number | null>(null)
  const generation = useRef(0)

  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)
  const [draft, setDraft] = useState<AssignmentPayload>(() => emptyDraft(undefined, 0))
  const [busy, setBusy] = useState(false)

  const periodStart = `${from} 00:00:00`
  const periodEnd = `${to} 23:59:59`

  const load = useCallback(() => {
    if (structureId <= 0) return
    const gen = ++generation.current
    setLoading(true)
    Promise.all([
      assignmentsApi.list(structureId, from, to),
      assignmentsApi.conflicts(structureId, from, to),
    ])
      .then(([list, conflictList]) => {
        if (gen !== generation.current) return
        setAssignments(list)
        setConflicts(conflictList)
      })
      .catch(() => {
        if (gen === generation.current) toast.error(i18n.t('assignments.errorLoad', 'Fehler beim Laden.'))
      })
      .finally(() => { if (gen === generation.current) setLoading(false) })
  }, [structureId, from, to, i18n])

  useEffect(() => {
    if (structureId <= 0) return
    customersApi.list(structureId).then(setCustomers).catch(() => {})
    locationsApi.list(structureId).then(setLocations).catch(() => {})
    skillsApi.list(structureId).then(setSkills).catch(() => {})
  }, [structureId])

  useEffect(() => {
    // A different period or structure invalidates the proposal: it belongs to the old window.
    setSolution(null)
    load()
  }, [load])

  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) { clearTimeout(pollTimer.current); pollTimer.current = null }
  }, [])

  useEffect(() => stopPolling, [stopPolling])

  function stopSolve() {
    const id = jobId.current
    if (id) shiftsApi.stopJob(id).catch(() => {})
    stopPolling()
    setSolving(false)
    jobId.current = null
  }

  async function handleSolve() {
    if (structureId <= 0) return
    setSolving(true)
    setSolution(null)
    const solvedStructureId = structureId
    try {
      const id = await shiftsApi.solve(solvedStructureId, periodStart, periodEnd)
      jobId.current = id
      const poll = async () => {
        try {
          const result = await shiftsApi.getJob(id)
          if (useAppStore.getState().currentStructure?.id !== solvedStructureId) return
          if (jobId.current !== id) return
          if (result.solverStatus === 'NOT_SOLVING') {
            const proposed = new Map<number, ProposedSlot>()
            for (const shift of result.shifts) {
              if (shift.context) continue
              proposed.set(shift.id, {
                employeeId: shift.employee?.id ?? null,
                employeeName: shift.employee?.fullName ?? '',
                version: shift.version ?? 0,
              })
            }
            setSolution(proposed)
            stopPolling()
            setSolving(false)
            jobId.current = null
            toast.success(t('assignments.solveDone', 'Optimierung abgeschlossen. Plan prüfen und übernehmen.'))
          } else {
            pollTimer.current = window.setTimeout(poll, 2000)
          }
        } catch {
          stopPolling()
          setSolving(false)
          jobId.current = null
          toast.error(t('assignments.errorPolling', 'Fehler beim Abruf des Solver-Status.'))
        }
      }
      pollTimer.current = window.setTimeout(poll, 2000)
    } catch {
      setSolving(false)
      toast.error(t('assignments.errorSolve', 'Optimierung konnte nicht gestartet werden.'))
    }
  }

  async function handleApprove() {
    if (!solution) return
    setSaving(true)
    try {
      await shiftsApi.saveAssignments(
        [...solution.entries()].map(([shiftId, slot]) => ({
          shift_id: shiftId,
          employee_id: slot.employeeId,
          version: slot.version,
        })),
        structureId,
        periodStart,
        periodEnd,
      )
      toast.success(t('assignments.saved', 'Plan übernommen.'))
      setSolution(null)
      load()
    } catch (err) {
      if (errorCode(err) === 'SHIFTS_CHANGED') {
        toast.error(t('assignments.changed',
          'Die Einsätze wurden während der Optimierung geändert. Nichts wurde gespeichert.'))
        setSolution(null)
        load()
      } else {
        toast.error(t('assignments.errorSave', 'Plan konnte nicht übernommen werden.'))
      }
    } finally {
      setSaving(false)
    }
  }

  function openNew() {
    const active = customers.filter(c => c.active)
    const customer = active[0]
    const locationId = customer?.locationIds[0] ?? locations[0]?.id ?? 0
    setEditId(null)
    setDraft(emptyDraft(customer, locationId))
    setModalOpen(true)
  }

  function openEdit(assignment: CustomerAssignment) {
    setEditId(assignment.id)
    setDraft({
      customerId: assignment.customerId,
      locationId: assignment.locationId,
      title: assignment.title,
      service: assignment.service,
      assignmentDate: assignment.assignmentDate,
      startTime: assignment.startTime,
      durationMinutes: assignment.durationMinutes,
      priority: assignment.priority,
      status: assignment.status,
      fixedTime: assignment.fixedTime,
      notes: assignment.notes,
      requirements: assignment.requirements.map(r => ({
        role: r.role, skillId: r.skillId ?? null, headcount: r.headcount,
      })),
    })
    setModalOpen(true)
  }

  async function handleSaveDraft() {
    setBusy(true)
    try {
      if (editId === null) await assignmentsApi.create(draft, structureId)
      else await assignmentsApi.update(editId, draft, structureId)
      toast.success(t('assignments.savedAssignment', 'Einsatz gespeichert.'))
      setModalOpen(false)
      setSolution(null)
      load()
    } catch (err) {
      toast.error(translateError(errorCode(err), (k, f) => t(k, f)))
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete(assignment: CustomerAssignment) {
    if (!window.confirm(t('assignments.confirmDelete', 'Diesen Einsatz wirklich löschen?'))) return
    try {
      await assignmentsApi.remove(assignment.id, structureId)
      setSolution(null)
      load()
    } catch {
      toast.error(t('assignments.errorDelete', 'Einsatz konnte nicht gelöscht werden.'))
    }
  }

  async function handlePin(assignment: CustomerAssignment, pinned: boolean) {
    try {
      await assignmentsApi.pin(assignment.id, pinned, structureId)
      load()
    } catch {
      toast.error(t('assignments.errorPin', 'Sperre konnte nicht geändert werden.'))
    }
  }

  const customerLocations = locations.filter(l => draft.customerId > 0 &&
    (customers.find(c => c.id === draft.customerId)?.locationIds.includes(l.id) ?? false))

  const openCount = assignments.reduce((sum, a) => sum + a.unfilledPositions, 0)
  const plannedUnfilled = solution
    ? [...solution.values()].filter(s => s.employeeId === null).length
    : 0

  function roleLabel(role: string): string {
    return role === 'PHYSICIAN'
      ? t('assignments.rolePhysician', 'Arzt/Ärztin')
      : t('assignments.roleAssistant', 'MFA')
  }

  function statusLabel(status: string): string {
    return t(`assignments.status.${status}`, status)
  }

  function slotFor(positionId: number, fallback: string, employeeId: number | null | undefined): string {
    if (solution) {
      const slot = solution.get(positionId)
      if (!slot) return '—'
      return slot.employeeId === null ? t('assignments.open', 'Offen') : slot.employeeName
    }
    return employeeId == null ? t('assignments.open', 'Offen') : fallback
  }

  return (
    <div className="container-fluid py-3">
      <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
        <h4 className="mb-0 me-3">
          <FontAwesomeIcon icon={faWandMagicSparkles} className="me-2" />
          {t('assignments.title', 'Kundeneinsätze')}
        </h4>
        <Form.Control type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 165 }} />
        <span>–</span>
        <Form.Control type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 165 }} />
        <Button variant="outline-primary" onClick={openNew}>
          <FontAwesomeIcon icon={faPlus} className="me-1" />
          {t('assignments.newAssignment', 'Neuer Einsatz')}
        </Button>
        <div className="ms-auto d-flex gap-2">
          {solving ? (
            <Button variant="warning" onClick={stopSolve}>
              <Spinner size="sm" animation="border" className="me-1" />
              {t('assignments.stopSolve', 'Optimierung stoppen')}
            </Button>
          ) : (
            <Button variant="primary" onClick={handleSolve} disabled={assignments.length === 0}>
              <FontAwesomeIcon icon={faWandMagicSparkles} className="me-1" />
              {t('assignments.solve', 'Optimieren')}
            </Button>
          )}
          <Button variant="success" onClick={handleApprove} disabled={!solution || saving}>
            <FontAwesomeIcon icon={faCheck} className="me-1" />
            {t('assignments.approve', 'Plan übernehmen')}
          </Button>
          {solution && (
            <Button variant="outline-secondary" onClick={() => setSolution(null)}>
              <FontAwesomeIcon icon={faRotateLeft} className="me-1" />
              {t('assignments.discard', 'Verwerfen')}
            </Button>
          )}
        </div>
      </div>

      {solution && (
        <Alert variant="info" className="py-2">
          {t('assignments.previewNotice',
            'Vorschlag der Optimierung – noch nicht gespeichert.')}
          {plannedUnfilled > 0 && <> {t('assignments.plannedOpen', 'Offene Positionen im Vorschlag:')} <strong>{plannedUnfilled}</strong></>}
        </Alert>
      )}

      {conflicts.length > 0 && (
        <Alert variant="danger" className="py-2">
          <strong className="me-2">{t('assignments.conflicts', 'Konflikte gesperrter Einsätze:')}</strong>
          <ul className="mb-0">
            {conflicts.map(c => (
              <li key={`${c.shiftId}-${c.conflictType}`}>
                {c.employeeName} – {t(`assignments.conflict.${c.conflictType}`, c.conflictType)}
                {c.detail ? ` (${c.detail})` : ''}
              </li>
            ))}
          </ul>
        </Alert>
      )}

      {openCount > 0 && !solution && (
        <Alert variant="warning" className="py-2">
          {t('assignments.openPositions', 'Offene Positionen:')} <strong>{openCount}</strong>
        </Alert>
      )}

      {loading ? (
        <div className="text-center py-5"><Spinner animation="border" /></div>
      ) : assignments.length === 0 ? (
        <Alert variant="secondary">{t('assignments.empty', 'Keine Einsätze in diesem Zeitraum.')}</Alert>
      ) : (
        <Table bordered hover responsive size="sm" className="align-middle">
          <thead>
            <tr>
              <th>{t('assignments.date', 'Datum')}</th>
              <th>{t('assignments.customer', 'Kunde')}</th>
              <th>{t('assignments.location', 'Ort')}</th>
              <th>{t('assignments.service', 'Leistung')}</th>
              <th>{t('assignments.window', 'Zeit')}</th>
              <th>{t('assignments.team', 'Team')}</th>
              <th>{t('assignments.statusLabel', 'Status')}</th>
              <th style={{ width: 130 }}>{t('assignments.actions', 'Aktionen')}</th>
            </tr>
          </thead>
          <tbody>
            {assignments.map(a => {
              const pinned = a.positions.length > 0 && a.positions.every(p => p.pinned)
              return (
                <tr key={a.id}>
                  <td className="text-nowrap">{a.assignmentDate}</td>
                  <td>
                    {a.customerName}
                    <div className="text-muted small">{a.title}</div>
                  </td>
                  <td>{a.locationName}</td>
                  <td>{a.service || '—'}</td>
                  <td className="text-nowrap">
                    {a.startTime}–{a.endTime}
                    <div className="text-muted small">{a.durationMinutes}′</div>
                  </td>
                  <td>
                    {a.positions.length === 0 && <span className="text-muted">{t('assignments.noPositions', 'Keine Positionen')}</span>}
                    {a.positions.map(p => {
                      const planned = solution?.get(p.id)
                      const name = slotFor(p.id, p.employeeName ?? '', p.employeeId)
                      const open = solution ? (planned?.employeeId ?? null) === null : p.employeeId === null
                      return (
                        <div key={p.id} className="small">
                          <Badge bg="light" text="dark" className="me-1">{roleLabel(p.role)}</Badge>
                          {p.skillName && <span className="text-muted me-1">{p.skillName}</span>}
                          {open
                            ? <Badge bg="warning" text="dark">{name}</Badge>
                            : <span>{name}{planned ? ` (${t('assignments.planned', 'geplant')})` : ''}</span>}
                        </div>
                      )
                    })}
                  </td>
                  <td>
                    <Badge bg={a.status === 'CANCELLED' ? 'secondary' : a.status === 'COMPLETED' ? 'dark' : 'primary'}>
                      {statusLabel(a.status)}
                    </Badge>
                    {a.fixedTime && <Badge bg="info" text="dark" className="ms-1">{t('assignments.fixedTime', 'Fixzeit')}</Badge>}
                  </td>
                  <td className="text-nowrap">
                    <Button size="sm" variant="outline-secondary" className="me-1" onClick={() => openEdit(a)}
                      title={t('assignments.edit', 'Bearbeiten')}>
                      <FontAwesomeIcon icon={faPenToSquare} />
                    </Button>
                    <Button size="sm" variant="outline-secondary" className="me-1"
                      onClick={() => handlePin(a, !pinned)}
                      title={pinned ? t('assignments.unlock', 'Entsperren') : t('assignments.lock', 'Sperren')}>
                      <FontAwesomeIcon icon={pinned ? faLock : faLockOpen} />
                    </Button>
                    <Button size="sm" variant="outline-danger" onClick={() => handleDelete(a)}
                      title={t('assignments.delete', 'Löschen')}>
                      <FontAwesomeIcon icon={faTrash} />
                    </Button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </Table>
      )}

      <Modal show={modalOpen} onHide={() => setModalOpen(false)} size="lg">
        <Modal.Header closeButton>
          <Modal.Title>
            {editId === null ? t('assignments.newAssignment', 'Neuer Einsatz') : t('assignments.edit', 'Bearbeiten')}
          </Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Row className="g-2">
            <Col md={6}>
              <Form.Label>{t('assignments.customer', 'Kunde')}</Form.Label>
              <Form.Select
                value={draft.customerId}
                onChange={e => {
                  const id = Number(e.target.value)
                  const customer = customers.find(c => c.id === id)
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
              <Form.Control value={draft.title} onChange={e => setDraft({ ...draft, title: e.target.value })} />
            </Col>
            <Col md={6}>
              <Form.Label>{t('assignments.service', 'Leistung')}</Form.Label>
              <Form.Control value={draft.service ?? ''} onChange={e => setDraft({ ...draft, service: e.target.value })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('assignments.date', 'Datum')}</Form.Label>
              <Form.Control type="date" value={draft.assignmentDate}
                onChange={e => setDraft({ ...draft, assignmentDate: e.target.value })} />
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
              <Form.Label>{t('assignments.statusLabel', 'Status')}</Form.Label>
              <Form.Select value={draft.status} onChange={e => setDraft({ ...draft, status: e.target.value })}>
                {STATUSES.map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}
              </Form.Select>
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
            <Col md={4} className="d-flex align-items-end">
              <Form.Check type="checkbox" id="fixed-time" checked={draft.fixedTime ?? false}
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
                <Form.Select value={requirement.role}
                  onChange={e => {
                    const next = [...draft.requirements]
                    next[index] = { ...requirement, role: e.target.value }
                    setDraft({ ...draft, requirements: next })
                  }}>
                  {ROLES.map(r => <option key={r} value={r}>{roleLabel(r)}</option>)}
                </Form.Select>
              </Col>
              <Col md={5}>
                <Form.Select value={requirement.skillId ?? ''}
                  onChange={e => {
                    const next = [...draft.requirements]
                    next[index] = { ...requirement, skillId: e.target.value ? Number(e.target.value) : null }
                    setDraft({ ...draft, requirements: next })
                  }}>
                  <option value="">{t('assignments.anyQualification', 'Keine Zusatzqualifikation')}</option>
                  {skills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </Form.Select>
              </Col>
              <Col md={2}>
                <Form.Control type="number" min={1} value={requirement.headcount}
                  onChange={e => {
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
          <Button variant="primary" onClick={handleSaveDraft} disabled={busy}>
            {busy && <Spinner size="sm" animation="border" className="me-1" />}
            {t('btn.save', 'Speichern')}
          </Button>
        </Modal.Footer>
      </Modal>
    </div>
  )
}

/** @brief Maps a backend validation code to a user-facing message. */
function translateError(code: string | null, t: (key: string, fallback: string) => string): string {
  switch (code) {
    case 'ASSIGNMENT_TITLE_REQUIRED': return t('assignments.errorTitle', 'Bezeichnung ist erforderlich.')
    case 'ASSIGNMENT_DATE_REQUIRED':
    case 'ASSIGNMENT_DATE_INVALID': return t('assignments.errorDate', 'Datum ist ungültig.')
    case 'ASSIGNMENT_START_REQUIRED':
    case 'ASSIGNMENT_START_INVALID': return t('assignments.errorStart', 'Beginn ist ungültig.')
    case 'ASSIGNMENT_DURATION_INVALID': return t('assignments.errorDuration', 'Dauer ist ungültig.')
    case 'ASSIGNMENT_STATUS_INVALID': return t('assignments.errorStatus', 'Status ist ungültig.')
    case 'ASSIGNMENT_CUSTOMER_UNKNOWN': return t('assignments.errorCustomer', 'Kunde nicht gefunden.')
    case 'ASSIGNMENT_LOCATION_UNKNOWN': return t('assignments.errorLocation', 'Ort nicht gefunden.')
    case 'ASSIGNMENT_LOCATION_CUSTOMER_MISMATCH':
      return t('assignments.errorLocationCustomer', 'Der Ort gehört nicht zu diesem Kunden.')
    case 'ASSIGNMENT_REQUIREMENT_REQUIRED':
      return t('assignments.errorRequirement', 'Mindestens eine Anforderung ist nötig.')
    case 'ASSIGNMENT_ROLE_INVALID': return t('assignments.errorRole', 'Rolle ist ungültig.')
    case 'ASSIGNMENT_HEADCOUNT_INVALID': return t('assignments.errorHeadcount', 'Anzahl ist ungültig.')
    case 'ASSIGNMENT_SKILL_UNKNOWN': return t('assignments.errorSkill', 'Qualifikation nicht gefunden.')
    case 'ASSIGNMENT_TOO_MANY_POSITIONS':
      return t('assignments.errorTooMany', 'Zu viele Positionen für einen Einsatz (max. 40).')
    default: return t('assignments.errorGeneric', 'Speichern fehlgeschlagen.')
  }
}
