/**
 * @file TemplateShiftModal.tsx
 * @brief Modal for adding/editing a template shift (weekly pattern).
 *
 * @details
 * Similar to ShiftModal but without an absolute date: select a day of the week
 * (0=Mon … 6=Sun) and a time of day (`time` input). Location and skills work as in ShiftModal.
 * Saves/deletes through the template-shift API.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Modal, Button, Form, Row, Col, Spinner } from 'react-bootstrap'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { shiftsApi } from '../../api/shifts'
import { locationsApi } from '../../api/locations'
import { templatesApi, type ShiftTemplate } from '../../api/templates'
import type { SkillOption } from '../../api/employees'

interface Props {
  show: boolean
  /** @brief Template to edit, or `null` to add one. */
  template: ShiftTemplate | null
  structureId: number
  /** @brief Header of the saved template that owns this row (for the saved-template editor). */
  headerId?: number
  /** @brief Preselected weekday when adding (click on an empty area). */
  prefillDay?: number
  /** @brief Prepopulated start time when adding ("HH:mm"). */
  prefillStartTime?: string
  /** @brief Preselected location when adding. */
  prefillLocationId?: number
  onClose: () => void
  onSaved: () => void
  onDeleted?: () => void
}

const EMPTY = { dayOfWeek: 0, startTime: '', endTime: '', locationId: '', required: [] as number[], optional: [] as number[] }

/** @brief "HH:mm:ss" or "HH:mm" → "HH:mm" for the time input. */
function toTimeInput(v: string): string {
  return v ? v.slice(0, 5) : ''
}

export default function TemplateShiftModal({
  show, template, structureId, headerId, prefillDay, prefillStartTime, prefillLocationId,
  onClose, onSaved, onDeleted,
}: Props) {
  const { t, i18n } = useTranslation()
  const isEdit = template !== null
  const [form, setForm] = useState(EMPTY)
  const [locations, setLocations] = useState<{ id: number; name: string }[]>([])
  const [allSkills, setAllSkills] = useState<{ required: SkillOption[]; optional: SkillOption[] }>({ required: [], optional: [] })
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // true when skill loading fails (catalog on mount or location skills):
  // when adding, blocks saving to avoid persisting a shift without requiredSkills.
  const [skillsLoadFailed, setSkillsLoadFailed] = useState(false)
  const locationRequest = useRef(0)
  const sessionIdentity = `${show}:${structureId}:${template?.id ?? 'new'}:${headerId ?? 'none'}`
  const currentSession = useRef(sessionIdentity)
  const operationInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentSession.current = sessionIdentity
    operationInFlight.current = null
    setSaving(false)
    setDeleting(false)
  }, [sessionIdentity])

  const DAYS = [
    t('day.mon', 'Lunedì'), t('day.tue', 'Martedì'), t('day.wed', 'Mercoledì'),
    t('day.thu', 'Giovedì'), t('day.fri', 'Venerdì'), t('day.sat', 'Sabato'), t('day.sun', 'Domenica'),
  ]

  // Load locations + skills on mount
  useEffect(() => {
    if (!show) return
    let current = true
    setSkillsLoadFailed(false)
    Promise.all([locationsApi.list(structureId), shiftsApi.skillsForNew(structureId)])
      .then(([locs, skills]) => {
        if (!current) return
        setLocations(locs)
        setAllSkills({ required: skills.requiredSkills ?? [], optional: skills.optionalSkills ?? [] })
      })
      .catch(() => {
        if (!current) return
        // Skill catalog not loaded → checkboxes are not shown: report the error and block saving
        // instead of silently allowing a shift without skills to be saved.
        setSkillsLoadFailed(true)
        toast.error(i18n.t('toast.locationSkillsLoadFailed', 'Impossibile caricare le skill della sede: verificale prima di salvare.'))
      })
    return () => { current = false }
  }, [show, structureId, i18n])

  // Populate the form from the template (edit) or prefill (add)
  useEffect(() => {
    if (!show) return
    const requestRef = locationRequest
    locationRequest.current++
    if (isEdit && template) {
      setForm({
        dayOfWeek: template.day_of_week,
        startTime: toTimeInput(template.start_time),
        endTime: toTimeInput(template.end_time),
        locationId: String(template.location_id),
        required: (template.requiredSkills ?? []).map(s => s.id),
        optional: (template.optionalSkills ?? []).map(s => s.id),
      })
    } else {
      const start = prefillStartTime ?? ''
      // default end: +1 hour, without crossing midnight
      let end = ''
      if (start) {
        const [h, m] = start.split(':').map(Number)
        end = `${String(Math.min(h + 1, 23)).padStart(2, '0')}:${String(m).padStart(2, '0')}`
      }
      setForm({
        ...EMPTY,
        dayOfWeek: prefillDay ?? 0,
        startTime: start,
        endTime: end,
        locationId: prefillLocationId ? String(prefillLocationId) : '',
      })
      if (prefillLocationId) {
        const request = ++locationRequest.current
        locationsApi.get(prefillLocationId, structureId)
          .then(loc => {
            if (request !== locationRequest.current) return
            setSkillsLoadFailed(false)
            setForm(f => ({
              ...f,
              required: (loc.requiredSkills ?? []).filter(s => s.used).map(s => s.id),
              optional: (loc.optionalSkills ?? []).filter(s => s.used).map(s => s.id),
            }))
          })
          .catch(() => {
            if (request !== locationRequest.current) return
            setSkillsLoadFailed(true)
            toast.error(i18n.t('toast.locationSkillsLoadFailed', 'Impossibile caricare le skill della sede: verificale prima di salvare.'))
          })
      }
    }
    return () => { requestRef.current++ }
  }, [show, template, isEdit, prefillDay, prefillStartTime, prefillLocationId, structureId, i18n])

  async function handleLocationChange(locId: string) {
    const request = ++locationRequest.current
    setForm(f => ({ ...f, locationId: locId, required: [], optional: [] }))
    if (!locId || isEdit) return
    try {
      const loc = await locationsApi.get(parseInt(locId), structureId)
      if (request !== locationRequest.current) return
      // Location skills loaded: the form now has consistent requiredSkills → enable saving.
      setSkillsLoadFailed(false)
      setForm(f => ({
        ...f,
        required: (loc.requiredSkills ?? []).filter(s => s.used).map(s => s.id),
        optional: (loc.optionalSkills ?? []).filter(s => s.used).map(s => s.id),
      }))
    } catch {
      if (request !== locationRequest.current) return
      // If location loading fails, skills are NOT preselected: notify the user AND block saving,
      // instead of silently saving a shift without requiredSkills (the solver could assign anyone).
      setSkillsLoadFailed(true)
      toast.error(t('toast.locationSkillsLoadFailed', 'Impossibile caricare le skill della sede: verificale prima di salvare.'))
    }
  }

  function toggleSkill(field: 'required' | 'optional', id: number) {
    setForm(f => ({
      ...f,
      [field]: f[field].includes(id) ? f[field].filter(s => s !== id) : [...f[field], id],
    }))
  }

  function validate(): boolean {
    if (!isEdit && skillsLoadFailed) { toast.error(t('toast.locationSkillsLoadFailed', 'Impossibile caricare le skill della sede: verificale prima di salvare.')); return false }
    if (!form.locationId) { toast.error(t('toast.selectLocation', 'Seleziona una sede.')); return false }
    if (!form.startTime || !form.endTime) { toast.error(t('toast.insertStartEnd', 'Inserisci orario di inizio e fine.')); return false }
    if (form.endTime <= form.startTime) { toast.error(t('toast.endAfterStart', "L'orario di fine deve essere dopo l'inizio.")); return false }
    return true
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (operationInFlight.current) return
    if (!validate()) return
    const operation = Symbol('template-save')
    operationInFlight.current = operation
    setSaving(true)
    const submitSession = sessionIdentity
    const payload = {
      day_of_week: form.dayOfWeek,
      start_time: form.startTime + ':00',
      end_time: form.endTime + ':00',
      location_id: parseInt(form.locationId),
      header_id: headerId,
      requiredSkills: form.required.map(id => ({ id })),
      optionalSkills: form.optional.map(id => ({ id })),
    }
    try {
      if (isEdit && template) {
        await templatesApi.update(template.id, payload, structureId)
      } else {
        await templatesApi.add(structureId, payload)
      }
      if (currentSession.current !== submitSession) return
      toast.success(isEdit ? t('toast.templateUpdated', 'Template aggiornato!') : t('toast.templateAdded', 'Template aggiunto!'))
      onSaved()
      onClose()
    } catch {
      if (currentSession.current === submitSession) toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
    } finally {
      if (operationInFlight.current === operation) {
        operationInFlight.current = null
        if (currentSession.current === submitSession) setSaving(false)
      }
    }
  }

  async function handleDelete() {
    if (!template) return
    if (operationInFlight.current) return
    const operation = Symbol('template-delete')
    operationInFlight.current = operation
    setDeleting(true)
    const deleteSession = sessionIdentity
    try {
      await templatesApi.delete(template.id, structureId)
      if (currentSession.current !== deleteSession) return
      toast.success(t('toast.templateDeleted', 'Template eliminato.'))
      onDeleted?.()
      onClose()
    } catch {
      if (currentSession.current === deleteSession) toast.error(t('toast.errorDelete', "Errore durante l'eliminazione."))
    } finally {
      if (operationInFlight.current === operation) {
        operationInFlight.current = null
        if (currentSession.current === deleteSession) setDeleting(false)
      }
    }
  }

  return (
    <Modal show={show} onHide={() => { if (!saving && !deleting) onClose() }} centered size="lg">
      <Form onSubmit={handleSubmit}>
        <Modal.Header closeButton={!saving && !deleting}>
          <Modal.Title>{isEdit ? t('modal.editTemplate', 'Modifica turno-template') : t('modal.addTemplate', 'Aggiungi turno-template')}</Modal.Title>
        </Modal.Header>

        <Modal.Body>
          <fieldset disabled={saving || deleting} className="border-0 p-0 m-0 w-100">
          <Row className="mb-3">
            <Col md={5}>
              <Form.Label>{t('label.dayOfWeek', 'Giorno')}</Form.Label>
              <Form.Select
                value={form.dayOfWeek}
                onChange={e => setForm(f => ({ ...f, dayOfWeek: parseInt(e.target.value) }))}
              >
                {DAYS.map((d, i) => <option key={i} value={i}>{d}</option>)}
              </Form.Select>
            </Col>
            <Col md={7}>
              <Form.Label>{t('label.location', 'Sede')}</Form.Label>
              <Form.Select required value={form.locationId} onChange={e => handleLocationChange(e.target.value)}>
                <option value="">{t('placeholder.selectLocation', '— Seleziona sede —')}</option>
                {locations.map(l => <option key={l.id} value={l.id}>{t('location.' + l.id, l.name)}</option>)}
              </Form.Select>
            </Col>
          </Row>

          <Row className="mb-3">
            <Col>
              <Form.Label>{t('label.start', 'Inizio')}</Form.Label>
              <Form.Control type="time" required value={form.startTime}
                onChange={e => setForm(f => ({ ...f, startTime: e.target.value }))} />
            </Col>
            <Col>
              <Form.Label>{t('label.end', 'Fine')}</Form.Label>
              <Form.Control type="time" required value={form.endTime}
                onChange={e => setForm(f => ({ ...f, endTime: e.target.value }))} />
            </Col>
          </Row>

          {allSkills.required.length > 0 && (
            <Row>
              <Col>
                <Form.Label className="fw-semibold">{t('label.requiredSkills', 'Comp. richieste')}</Form.Label>
                <div className="d-flex flex-wrap gap-2">
                  {allSkills.required.map(s => (
                    <Form.Check key={s.id} type="checkbox" id={`treq-${s.id}`} label={s.name}
                      checked={form.required.includes(s.id)} onChange={() => toggleSkill('required', s.id)} />
                  ))}
                </div>
              </Col>
              <Col>
                <Form.Label className="fw-semibold">{t('label.optionalSkills', 'Comp. opzionali')}</Form.Label>
                <div className="d-flex flex-wrap gap-2">
                  {allSkills.optional.map(s => (
                    <Form.Check key={s.id} type="checkbox" id={`topt-${s.id}`} label={s.name}
                      checked={form.optional.includes(s.id)} onChange={() => toggleSkill('optional', s.id)} />
                  ))}
                </div>
              </Col>
            </Row>
          )}
          </fieldset>
        </Modal.Body>

        <Modal.Footer className="justify-content-between">
          {isEdit && (
            <Button variant="outline-danger" onClick={handleDelete} disabled={deleting || saving}>
              {deleting ? <Spinner size="sm" /> : t('btn.delete', 'Elimina')}
            </Button>
          )}
          <div className="d-flex gap-2 ms-auto">
            <Button variant="secondary" onClick={onClose} disabled={saving || deleting}>{t('btn.cancel', 'Annulla')}</Button>
            <Button type="submit" variant="primary" disabled={saving || (!isEdit && skillsLoadFailed)}>
              {saving ? <Spinner size="sm" /> : isEdit ? t('btn.save', 'Salva') : t('btn.add', 'Aggiungi')}
            </Button>
          </div>
        </Modal.Footer>
      </Form>
    </Modal>
  )
}
