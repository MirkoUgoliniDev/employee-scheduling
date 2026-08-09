/**
 * @file EmailTemplateSection.tsx
 * @brief Configuration → Email Template section: subject + HTML body with placeholders.
 *
 * @details
 * Form with two editable fields per structure:
 * - **Subject** — plain text; placeholders are inserted at the cursor with chips.
 * - **Body** — HTML editor ({@link RichTextEditor}); same placeholders available.
 * Placeholders ({{Nominativo}}, {{Giorno}}) are replaced when sending.
 * Persisted through PUT /demo-data/email-template (upsert per structure).
 */

import { useEffect, useRef, useState } from 'react'
import { Badge, Button, Form, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFloppyDisk } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import toast from 'react-hot-toast'
import { useAppStore } from '../../store/useAppStore'
import { emailTemplatesApi, EMAIL_PLACEHOLDERS } from '../../api/emailTemplates'
import RichTextEditor, { type RichTextEditorHandle } from './RichTextEditor'
import { sanitizeRichHtml } from '../../utils/sanitizeHtml'

/** @brief Clickable chips for inserting a placeholder at the cursor. */
function PlaceholderChips({ onInsert, disabled = false }: { onInsert: (ph: string) => void; disabled?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="d-flex align-items-center gap-2 mt-1 flex-wrap">
      <span className="text-muted small">{t('emailTpl.insertPlaceholder', 'Segnaposto:')}</span>
      {EMAIL_PLACEHOLDERS.map(ph => (
        <Badge
          key={ph}
          bg="light" text="dark"
          className="border font-monospace"
          style={{ cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.6 : 1 }}
          onMouseDown={e => e.preventDefault() /* preserve the field cursor/selection */}
          onClick={() => { if (!disabled) onInsert(ph) }}
        >
          {ph}
        </Badge>
      ))}
    </div>
  )
}

export default function EmailTemplateSection() {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)

  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const subjectRef = useRef<HTMLInputElement>(null)
  const editorApi = useRef<RichTextEditorHandle | null>(null)
  const loadGeneration = useRef(0)
  const saveOperation = useRef<symbol | null>(null)

  useEffect(() => {
    const generationRef = loadGeneration
    const generation = ++loadGeneration.current
    saveOperation.current = null
    setSaving(false)
    if (!structureId) { setSubject(''); setBody(''); setLoading(false); return }
    setLoading(true)
    emailTemplatesApi.get(structureId)
      .then(tpl => {
        if (generation !== loadGeneration.current) return
        setSubject(tpl.subject ?? '')
        setBody(sanitizeRichHtml(tpl.body ?? ''))
      })
      .catch(() => { if (generation === loadGeneration.current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) })
      .finally(() => { if (generation === loadGeneration.current) setLoading(false) })
    return () => { generationRef.current++ }
  }, [structureId, i18n])

  /** @brief Inserts a placeholder into the subject at the current cursor position. */
  function insertInSubject(ph: string) {
    const input = subjectRef.current
    const start = input?.selectionStart ?? subject.length
    const end = input?.selectionEnd ?? start
    setSubject(subject.slice(0, start) + ph + subject.slice(end))
    requestAnimationFrame(() => {
      input?.focus()
      if (input) input.selectionStart = input.selectionEnd = start + ph.length
    })
  }

  async function handleSave() {
    if (!structureId || saveOperation.current) return
    const operation = Symbol('email-template-save')
    const submitStructureId = structureId
    saveOperation.current = operation
    setSaving(true)
    try {
      const safeBody = sanitizeRichHtml(body)
      await emailTemplatesApi.save(submitStructureId, { subject, body: safeBody })
      if (saveOperation.current !== operation || structureId !== submitStructureId) return
      setBody(safeBody)
      toast.success(t('toast.emailTemplateSaved', 'Template email salvato.'))
    } catch {
      if (saveOperation.current === operation && structureId === submitStructureId)
        toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
    } finally {
      if (saveOperation.current === operation) {
        saveOperation.current = null
        setSaving(false)
      }
    }
  }

  if (!structureId) return <p className="text-muted">{t('msg.selectStructure', 'Seleziona una struttura.')}</p>
  if (loading) return <div className="text-center py-4"><Spinner /></div>

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('config.menu.emailTemplate', 'Template Email')}</h5>
        <Button variant="primary" size="sm" onClick={handleSave} disabled={saving}>
          {saving ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.save', 'Salva')}</>}
        </Button>
      </div>

      <p className="text-muted small">
        {t('emailTpl.placeholdersHint', 'I segnaposto verranno sostituiti con i dati reali al momento dell’invio.')}
      </p>

      <Form.Group className="mb-3" controlId="email-tpl-subject">
        <Form.Label className="fw-semibold">{t('emailTpl.subject', 'Oggetto')}</Form.Label>
        <Form.Control
          ref={subjectRef}
          type="text"
          value={subject}
          disabled={saving}
          placeholder={t('emailTpl.subjectPlaceholder', 'Oggetto della mail…')}
          onChange={e => setSubject(e.target.value)}
        />
        <PlaceholderChips onInsert={insertInSubject} disabled={saving} />
      </Form.Group>

      <Form.Group controlId="email-tpl-body">
        <Form.Label className="fw-semibold">{t('emailTpl.body', 'Corpo')}</Form.Label>
        <RichTextEditor value={body} onChange={setBody} apiRef={editorApi} disabled={saving} />
        <PlaceholderChips onInsert={ph => editorApi.current?.insertText(ph)} disabled={saving} />
      </Form.Group>
    </div>
  )
}
