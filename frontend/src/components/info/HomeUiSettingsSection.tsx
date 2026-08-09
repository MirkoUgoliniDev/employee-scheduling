import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Form, Spinner, Tab, Tabs } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import toast from 'react-hot-toast'
import { HOME_COVERS } from '../../assets/home-covers'
import { homeUiSettingsApi, type HomeUiSettings } from '../../api/homeUiSettings'
import { languagesApi, labelsApi, translationsApi, type Language, type Label, type Localizzazione } from '../../api/labels'
import { refreshTranslations } from '../../i18n'
import RichTextEditor from '../email/RichTextEditor'
import { sanitizeRichHtml } from '../../utils/sanitizeHtml'

type TextRow = { key: string; label: string }

const TEXT_ROWS: TextRow[] = [
  { key: 'home.title', label: 'home.ui.textTitle' },
  { key: 'home.body', label: 'home.ui.textBody' },
  { key: 'home.hint', label: 'home.ui.textHint' },
]

export default function HomeUiSettingsSection() {
  const { t } = useTranslation()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [settings, setSettings] = useState<HomeUiSettings | null>(null)
  const [languages, setLanguages] = useState<Language[]>([])
  const [labels, setLabels] = useState<Label[]>([])
  const [translations, setTranslations] = useState<Record<string, Record<string, string>>>({})
  const [coverKey, setCoverKey] = useState('')
  const coverInputRef = useRef<HTMLInputElement | null>(null)

  const coverOptions = useMemo(() => {
    const base = [
      { id: '', label: t('common.none', 'Nessuna'), src: '' },
      ...HOME_COVERS.map(c => ({ id: c.id, label: c.label, src: c.src })),
    ]
    if (settings?.cover_data_url && settings.cover_key) {
      const exists = base.some(item => item.id === settings.cover_key)
      if (!exists) base.unshift({ id: settings.cover_key, label: t('home.ui.cover', 'Copertina home'), src: settings.cover_data_url })
    }
    return base
  }, [t, settings])

  useEffect(() => {
    setLoading(true)
    Promise.all([
      homeUiSettingsApi.get(),
      languagesApi.list(),
      labelsApi.list(),
      translationsApi.all(),
    ])
      .then(([cfg, langs, lbls, tr]) => {
        setSettings(cfg)
        setCoverKey(cfg.cover_key ?? '')
        setLanguages(langs)
        setLabels(lbls)
        setTranslations(tr)
      })
      .catch(() => toast.error(t('toast.errorLoad', 'Errore nel caricamento.')))
      .finally(() => setLoading(false))
  }, [t])

  function handleCoverChange(next: string) {
    setCoverKey(next)
    if (settings) {
      setSettings({ ...settings, cover_key: next, cover_data_url: '' })
    }
  }

  function handleCoverUpload(file: File) {
    if (!file.name.toLowerCase().endsWith('.webp')) {
      toast.error(t('home.ui.coverHint', 'Solo file .webp, massimo 2 MB.'))
      return
    }
    if (file.size > 2 * 1024 * 1024) {
      toast.error(t('home.ui.coverHint', 'Solo file .webp, massimo 2 MB.'))
      return
    }
    if (file.type && file.type !== 'image/webp') {
      toast.error(t('home.ui.coverHint', 'Solo file .webp, massimo 2 MB.'))
      return
    }
    const nextId = `custom-${Date.now()}`
    const reader = new FileReader()
    reader.onload = () => {
      const dataUrl = String(reader.result || '')
      if (!dataUrl) return
      setCoverKey(nextId)
      setSettings(current => (current ? { ...current, cover_key: nextId, cover_data_url: dataUrl } : current))
    }
    reader.onerror = () => {
      toast.error(t('toast.errorLoad', 'Errore nel caricamento.'))
    }
    reader.readAsDataURL(file)
  }

  function updateTranslation(langCode: string, key: string, value: string) {
    setTranslations(prev => ({
      ...prev,
      [langCode]: { ...(prev[langCode] ?? {}), [key]: value },
    }))
  }

  function stripHtml(value: string) {
    return value.replace(/<[^>]+>/g, '')
  }

  async function saveTexts() {
    if (!settings) return
    setSaving(true)
    try {
      await homeUiSettingsApi.save({
        ...settings,
        cover_key: coverKey,
        cover_data_url: settings.cover_data_url ?? '',
        title_key: 'home.title',
        body_key: 'home.body',
        hint_key: 'home.hint',
      })

      for (const row of TEXT_ROWS) {
        const labelId = findLabelId(row.key)
        if (!labelId) continue
        const items: Localizzazione[] = languages.map(lang => {
          const raw = translations?.[lang.code]?.[row.key] ?? ''
          const sanitized = row.key === 'home.title'
            ? stripHtml(raw)
            : sanitizeRichHtml(raw)
          return {
            entityType: 'labels',
            entityId: labelId,
            fieldName: 'value',
            languageId: lang.id,
            value: sanitized,
          }
        }).filter(item => item.value.trim() !== '')
        await translationsApi.saveForEntity('labels', labelId, items)
      }

      await refreshTranslations()
      toast.success(t('msg.success.saved', 'Salvato!'))
    } catch {
      toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
    } finally {
      setSaving(false)
    }
  }

  function findLabelId(key: string): number {
    return labels.find(l => l.key === key)?.id ?? 0
  }

  if (loading || !settings) {
    return <div className="text-center py-4"><Spinner /></div>
  }

  return (
    <div>
      <h5 className="mb-3">{t('home.ui.title', 'Home – Contenuti')}</h5>

      <div className="mb-4" style={{ maxWidth: 900 }}>
        <Form.Label className="fw-semibold">{t('home.ui.cover', 'Copertina home')}</Form.Label>
        <div className="d-flex flex-wrap gap-2 align-items-center">
          <Form.Select value={coverKey} onChange={e => handleCoverChange(e.target.value)} style={{ maxWidth: 280 }}>
            {coverOptions.map(opt => (
              <option key={opt.id} value={opt.id}>{opt.label}</option>
            ))}
          </Form.Select>
          <input
            ref={coverInputRef}
            type="file"
            accept="image/webp"
            onChange={e => {
              const file = e.target.files?.[0]
              if (file) handleCoverUpload(file)
              if (coverInputRef.current) coverInputRef.current.value = ''
            }}
          />
        </div>
        <Form.Text>{t('home.ui.coverHint', 'Solo file .webp, massimo 2 MB.')}</Form.Text>
      </div>

      <div style={{ maxWidth: 900 }}>
        <div className="d-flex justify-content-between align-items-center mb-3">
          <Form.Label className="fw-semibold mb-0">{t('home.ui.texts', 'Testi home')}</Form.Label>
          <Button variant="primary" size="sm" onClick={saveTexts} disabled={saving}>
            {saving ? <Spinner size="sm" /> : t('btn.save', 'Salva')}
          </Button>
        </div>

        {languages.length > 0 && (
          <Tabs defaultActiveKey={String(languages[0]?.id)} className="mb-2">
            {languages.map(lang => (
              <Tab key={lang.id} eventKey={String(lang.id)} title={lang.description || lang.code.toUpperCase()}>
                <div className="mt-3">
                  <Form.Group className="mb-3">
                    <Form.Label className="fw-semibold">{t('home.ui.textTitle', 'Titolo')}</Form.Label>
                    <Form.Control
                      value={translations?.[lang.code]?.['home.title'] ?? ''}
                      onChange={e => updateTranslation(lang.code, 'home.title', e.target.value)}
                      disabled={saving}
                    />
                  </Form.Group>

                  <Form.Group className="mb-3">
                    <Form.Label className="fw-semibold">{t('home.ui.textBody', 'Testo principale')}</Form.Label>
                    <RichTextEditor
                      value={translations?.[lang.code]?.['home.body'] ?? ''}
                      onChange={html => updateTranslation(lang.code, 'home.body', html)}
                      minHeight={160}
                      disabled={saving}
                    />
                  </Form.Group>

                  <Form.Group className="mb-3">
                    <Form.Label className="fw-semibold">{t('home.ui.textHint', 'Suggerimento')}</Form.Label>
                    <RichTextEditor
                      value={translations?.[lang.code]?.['home.hint'] ?? ''}
                      onChange={html => updateTranslation(lang.code, 'home.hint', html)}
                      minHeight={140}
                      disabled={saving}
                    />
                  </Form.Group>
                </div>
              </Tab>
            ))}
          </Tabs>
        )}
      </div>
    </div>
  )
}
