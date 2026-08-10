/**
 * @file LabelModal.tsx
 * @brief Modal for adding and editing a label with per-language translations.
 *
 * @details
 * Manages the base form (key + description) and one tab for each available language
 * where translated text can be entered.
 * On save:
 * 1. Creates/updates the label (`POST /labels` or `PUT /labels/{id}`)
 * 2. Saves all translations in parallel (`PUT /localizzazioni/labels/{id}`)
 *
 * Existing translations are loaded from `GET /localizzazioni/labels/{id}`
 * directly with fetch (it does not use the typed client because the path is outside `api/`).
 */

import { useEffect, useState } from 'react'
import { Modal, Button, Form, Spinner, Tab, Tabs } from 'react-bootstrap'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { labelsApi, languagesApi, translationsApi, type Label, type Language } from '../../api/labels'
import { errorCode } from '../../api/client'
import { backendErrorText } from '../../i18n/backendErrors'
import { refreshTranslations } from '../../i18n'
import { useAppStore } from '../../store/useAppStore'

/**
 * @brief LabelModal component props.
 */
interface Props {
  show: boolean
  /** @brief Label to edit, or `null` to add one. */
  label: Label | null
  onClose: () => void
  onSaved: () => void
}

export default function LabelModal({ show, label, onClose, onSaved }: Props) {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const isEdit = label !== null
  // Pseudo-label for an entity name (skill.<id>/location.<id>): key and description
  // belong to the entity (read-only); translations live in `localizzazioni`.
  const isEntity = !!label?.entityType && label.entityType !== 'labels'
  const [key, setKey] = useState('')
  const [description, setDescription] = useState('')
  const [languages, setLanguages] = useState<Language[]>([])
  const [translations, setTranslations] = useState<Record<number, string>>({}) // languageId → value
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!show) return
    let current = true
    setLoading(true)
    languagesApi.list()
      .then(langs => {
        if (!current) return
        setLanguages(langs)
        if (isEdit) {
          setKey(label.key)
          setDescription(label.description)
          const map: Record<number, string> = {}
          langs.forEach(l => { map[l.id] = '' })
          // Entity-name pseudo-labels read from the localizzazioni table through
          // /localizzazioni/{entityType}/{entityId}; UI labels read from /labels.
          if (isEntity) {
            return translationsApi.getForEntity(
              label.entityType!, label.entityId!, label.entityType === 'locations' ? structureId : undefined,
            )
              .then(locs => {
                locs.forEach(loc => { if (loc.languageId in map) map[loc.languageId] = loc.value ?? '' })
                if (current) setTranslations(map)
              })
          }
          // Load existing translations for this label
          return fetch(`/localizzazioni/labels/${label.id}`)
            .then(r => r.json())
            .then((locs: { languageId: number; value: string }[]) => {
              locs.forEach(loc => { map[loc.languageId] = loc.value ?? '' })
              if (current) setTranslations(map)
            })
        } else {
          setKey('')
          setDescription('')
          const map: Record<number, string> = {}
          langs.forEach(l => { map[l.id] = '' })
          if (current) setTranslations(map)
        }
      })
      .catch(() => { if (current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) })
      .finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [show, label, isEdit, isEntity, structureId, i18n])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true)
    try {
      // Entity-name pseudo-label: save only translations (replace) in the
      // localizzazioni table; key/description belong to the entity.
      if (isEntity && label) {
        const items = Object.entries(translations)
          .filter(([, value]) => value.trim() !== '')
          .map(([languageId, value]) => ({
            entityType: label.entityType!, entityId: label.entityId!, fieldName: 'name',
            languageId: Number(languageId), value: value.trim(),
          }))
        await translationsApi.saveForEntity(
          label.entityType!, label.entityId!, items, label.entityType === 'locations' ? structureId : undefined,
        )
        await refreshTranslations() // translated names update without a reload
        toast.success(t('toast.labelUpdated', 'Etichetta aggiornata.'))
        onSaved()
        return
      }
      if (!key.trim()) { toast.error(t('toast.keyRequired', 'La chiave è obbligatoria.')); return }
      if (!description.trim()) { toast.error(t('toast.descriptionRequired', 'La descrizione è obbligatoria.')); return }
      const translationsPayload: Record<number, string> = {}
      languages.forEach(lang => { translationsPayload[lang.id] = translations[lang.id] ?? '' })

      if (isEdit) {
        await labelsApi.update(label.id, { key: key.trim(), description: description.trim(), translations: translationsPayload })
      } else {
        await labelsApi.add({ key: key.trim(), description: description.trim(), translations: translationsPayload })
      }
      await refreshTranslations()
      toast.success(isEdit ? t('toast.labelUpdated', 'Etichetta aggiornata.') : t('toast.labelAdded', 'Etichetta aggiunta.'))
      onSaved()
    } catch (err) {
      toast.error(backendErrorText(errorCode(err), t)
        ?? (isEdit ? t('toast.errorEdit', 'Errore durante la modifica.') : t('toast.errorAdd', "Errore durante l'aggiunta.")))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal show={show} onHide={onClose} centered size="lg">
      <Form onSubmit={handleSubmit}>
        <Modal.Header closeButton>
          <Modal.Title>{isEdit ? t('modal.editLabel', 'Modifica Etichetta') : t('modal.addLabel', 'Aggiungi Etichetta')}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          {loading ? (
            <div className="text-center py-3"><Spinner /></div>
          ) : (
            <>
              <Form.Group className="mb-3">
                <Form.Label>{t('label.key', 'Chiave')} {!isEntity && <span className="text-danger">*</span>}</Form.Label>
                <Form.Control
                  value={key}
                  onChange={e => setKey(e.target.value)}
                  placeholder={t('placeholder.labelKey', 'es. btn.save')}
                  required={!isEntity}
                  readOnly={isEntity}
                  plaintext={isEntity}
                />
              </Form.Group>
              <Form.Group className="mb-3">
                <Form.Label>{t('label.description', 'Descrizione')} {!isEntity && <span className="text-danger">*</span>}</Form.Label>
                <Form.Control
                  value={description}
                  onChange={e => setDescription(e.target.value)}
                  placeholder={t('placeholder.labelDescription', 'Descrizione leggibile')}
                  required={!isEntity}
                  readOnly={isEntity}
                  plaintext={isEntity}
                />
              </Form.Group>

              {languages.length > 0 && (
                <div>
                  <Form.Label className="fw-semibold">{t('label.translations', 'Traduzioni')}</Form.Label>
                  <Tabs defaultActiveKey={String(languages[0]?.id)} className="mb-2">
                    {languages.map(lang => (
                      <Tab
                        key={lang.id}
                        eventKey={String(lang.id)}
                        title={lang.description || lang.code.toUpperCase()}
                      >
                        <Form.Control
                          className="mt-2"
                          value={translations[lang.id] ?? ''}
                          onChange={e => setTranslations(prev => ({ ...prev, [lang.id]: e.target.value }))}
                          placeholder={`Valore in ${lang.description || lang.code}`}
                        />
                      </Tab>
                    ))}
                  </Tabs>
                  {isEntity && (
                    <Form.Text className="text-muted">
                      {t('hint.skillTranslationFallback', 'Le lingue lasciate vuote useranno il Nome base.')}
                    </Form.Text>
                  )}
                </div>
              )}
            </>
          )}
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
