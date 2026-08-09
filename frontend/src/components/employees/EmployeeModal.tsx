/**
 * @file EmployeeModal.tsx
 * @brief Modal for adding and editing an employee.
 *
 * @details
 * In "add" mode (employeeId=null), it automatically suggests the next available code
 * (`GET /demo-data/next-employee-code`).
 * In "edit" mode, it loads the existing data, including assigned skills.
 * It handles duplicate codes with a warning toast (HTTP 409).
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Modal, Button, Form, Spinner, Badge, CloseButton } from 'react-bootstrap'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { employeesApi, type Employee, type SkillOption } from '../../api/employees'
import { specialistsApi, type Specialist } from '../../api/specialists'
import { affinityApi, AFFINITY_AVOID, AFFINITY_INCOMPATIBLE, type SpecialistAffinity } from '../../api/affinity'
import { errorCode, errorStatus } from '../../api/client'
import { backendErrorText } from '../../i18n/backendErrors'

/**
 * @brief EmployeeModal component properties.
 */
interface Props {
  show: boolean
  /** @brief ID of the employee to edit, or `null` when adding one. */
  employeeId: number | null
  structureId: number
  onClose: () => void
  onSaved: () => void
}

const EMPTY_FORM = { firstName: '', lastName: '', email: '', code: '', skills: [] as number[], active: true }

/** @brief Removes leading whitespace (for values pasted with whitespace in front). */
const stripLeading = (s: string) => s.replace(/^\s+/, '')
/** @brief Removes all whitespace (an email address cannot contain any). */
const stripSpaces = (s: string) => s.replace(/\s+/g, '')

export default function EmployeeModal({ show, employeeId, structureId, onClose, onSaved }: Props) {
  const { t, i18n } = useTranslation()
  const isEdit = employeeId !== null
  const [form, setForm] = useState(EMPTY_FORM)
  const [allSkills, setAllSkills] = useState<SkillOption[]>([])
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)
  // Employee↔specialist compatibility (edit mode only: the employee ID is required)
  const [specialists, setSpecialists] = useState<Specialist[]>([])
  const [affinities, setAffinities] = useState<SpecialistAffinity[]>([])
  const [selSpecialist, setSelSpecialist] = useState<number | ''>('')
  const sessionIdentity = `${show}:${structureId}:${employeeId ?? 'new'}`
  const currentSession = useRef(sessionIdentity)
  const operationInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentSession.current = sessionIdentity
    operationInFlight.current = null
    setSaving(false)
  }, [sessionIdentity])

  // Load the available skills when the modal opens
  useEffect(() => {
    if (!show) return
    let current = true
    employeesApi.allSkills(structureId).then(skills => { if (current) setAllSkills(skills) }).catch(() => {})
    return () => { current = false }
  }, [show, structureId])

  // Load the structure's active specialists and the employee's relationships (edit mode only)
  useEffect(() => {
    if (!show || !isEdit) {
      setSpecialists([]); setAffinities([]); setSelSpecialist('')
      return
    }
    let current = true
    specialistsApi.list(structureId)
      .then(list => { if (current) setSpecialists(list.filter(s => s.active !== false)) })
      .catch(() => {})
    affinityApi.byOperator(employeeId!, structureId).then(items => { if (current) setAffinities(items) }).catch(() => {})
    setSelSpecialist('')
    return () => { current = false }
  }, [show, isEdit, employeeId, structureId])

  // Load employee data when editing, or suggest a code when adding
  useEffect(() => {
    if (!show) return
    let current = true
    if (isEdit) {
      setLoading(true)
      employeesApi.get(employeeId!, structureId)
        .then((emp: Employee) => {
          if (!current) return
          setForm({
            firstName: emp.firstName,
            lastName: emp.lastName,
            email: emp.email ?? '',
            code: emp.code,
            skills: (emp.skills ?? []).filter(s => s.used).map(s => s.id),
            active: emp.active !== false,
          })
        })
        .catch(() => { if (current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) })
        .finally(() => { if (current) setLoading(false) })
    } else {
      setLoading(false)
      setForm(EMPTY_FORM)
      employeesApi.nextCode()
        .then(({ code }) => { if (current) setForm(f => ({ ...f, code })) })
        .catch(() => {})
    }
    return () => { current = false }
  }, [show, employeeId, isEdit, structureId, i18n])

  function toggleSkill(id: number) {
    setForm(f => ({
      ...f,
      skills: f.skills.includes(id) ? f.skills.filter(s => s !== id) : [...f.skills, id],
    }))
  }

  /** @brief Adds (or updates) the relationship with the selected specialist. */
  function addAffinity(type: number) {
    if (selSpecialist === '') return
    setAffinities(a => [...a.filter(x => x.specialistId !== selSpecialist), { specialistId: selSpecialist, type }])
    setSelSpecialist('')
  }

  /** @brief Removes the relationship, implicitly returning the pair to neutral. */
  function removeAffinity(specialistId: number) {
    setAffinities(a => a.filter(x => x.specialistId !== specialistId))
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (operationInFlight.current) return
    const email = form.email.trim()
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      toast.error(t('validation.emailInvalid', 'Inserisci un indirizzo email valido.'))
      return
    }
    const operation = Symbol('employee-save')
    operationInFlight.current = operation
    setSaving(true)
    const submitSession = sessionIdentity
    const payload = {
      id: isEdit ? employeeId! : undefined,
      firstName: form.firstName.trim(),
      lastName: form.lastName.trim(),
      email,
      code: form.code.trim(),
      skills: allSkills.filter(s => form.skills.includes(s.id)).map(s => ({ id: s.id, name: s.name })),
      active: form.active,
    }
    try {
      if (isEdit) {
        await employeesApi.update(employeeId!, { ...payload, affinities }, structureId)
      } else {
        await employeesApi.add(payload, structureId)
      }
      if (currentSession.current !== submitSession) return
      toast.success(isEdit ? t('toast.employeeUpdated', 'Operatore aggiornato!') : t('toast.employeeAdded', 'Operatore aggiunto!'))
      onSaved()
      onClose()
    } catch (err: unknown) {
      // The backend identifies WHICH field it rejected; showing it avoids a generic message
      // that forces the user to guess what needs fixing.
      if (currentSession.current !== submitSession) return
      const specific = backendErrorText(errorCode(err), t)
      if (specific) {
        toast.error(specific)
      } else if (errorStatus(err) === 409) {
        toast.error(t('toast.employeeCodeDuplicate', 'Codice operatore già in uso. Scegline uno diverso.'))
      } else {
        toast.error(isEdit ? t('toast.errorEdit', 'Errore durante la modifica.') : t('toast.errorAdd', "Errore durante l'aggiunta."))
      }
    } finally {
      if (operationInFlight.current === operation) {
        operationInFlight.current = null
        if (currentSession.current === submitSession) setSaving(false)
      }
    }
  }

  return (
    <Modal show={show} onHide={() => { if (!saving) onClose() }} centered>
      <Form onSubmit={handleSubmit}>
        <Modal.Header closeButton={!saving}>
          <Modal.Title>{isEdit ? t('modal.editEmployee', 'Modifica Operatore') : t('modal.addEmployee', 'Aggiungi Operatore')}</Modal.Title>
        </Modal.Header>

        <Modal.Body>
          <fieldset disabled={saving} className="border-0 p-0 m-0 w-100">
          {loading ? (
            <div className="text-center py-3"><Spinner /></div>
          ) : (
            <>
              <Form.Group className="mb-3">
                <Form.Label>{t('label.firstName', 'Nome')}</Form.Label>
                <Form.Control
                  required
                  value={form.firstName}
                  onChange={e => setForm(f => ({ ...f, firstName: stripLeading(e.target.value) }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Label>{t('label.lastName', 'Cognome')}</Form.Label>
                <Form.Control
                  required
                  value={form.lastName}
                  onChange={e => setForm(f => ({ ...f, lastName: stripLeading(e.target.value) }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Label>{t('label.code', 'Codice')}</Form.Label>
                <Form.Control
                  required
                  value={form.code}
                  onChange={e => setForm(f => ({ ...f, code: stripLeading(e.target.value) }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Label>{t('label.email', 'Email')}</Form.Label>
                <Form.Control
                  type="email"
                  value={form.email}
                  placeholder={t('placeholder.email', 'nome@esempio.it')}
                  onChange={e => setForm(f => ({ ...f, email: stripSpaces(e.target.value) }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Check
                  type="switch"
                  id="emp-active"
                  label={t('label.active', 'Attivo')}
                  checked={form.active}
                  onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                />
                <Form.Text className="text-muted">
                  {t('hint.inactiveEmployee', 'Se disattivato non compare in Gestione Turni e il solver non gli assegna turni.')}
                </Form.Text>
              </Form.Group>

              {allSkills.length > 0 && (
                <Form.Group>
                  <Form.Label>{t('label.skills', 'Competenze')}</Form.Label>
                  <div className="d-flex flex-wrap gap-2">
                    {allSkills.map(skill => (
                      <Form.Check
                        key={skill.id}
                        type="checkbox"
                        id={`skill-${skill.id}`}
                        label={t('skill.' + skill.id, skill.name)}
                        checked={form.skills.includes(skill.id)}
                        onChange={() => toggleSkill(skill.id)}
                      />
                    ))}
                  </div>
                </Form.Group>
              )}

              {/* Employee↔specialist compatibility: exception list (non-neutral relationships only) */}
              {isEdit && specialists.length > 0 && (
                <Form.Group className="mt-3">
                  <Form.Label>{t('label.specialistCompatibility', 'Compatibilità con Specialisti')}</Form.Label>

                  {affinities.length > 0 && (
                    <div className="mb-2">
                      {affinities.map(a => {
                        const sp = specialists.find(s => s.id === a.specialistId)
                        const incompatible = a.type === AFFINITY_INCOMPATIBLE
                        return (
                          <div key={a.specialistId} className="d-flex align-items-center gap-2 mb-1">
                            <Badge bg={incompatible ? 'danger' : 'warning'} text={incompatible ? undefined : 'dark'}>
                              {incompatible ? '✗ ' + t('affinity.incompatible', 'Incompatibile') : '⚠ ' + t('affinity.avoid', 'Da evitare')}
                            </Badge>
                            <span className="flex-grow-1">
                              {sp ? `${sp.lastName} ${sp.firstName}` : `#${a.specialistId}`}
                            </span>
                            <CloseButton
                              title={t('btn.delete', 'Elimina')}
                              onClick={() => removeAffinity(a.specialistId)}
                            />
                          </div>
                        )
                      })}
                    </div>
                  )}

                  <div className="d-flex gap-2">
                    <Form.Select
                      size="sm"
                      value={selSpecialist}
                      onChange={e => setSelSpecialist(e.target.value ? Number(e.target.value) : '')}
                    >
                      <option value="">{t('affinity.selectSpecialist', 'Seleziona specialista…')}</option>
                      {specialists
                        .filter(s => !affinities.some(a => a.specialistId === s.id))
                        .map(s => (
                          <option key={s.id} value={s.id}>{s.lastName} {s.firstName}</option>
                        ))}
                    </Form.Select>
                    <Button
                      size="sm"
                      variant="outline-warning"
                      className="text-nowrap"
                      disabled={selSpecialist === ''}
                      onClick={() => addAffinity(AFFINITY_AVOID)}
                    >
                      ⚠ {t('affinity.avoid', 'Da evitare')}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline-danger"
                      className="text-nowrap"
                      disabled={selSpecialist === ''}
                      onClick={() => addAffinity(AFFINITY_INCOMPATIBLE)}
                    >
                      ✗ {t('affinity.incompatible', 'Incompatibile')}
                    </Button>
                  </div>
                  <Form.Text className="text-muted">
                    {t('hint.incompatibleSpecialist', '"Incompatibile" vieta l\'assegnazione: può lasciare turni scoperti.')}
                  </Form.Text>
                </Form.Group>
              )}
            </>
          )}
          </fieldset>
        </Modal.Body>

        <Modal.Footer>
          <Button variant="secondary" onClick={onClose} disabled={saving}>
            {t('btn.cancel', 'Annulla')}
          </Button>
          <Button type="submit" variant="primary" disabled={saving || loading}>
            {saving ? <Spinner size="sm" /> : isEdit ? t('btn.save', 'Salva') : t('btn.add', 'Aggiungi')}
          </Button>
        </Modal.Footer>
      </Form>
    </Modal>
  )
}
