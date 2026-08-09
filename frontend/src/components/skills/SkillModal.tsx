/**
 * @file SkillModal.tsx
 * @brief Modal for adding/editing a skill (name, order, active state).
 *
 * @details
 * The "Name" field is the skill name IN THE CURRENT UI LANGUAGE: on save, the page updates
 * that language in the `localizzazioni` table (other languages are edited under
 * Configuration → Localizations). The base `name` column reflects the key language
 * (FALLBACK_LANG): the default when a language is not localized; it is not
 * overwritten when editing other languages.
 */

import { useEffect, useState } from 'react'
import { Modal, Button, Form, Spinner } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import { type Skill } from '../../api/skills'
import { languagesApi, translationsApi, type Language } from '../../api/labels'
import { useAppStore } from '../../store/useAppStore'

/** "Key" language (= i18n fallbackLng): the base `name` column reflects this language. */
const FALLBACK_LANG = 'it'

interface Props {
  show: boolean
  skill: Skill | null
  nextOrder: number
  structureId: number
  onClose: () => void
  /**
   * @param languageId    Current UI language in which to save the entered name.
   * @param isDefaultLang true when the current language is the key language (also updates `name`).
   */
  onSave: (name: string, order: number, active: boolean, languageId?: number, isDefaultLang?: boolean) => Promise<void>
}

export default function SkillModal({ show, skill, nextOrder, structureId, onClose, onSave }: Props) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [order, setOrder] = useState(nextOrder)
  const [saving, setSaving] = useState(false)
  const [active, setActive] = useState(true)
  const [languages, setLanguages] = useState<Language[]>([])
  const language = useAppStore(s => s.language)
  const currentLang = languages.find(l => l.code === language)

  useEffect(() => {
    if (!show) return
    setOrder(skill?.order ?? nextOrder)
    setActive(skill?.active ?? true)
    // The Name field shows the value in the current UI language (falling back to the base name).
    languagesApi.list()
      .then(langs => {
        setLanguages(langs)
        const langId = langs.find(l => l.code === language)?.id
        if (skill && langId) {
          return translationsApi.getForEntity('skills', skill.id, structureId).then(locs => {
            const localized = locs.find(x => x.fieldName === 'name' && x.languageId === langId)?.value
            setName((localized && localized.trim()) || skill.name)
          })
        }
        setName(skill?.name ?? '')
      })
      .catch(() => { setLanguages([]); setName(skill?.name ?? '') })
  }, [show, skill, nextOrder, language])

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!name.trim()) return
    setSaving(true)
    try {
      const isDefaultLang = (currentLang?.code ?? FALLBACK_LANG) === FALLBACK_LANG
      await onSave(name, order, active, currentLang?.id, isDefaultLang)
    } finally {
      setSaving(false)
    }
  }

  const title = skill
    ? t('modal.editSkill', 'Modifica competenza')
    : t('modal.addSkill', 'Aggiungi competenza')

  return (
    <Modal show={show} onHide={onClose} centered>
      <Form onSubmit={handleSubmit}>
        <Modal.Header closeButton>
          <Modal.Title>{title}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Form.Group className="mb-3">
            <Form.Label>{t('table.name', 'Nome')}{currentLang ? ` (${currentLang.description})` : ''}</Form.Label>
            <Form.Control
              required
              autoFocus
              value={name}
              placeholder={t('placeholder.skillName', 'Nome competenza')}
              onChange={event => setName(event.target.value)}
            />
          </Form.Group>
          <Form.Group>
            <Form.Label>{t('label.order', 'Ordine')}</Form.Label>
            <Form.Control
              required
              type="number"
              min={0}
              value={order}
              onChange={event => setOrder(Number(event.target.value))}
            />
          </Form.Group>
          <Form.Check className="mt-3" type="switch" label={t('skills.active', 'Attiva')} checked={active} onChange={event => setActive(event.target.checked)} />
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={onClose} disabled={saving}>{t('btn.cancel', 'Annulla')}</Button>
          <Button type="submit" variant="primary" disabled={saving || !name.trim()}>
            {saving ? <Spinner size="sm" /> : t('btn.save', 'Salva')}
          </Button>
        </Modal.Footer>
      </Form>
    </Modal>
  )
}
