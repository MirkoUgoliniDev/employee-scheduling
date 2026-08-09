/**
 * @file SpecialistModal.tsx
 * @brief Modal for adding and editing a specialist (clinic physician).
 *
 * @details
 * In "add" mode (specialistId=null): automatically suggests the next
 * available code (`GET /specialists/next-code`).
 * In "edit" mode: loads existing data.
 * Handles duplicate codes with a warning toast (HTTP 409).
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Modal, Button, Form, Spinner } from 'react-bootstrap'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { specialistsApi, type Specialist } from '../../api/specialists'
import { errorCode, errorStatus } from '../../api/client'
import { backendErrorText } from '../../i18n/backendErrors'

/**
 * @brief SpecialistModal component props.
 */
interface Props {
  show: boolean
  /** @brief ID of the specialist to edit, or `null` to add one. */
  specialistId: number | null
  structureId: number
  onClose: () => void
  onSaved: () => void
}

const EMPTY_FORM = { firstName: '', lastName: '', email: '', code: '', active: true }

/** @brief Removes leading whitespace (data pasted with whitespace in front). */
const stripLeading = (s: string) => s.replace(/^\s+/, '')
/** @brief Removes all whitespace (an email address cannot contain any). */
const stripSpaces = (s: string) => s.replace(/\s+/g, '')

export default function SpecialistModal({ show, specialistId, structureId, onClose, onSaved }: Props) {
  const { t, i18n } = useTranslation()
  const isEdit = specialistId !== null
  const [form, setForm] = useState(EMPTY_FORM)
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)
  const sessionIdentity = `${show}:${structureId}:${specialistId ?? 'new'}`
  const currentSession = useRef(sessionIdentity)
  const operationInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentSession.current = sessionIdentity
    operationInFlight.current = null
    setSaving(false)
  }, [sessionIdentity])

  // Load specialist data (edit) or suggest a code (add)
  useEffect(() => {
    if (!show) return
    let current = true
    if (isEdit) {
      setLoading(true)
      specialistsApi.get(specialistId!, structureId)
        .then((spec: Specialist) => {
          if (!current) return
          setForm({
            firstName: spec.firstName,
            lastName: spec.lastName,
            email: spec.email ?? '',
            code: spec.code,
            active: spec.active !== false,
          })
        })
        .catch(() => { if (current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) })
        .finally(() => { if (current) setLoading(false) })
    } else {
      setLoading(false)
      setForm(EMPTY_FORM)
      specialistsApi.nextCode()
        .then(({ code }) => { if (current) setForm(f => ({ ...f, code })) })
        .catch(() => {})
    }
    return () => { current = false }
  }, [show, specialistId, isEdit, structureId, i18n])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (operationInFlight.current) return
    const email = form.email.trim()
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      toast.error(t('validation.emailInvalid', 'Inserisci un indirizzo email valido.'))
      return
    }
    const operation = Symbol('specialist-save')
    operationInFlight.current = operation
    setSaving(true)
    const submitSession = sessionIdentity
    const payload = {
      id: isEdit ? specialistId! : undefined,
      firstName: form.firstName.trim(),
      lastName: form.lastName.trim(),
      email,
      code: form.code.trim(),
      active: form.active,
    }
    try {
      if (isEdit) {
        await specialistsApi.update(specialistId!, payload, structureId)
      } else {
        await specialistsApi.add(payload, structureId)
      }
      if (currentSession.current !== submitSession) return
      toast.success(isEdit ? t('toast.specialistUpdated', 'Specialista aggiornato!') : t('toast.specialistAdded', 'Specialista aggiunto!'))
      onSaved()
      onClose()
    } catch (err: unknown) {
      if (currentSession.current !== submitSession) return
      const specific = backendErrorText(errorCode(err), t)
      if (specific) {
        toast.error(specific)
      } else if (errorStatus(err) === 409) {
        toast.error(t('toast.specialistCodeDuplicate', 'Codice specialista già in uso. Scegline uno diverso.'))
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
          <Modal.Title>{isEdit ? t('modal.editSpecialist', 'Modifica Specialista') : t('modal.addSpecialist', 'Aggiungi Specialista')}</Modal.Title>
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
                  id="spec-active"
                  label={t('label.active', 'Attivo')}
                  checked={form.active}
                  onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                />
              </Form.Group>
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
