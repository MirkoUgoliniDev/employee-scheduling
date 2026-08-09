/**
 * @file ShiftModal.tsx
 * @brief Modal for adding and editing a shift.
 *
 * @details
 * In "add" mode:
 * - `prefillStart`: prepopulates start/end (click on an empty timeline slot)
 * - `prefillLocationId`: preselects the location and loads its skills
 * - Changing the location automatically updates the preselected skills
 *
 * In "edit" mode:
 * - Loads the shift from `GET /demo-data/editshift/{id}`
 * - Shows the "Delete" button in the footer
 *
 * Client-side validation:
 * - Location is required
 * - Start and end are required
 * - End > start
 * - A shift cannot cross midnight (business rule)
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Modal, Button, Form, Row, Col, Spinner } from 'react-bootstrap'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { shiftsApi } from '../../api/shifts'
import { locationsApi } from '../../api/locations'
import type { SkillOption } from '../../api/employees'

/**
 * @brief ShiftModal component props.
 */
interface Props {
  show: boolean
  /** @brief ID of the shift to edit, or `null` to add one. */
  shiftId: number | null
  prefillStart?: string         // for clicks on empty slots
  prefillLocationId?: number
  structureId: number
  onClose: () => void
  onSaved: () => void
  onDeleted?: () => void
}

const EMPTY = { locationId: '', start: '', end: '', required: [] as number[], optional: [] as number[] }

function toInput(iso: string) {
  if (!iso) return ''
  return iso.replace(' ', 'T').slice(0, 16)
}

export default function ShiftModal({
  show, shiftId, prefillStart, prefillLocationId,
  structureId, onClose, onSaved, onDeleted,
}: Props) {
  const { t, i18n } = useTranslation()
  const isEdit = shiftId !== null
  const [form, setForm] = useState(EMPTY)
  const [locations, setLocations] = useState<{ id: number; name: string }[]>([])
  const [allSkills, setAllSkills] = useState<{ required: SkillOption[]; optional: SkillOption[] }>({ required: [], optional: [] })
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // true when skill loading fails (catalog on mount or location skills):
  // when adding, blocks saving to avoid persisting a shift without requiredSkills.
  const [skillsLoadFailed, setSkillsLoadFailed] = useState(false)
  const locationRequest = useRef(0)
  const sessionIdentity = `${show}:${structureId}:${shiftId ?? 'new'}`
  const currentSession = useRef(sessionIdentity)
  const operationInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentSession.current = sessionIdentity
    operationInFlight.current = null
    setSaving(false)
    setDeleting(false)
  }, [sessionIdentity])

  // Load locations and skills on mount
  useEffect(() => {
    if (!show) return
    let current = true
    setSkillsLoadFailed(false)
    Promise.all([
      locationsApi.list(structureId),
      shiftsApi.skillsForNew(structureId),
    ]).then(([locs, skills]) => {
      if (!current) return
      setLocations(locs)
      setAllSkills({ required: skills.requiredSkills ?? [], optional: skills.optionalSkills ?? [] })
    }).catch(() => {
      if (!current) return
      // Skill catalog not loaded → checkboxes are not shown: report the error and block saving
      // instead of silently allowing a shift without skills to be saved.
      setSkillsLoadFailed(true)
      toast.error(i18n.t('toast.locationSkillsLoadFailed', 'Impossibile caricare le skill della sede: verificale prima di salvare.'))
    })
    return () => { current = false }
  }, [show, structureId, i18n])

  // Load shift data in edit mode / prepopulate in add mode
  useEffect(() => {
    if (!show) return
    const requestRef = locationRequest
    let current = true
    locationRequest.current++
    if (isEdit) {
      setLoading(true)
      shiftsApi.get(shiftId!, structureId)
        .then(({ shift, locations: locs }) => {
          if (!current) return
          setLocations(locs)
          setForm({
            locationId: String(shift.location_id),
            start: toInput(shift.start),
            end: toInput(shift.end),
            required: (shift.requiredSkills ?? []).filter(s => s.used).map(s => s.id),
            optional: (shift.optionalSkills ?? []).filter(s => s.used).map(s => s.id),
          })
        })
        .catch(() => { if (current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) })
        .finally(() => { if (current) setLoading(false) })
    } else {
      setLoading(false)
      // Add mode: prepopulate start/end and location
      let start = '', end = ''
      if (prefillStart) {
        const d = new Date(prefillStart)
        const pad = (n: number) => String(n).padStart(2, '0')
        const dateStr = `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`
        start = `${dateStr}T${pad(d.getHours())}:00`
        const endD = new Date(d.getTime() + 8 * 3600 * 1000)
        const endDateStr = `${endD.getFullYear()}-${pad(endD.getMonth()+1)}-${pad(endD.getDate())}`
        end = endDateStr === dateStr
          ? `${dateStr}T${pad(endD.getHours())}:00`
          : `${dateStr}T23:00`
      }
      setForm({ ...EMPTY, locationId: prefillLocationId ? String(prefillLocationId) : '', start, end })
      if (prefillLocationId) {
        const request = ++locationRequest.current
        locationsApi.get(prefillLocationId, structureId)
          .then(loc => {
            if (!current || request !== locationRequest.current) return
            setSkillsLoadFailed(false)
            setForm(f => ({
              ...f,
              required: (loc.requiredSkills ?? []).filter(s => s.used).map(s => s.id),
              optional: (loc.optionalSkills ?? []).filter(s => s.used).map(s => s.id),
            }))
          })
          .catch(() => {
            if (!current || request !== locationRequest.current) return
            setSkillsLoadFailed(true)
            toast.error(i18n.t('toast.locationSkillsLoadFailed', 'Impossibile caricare le skill della sede: verificale prima di salvare.'))
          })
      }
    }
    return () => { current = false; requestRef.current++ }
  }, [show, shiftId, isEdit, prefillStart, prefillLocationId, structureId, i18n])

  // When the location changes in add mode → preselect the location's skills
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
    if (!form.start || !form.end) { toast.error(t('toast.insertStartEnd', 'Inserisci orario di inizio e fine.')); return false }
    const s = new Date(form.start), e = new Date(form.end)
    if (isNaN(s.getTime()) || isNaN(e.getTime())) { toast.error(t('toast.invalidDateFormat', 'Formato data/ora non valido.')); return false }
    if (e <= s) { toast.error(t('toast.endAfterStart', "L'orario di fine deve essere dopo l'inizio.")); return false }
    if (s.toDateString() !== e.toDateString()) { toast.error(t('toast.shiftNoCrossDay', 'Il turno non può coprire due giorni.')); return false }
    return true
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (operationInFlight.current) return
    if (!validate()) return
    const operation = Symbol('shift-save')
    operationInFlight.current = operation
    setSaving(true)
    const submitSession = sessionIdentity
    const payload = {
      location_id: parseInt(form.locationId),
      start: form.start + ':00',   // datetime-local returns "2024-12-22T02:00" → append seconds
      end: form.end + ':00',
      requiredSkills: form.required.map(id => ({ id })),
      optionalSkills: form.optional.map(id => ({ id })),
    }
    try {
      if (isEdit) {
        await shiftsApi.update(shiftId!, payload, structureId)
      } else {
        await shiftsApi.add(payload, structureId)
      }
      if (currentSession.current !== submitSession) return
      toast.success(isEdit ? t('toast.shiftUpdated', 'Turno aggiornato!') : t('toast.shiftAdded', 'Turno aggiunto!'))
      onSaved()
      onClose()
    } catch {
      if (currentSession.current === submitSession) toast.error(isEdit ? t('toast.errorEdit', 'Errore durante la modifica.') : t('toast.errorAdd', "Errore durante l'aggiunta."))
    } finally {
      if (operationInFlight.current === operation) {
        operationInFlight.current = null
        if (currentSession.current === submitSession) setSaving(false)
      }
    }
  }

  async function handleDelete() {
    if (!shiftId) return
    if (operationInFlight.current) return
    const operation = Symbol('shift-delete')
    operationInFlight.current = operation
    setDeleting(true)
    const deleteSession = sessionIdentity
    try {
      await shiftsApi.delete(shiftId, structureId)
      if (currentSession.current !== deleteSession) return
      toast.success(t('toast.shiftDeleted', 'Turno eliminato.'))
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
          <Modal.Title>{isEdit ? t('modal.editShift', 'Modifica Turno') : t('modal.addShift', 'Aggiungi Turno')}</Modal.Title>
        </Modal.Header>

        <Modal.Body>
          <fieldset disabled={saving || deleting} className="border-0 p-0 m-0 w-100">
          {loading ? (
            <div className="text-center py-3"><Spinner /></div>
          ) : (
            <>
              <Form.Group className="mb-3">
                <Form.Label>{t('label.location', 'Sede')}</Form.Label>
                <Form.Select
                  required
                  value={form.locationId}
                  onChange={e => handleLocationChange(e.target.value)}
                >
                  <option value="">{t('placeholder.selectLocation', '— Seleziona sede —')}</option>
                  {locations.map(l => (
                    <option key={l.id} value={l.id}>{t('location.' + l.id, l.name)}</option>
                  ))}
                </Form.Select>
              </Form.Group>

              <Row className="mb-3">
                <Col>
                  <Form.Label>{t('label.start', 'Inizio')}</Form.Label>
                  <Form.Control
                    type="datetime-local"
                    required
                    value={form.start}
                    onChange={e => setForm(f => ({ ...f, start: e.target.value }))}
                  />
                </Col>
                <Col>
                  <Form.Label>{t('label.end', 'Fine')}</Form.Label>
                  <Form.Control
                    type="datetime-local"
                    required
                    value={form.end}
                    onChange={e => setForm(f => ({ ...f, end: e.target.value }))}
                  />
                </Col>
              </Row>

              {allSkills.required.length > 0 && (
                <Row>
                  <Col>
                    <Form.Label className="fw-semibold">{t('label.requiredSkills', 'Comp. richieste')}</Form.Label>
                    <div className="d-flex flex-wrap gap-2">
                      {allSkills.required.map(s => (
                        <Form.Check
                          key={s.id} type="checkbox"
                          id={`req-${s.id}`} label={t('skill.' + s.id, s.name)}
                          checked={form.required.includes(s.id)}
                          onChange={() => toggleSkill('required', s.id)}
                        />
                      ))}
                    </div>
                  </Col>
                  <Col>
                    <Form.Label className="fw-semibold">{t('label.optionalSkills', 'Comp. opzionali')}</Form.Label>
                    <div className="d-flex flex-wrap gap-2">
                      {allSkills.optional.map(s => (
                        <Form.Check
                          key={s.id} type="checkbox"
                          id={`opt-${s.id}`} label={t('skill.' + s.id, s.name)}
                          checked={form.optional.includes(s.id)}
                          onChange={() => toggleSkill('optional', s.id)}
                        />
                      ))}
                    </div>
                  </Col>
                </Row>
              )}
            </>
          )}
          </fieldset>
        </Modal.Body>

        <Modal.Footer className="justify-content-between">
          {isEdit && (
            <Button variant="outline-danger" onClick={handleDelete} disabled={deleting || saving}>
              {deleting ? <Spinner size="sm" /> : t('btn.delete', 'Elimina')}
            </Button>
          )}
          <div className="d-flex gap-2">
            <Button variant="secondary" onClick={onClose} disabled={saving || deleting}>{t('btn.cancel', 'Annulla')}</Button>
            <Button type="submit" variant="primary" disabled={saving || loading || (!isEdit && skillsLoadFailed)}>
              {saving ? <Spinner size="sm" /> : isEdit ? t('btn.save', 'Salva') : t('btn.add', 'Aggiungi')}
            </Button>
          </div>
        </Modal.Footer>
      </Form>
    </Modal>
  )
}
