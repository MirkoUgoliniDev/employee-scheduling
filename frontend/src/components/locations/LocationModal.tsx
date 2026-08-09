/**
 * @file LocationModal.tsx
 * @brief Modal for adding and editing a location.
 *
 * @details
 * In "add" mode: suggests the next available code.
 * In "edit" mode: loads existing data with assigned skills.
 * Available skills are loaded from `employeesApi.allSkills(structureId)` (shared endpoint).
 * Checkboxes are divided into "Required skills" and "Optional skills".
 * Handles duplicate codes with a warning toast (HTTP 409).
 *
 * The "Name" field is the location name IN THE CURRENT UI LANGUAGE: saving updates
 * that language in the `localizzazioni` table (other languages are edited under
 * Configuration → Localizations). The base `name` column reflects the key language
 * (FALLBACK_LANG): it is the default when a language is not localized and serves
 * direct consumers (reports/PDFs). It is not overwritten when editing other languages.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Modal, Button, Form, Row, Col, Spinner } from 'react-bootstrap'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { locationsApi, type Location, type SkillOption } from '../../api/locations'
import { employeesApi } from '../../api/employees'  // riusa allSkills
import { specialistsApi, type Specialist } from '../../api/specialists'
import { languagesApi, translationsApi, type Language } from '../../api/labels'
import { errorCode, errorStatus } from '../../api/client'
import { backendErrorText } from '../../i18n/backendErrors'
import { refreshTranslations } from '../../i18n'

/**
 * @brief LocationModal component props.
 */
interface Props {
  show: boolean
  /** @brief ID of the location to edit, or `null` to add one. */
  locationId: number | null
  structureId: number
  onClose: () => void
  onSaved: () => void
}

const EMPTY_FORM = { name: '', code: '', order: 0, required: [] as number[], optional: [] as number[], active: true, specialistId: 0 }

/** "Key" language: its value is the default when a language is not localized
 *  (aligned with i18n `fallbackLng`). The base `name` column reflects this language. */
const FALLBACK_LANG = 'it'

export default function LocationModal({ show, locationId, structureId, onClose, onSaved }: Props) {
  const { t, i18n } = useTranslation()
  const isEdit = locationId !== null
  const [form, setForm] = useState(EMPTY_FORM)
  const [baseName, setBaseName] = useState('') // name in the key language (fallback)
  const [allSkills, setAllSkills] = useState<SkillOption[]>([])
  const [specialists, setSpecialists] = useState<Specialist[]>([])
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)
  const [languages, setLanguages] = useState<Language[]>([])
  const [editingLanguageId, setEditingLanguageId] = useState<number | null>(null)
  const currentLang = languages.find(l => l.id === editingLanguageId)
  const sessionIdentity = `${show}:${structureId}:${locationId ?? 'new'}`
  const currentSession = useRef(sessionIdentity)
  const operationInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentSession.current = sessionIdentity
    operationInFlight.current = null
    setSaving(false)
  }, [sessionIdentity])

  useEffect(() => {
    if (!show) return
    let current = true
    employeesApi.allSkills(structureId).then(items => { if (current) setAllSkills(items) }).catch(() => {})
    specialistsApi.list(structureId).then(items => { if (current) setSpecialists(items) }).catch(() => {})
    return () => { current = false }
  }, [show, structureId])

  useEffect(() => {
    if (!show) return
    let current = true
    const languageAtOpen = i18n.resolvedLanguage ?? i18n.language
    if (isEdit) {
      setLoading(true)
      // The Name field shows the value in the current UI language (falling back to the base name).
      Promise.all([
        locationsApi.get(locationId!, structureId),
        translationsApi.getForEntity('locations', locationId!, structureId),
        languagesApi.list(),
      ])
        .then(([loc, locs, langs]: [Location, Awaited<ReturnType<typeof translationsApi.getForEntity>>, Language[]]) => {
          if (!current) return
          setBaseName(loc.name)
          const langId = langs.find(l => l.code === languageAtOpen)?.id
          setLanguages(langs)
          setEditingLanguageId(langId ?? null)
          const localizedName = langId
            ? locs.find(x => x.fieldName === 'name' && x.languageId === langId)?.value
            : undefined
          setForm({
            name: (localizedName && localizedName.trim()) || loc.name,
            code: loc.code,
            order: loc.order,
            required: (loc.requiredSkills ?? []).filter(s => s.used).map(s => s.id),
            optional: (loc.optionalSkills ?? []).filter(s => s.used).map(s => s.id),
            active: loc.active !== false,
            specialistId: loc.specialistId ?? 0,
          })
        })
        .catch(() => { if (current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) })
        .finally(() => { if (current) setLoading(false) })
    } else {
      setLoading(false)
      setForm(EMPTY_FORM)
      setBaseName('')
      languagesApi.list().then(langs => {
        if (!current) return
        setLanguages(langs)
        setEditingLanguageId(langs.find(l => l.code === languageAtOpen)?.id ?? null)
      }).catch(() => {})
      locationsApi.nextCode()
        .then(({ code }) => { if (current) setForm(f => ({ ...f, code })) })
        .catch(() => {})
    }
    return () => { current = false }
  }, [show, locationId, isEdit, structureId, i18n])

  function toggle(field: 'required' | 'optional', id: number) {
    setForm(f => ({
      ...f,
      [field]: f[field].includes(id) ? f[field].filter(s => s !== id) : [...f[field], id],
    }))
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (operationInFlight.current) return
    const operation = Symbol('location-save')
    operationInFlight.current = operation
    setSaving(true)
    const submitSession = sessionIdentity
    const typedName = form.name.trim()
    // The base `name` column reflects the key language: update it only when creating
    // or editing in that language; otherwise retain the existing value.
    const isDefaultLang = (currentLang?.code ?? FALLBACK_LANG) === FALLBACK_LANG
    const payload = {
      id: isEdit ? locationId! : undefined,
      name: (!isEdit || isDefaultLang) ? typedName : (baseName || typedName),
      code: form.code.trim(),
      order: form.order,
      requiredSkills: form.required.map(id => ({ id })),
      optionalSkills: form.optional.map(id => ({ id })),
      active: form.active,
      specialistId: form.specialistId || null,
    }
    try {
      let savedId = isEdit ? locationId! : 0
      if (isEdit) {
        await locationsApi.update(locationId!, payload, structureId)
      } else {
        const created = await locationsApi.add(payload, structureId)
        savedId = created.id
      }
      // The entered name belongs to the current UI language: update ONLY that language
      // in localizzazioni (others remain and are edited under → Localizations).
      if (savedId && currentLang) {
        await translationsApi.upsertEntityName('locations', savedId, currentLang.id, typedName, structureId)
        if (currentSession.current !== submitSession) return
        await refreshTranslations() // translated names update without a reload
      }
      if (currentSession.current !== submitSession) return
      toast.success(isEdit ? t('toast.locationUpdated', 'Sede aggiornata!') : t('toast.locationAdded', 'Sede aggiunta!'))
      onSaved()
      onClose()
    } catch (err: unknown) {
      if (currentSession.current !== submitSession) return
      const specific = backendErrorText(errorCode(err), t)
      if (specific) {
        toast.error(specific)
      } else if (errorStatus(err) === 409) {
        toast.error(t('toast.codeDuplicate', 'Codice sede già in uso. Scegline uno diverso.'))
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
    <Modal show={show} onHide={() => { if (!saving) onClose() }} centered size="lg">
      <Form onSubmit={handleSubmit}>
        <Modal.Header closeButton={!saving}>
          <Modal.Title>{isEdit ? t('modal.editLocation', 'Modifica Sede') : t('modal.addLocation', 'Aggiungi Sede')}</Modal.Title>
        </Modal.Header>

        <Modal.Body>
          <fieldset disabled={saving} className="border-0 p-0 m-0 w-100">
          {loading ? (
            <div className="text-center py-3"><Spinner /></div>
          ) : (
            <>
              <Row className="mb-3">
                <Col>
                  <Form.Label>{t('label.specialist', 'Specialista')}</Form.Label>
                  <Form.Select
                    value={form.specialistId}
                    onChange={e => setForm(f => ({ ...f, specialistId: parseInt(e.target.value) || 0 }))}
                  >
                    <option value={0}>{t('select.none', '— Nessuno —')}</option>
                    {specialists.map(s => (
                      <option key={s.id} value={s.id}>
                        {s.fullName ?? `${s.firstName} ${s.lastName}`}{s.active === false ? ` (${t('label.inactive', 'No')})` : ''}
                      </option>
                    ))}
                  </Form.Select>
                </Col>
                <Col sm={3}>
                  <Form.Label>{t('label.code', 'Codice')}</Form.Label>
                  <Form.Control
                    required
                    value={form.code}
                    onChange={e => setForm(f => ({ ...f, code: e.target.value }))}
                  />
                </Col>
                <Col sm={2}>
                  <Form.Label>{t('label.order', 'Ordine')}</Form.Label>
                  <Form.Control
                    type="number"
                    value={form.order}
                    onChange={e => setForm(f => ({ ...f, order: parseInt(e.target.value) || 0 }))}
                  />
                </Col>
              </Row>

              <Form.Group className="mb-3">
                <Form.Label>{t('label.name', 'Nome')}{currentLang ? ` (${currentLang.description})` : ''}</Form.Label>
                <Form.Control
                  required
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                />
              </Form.Group>

              <Form.Group className="mb-3">
                <Form.Check
                  type="switch"
                  id="loc-active"
                  label={t('label.active', 'Attivo')}
                  checked={form.active}
                  onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                />
                <Form.Text className="text-muted">
                  {t('hint.inactiveLocation', 'Se disattivata non compare in Gestione Turni e il solver non la considera.')}
                </Form.Text>
              </Form.Group>

              {allSkills.length > 0 && (
                <Row>
                  <Col>
                    <Form.Label className="fw-semibold">{t('label.requiredSkills', 'Competenze richieste')}</Form.Label>
                    <div className="d-flex flex-wrap gap-2">
                      {allSkills.map(s => (
                        <Form.Check
                          key={s.id}
                          type="checkbox"
                          id={`req-${s.id}`}
                          label={t('skill.' + s.id, s.name)}
                          checked={form.required.includes(s.id)}
                          onChange={() => toggle('required', s.id)}
                        />
                      ))}
                    </div>
                  </Col>
                  <Col>
                    <Form.Label className="fw-semibold">{t('label.optionalSkills', 'Competenze opzionali')}</Form.Label>
                    <div className="d-flex flex-wrap gap-2">
                      {allSkills.map(s => (
                        <Form.Check
                          key={s.id}
                          type="checkbox"
                          id={`opt-${s.id}`}
                          label={t('skill.' + s.id, s.name)}
                          checked={form.optional.includes(s.id)}
                          onChange={() => toggle('optional', s.id)}
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

        <Modal.Footer>
          <Button variant="secondary" onClick={onClose} disabled={saving}>{t('btn.cancel', 'Annulla')}</Button>
          <Button type="submit" variant="primary" disabled={saving || loading}>
            {saving ? <Spinner size="sm" /> : isEdit ? t('btn.save', 'Salva') : t('btn.add', 'Aggiungi')}
          </Button>
        </Modal.Footer>
      </Form>
    </Modal>
  )
}
