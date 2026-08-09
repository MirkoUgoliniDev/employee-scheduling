/**
 * @file ConfigPage.tsx
 * @brief Configuration page — global settings + weekly shift templates.
 *
 * @details
 * Sezioni (Card impilate, estendibile):
 * - **Shift display** — timeline-window granularity (Week / Month), `shiftWindowMode` store.
 * - **Weekly shift template** — Mon–Sun mini-timeline with template shifts (recurring pattern):
 *   click empty space = add, click shift = edit, button to prepopulate from an actual week.
 */

import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from 'react'
import { Form, Button, Spinner, InputGroup, Row, Col, Modal, Table } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faSliders, faLanguage, faCalendarDays, faBuilding, faCertificate, faEnvelope, faFilePdf, faRobot, faPen, faTrash, faAt, faDatabase, faCircleInfo, faPalette } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import i18n from '../i18n'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import toast from 'react-hot-toast'
import { useAppStore } from '../store/useAppStore'
import { locationsApi } from '../api/locations'
import { templatesApi, type ShiftTemplate, type SavedTemplate } from '../api/templates'
import { structuresApi } from '../api/structures'
import VisTimeline, { type TimelineItem, type TimelineGroup } from '../components/shifts/VisTimeline'
import { SAFE_TIMELINE_XSS } from '../components/shifts/timelineXss'
import TemplateShiftModal from '../components/shifts/TemplateShiftModal'
import ConfirmModal from '../components/ConfirmModal'
import './ConfigPage.css'

const DAY_MS = 24 * 60 * 60 * 1000

/** @brief Monday 00:00 of the week containing `d`. */
function startOfWeekMonday(d: Date): Date {
  const diff = (d.getDay() + 6) % 7
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - diff)
}

function escHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** @brief Instant in the reference week for (dayOfWeek, "HH:mm:ss"). */
function dateFor(refMonday: Date, dayOfWeek: number, time: string): Date {
  const [h, m, s] = time.split(':').map(Number)
  return new Date(refMonday.getTime() + dayOfWeek * DAY_MS
    + (h || 0) * 3600_000 + (m || 0) * 60_000 + (s || 0) * 1000)
}

const LabelsPage = lazy(() => import('./LabelsPage'))
const StructuresPage = lazy(() => import('./StructuresPage'))
const SkillsPage = lazy(() => import('./SkillsPage'))
const EmailTemplateSection = lazy(() => import('../components/email/EmailTemplateSection'))
const EmailSettingsSection = lazy(() => import('../components/email/EmailSettingsSection'))
const BackupSection = lazy(() => import('../components/backup/BackupSection'))
const PdfTemplateSection = lazy(() => import('../components/pdf/PdfTemplateSection'))
const SolverSettingsSection = lazy(() => import('../components/solver/SolverSettingsSection'))
const GeneralSettingsSection = lazy(() => import('../components/general/GeneralSettingsSection'))
const InfoSection = lazy(() => import('../components/info/InfoSection'))
const HomeUiSettingsSection = lazy(() => import('../components/info/HomeUiSettingsSection'))

export default function ConfigPage() {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()

  // Configuration is restricted to administrators: the backend rejects access anyway, but without
  // this guard a head nurse entering /config sees the entire sidebar and every section fails with 403.
  useEffect(() => {
    if (!isAdmin) navigate('/', { replace: true })
  }, [isAdmin, navigate])

  const [searchParams, setSearchParams] = useSearchParams()
  const sectionParam = searchParams.get('section')
  const activeSection = sectionParam === 'info' || sectionParam === 'homeUi' || sectionParam === 'general' || sectionParam === 'localizations' || sectionParam === 'templates' || sectionParam === 'structures' || sectionParam === 'skills' || sectionParam === 'emailTemplate' || sectionParam === 'pdfTemplate' || sectionParam === 'solverSettings' || sectionParam === 'emailSettings' || sectionParam === 'backup' ? sectionParam : 'info'
  // Current-structure granularity (synchronized from the store): used only as the label
  // in the "Window" column of the Shift Templates table.
  const shiftWindowMode = useAppStore(s => s.shiftWindowMode)
  const language = useAppStore(s => s.language)

  const [loading, setLoading] = useState(false)

  // Saved (named) templates listed in this section
  const [savedTemplates, setSavedTemplates] = useState<(SavedTemplate & { structureName: string })[]>([])
  const [confirm, setConfirm] = useState<{ message: string; onConfirm: () => void } | null>(null)

  // Saved-template editor: description + shifts (Mon–Sun timeline)
  const [editorHeaderId, setEditorHeaderId] = useState<number | null>(null)
  const [editorStructureId, setEditorStructureId] = useState(0)
  const [editorDesc, setEditorDesc] = useState('')
  const [editorDescBusy, setEditorDescBusy] = useState(false)
  const [editorItems, setEditorItems] = useState<ShiftTemplate[]>([])
  const [editorLocations, setEditorLocations] = useState<{ id: number; name: string }[]>([])
  const [editorLoading, setEditorLoading] = useState(false)

  // Single template-shift modal (add/edit an editor row)
  const [shiftModalOpen, setShiftModalOpen] = useState(false)
  const [editShift, setEditShift] = useState<ShiftTemplate | null>(null)
  const [prefill, setPrefill] = useState<{ day?: number; time?: string; locationId?: number }>({})

  // Reference week (current Monday): used only to position timeline items.
  const refMonday = useMemo(() => startOfWeekMonday(new Date()), [])

  const loadTemplateRows = useCallback(async () => {
    if (activeSection !== 'templates') return
    setLoading(true)
    try {
      const structures = await structuresApi.list()
      const saved = await Promise.all(structures.map(item => templatesApi.listSaved(item.id)))
      const nameById = new Map(structures.map(s => [s.id, s.name]))
      setSavedTemplates(saved.flat().map(tpl => ({ ...tpl, structureName: nameById.get(tpl.structure_id) ?? String(tpl.structure_id) })))
    } catch {
      toast.error(t('toast.errorLoad', 'Errore nel caricamento.'))
    } finally {
      setLoading(false)
    }
  }, [activeSection, t])

  useEffect(() => { loadTemplateRows() }, [loadTemplateRows])

  // Load shifts + locations for the open template editor
  const loadEditor = useCallback(() => {
    if (editorHeaderId == null || !editorStructureId) return
    setEditorLoading(true)
    Promise.all([templatesApi.listSavedItems(editorHeaderId, editorStructureId), locationsApi.list(editorStructureId)])
      .then(([items, locs]) => { setEditorItems(items); setEditorLocations(locs) })
      .catch(() => toast.error(t('toast.errorLoad', 'Errore nel caricamento.')))
      .finally(() => setEditorLoading(false))
  }, [editorHeaderId, editorStructureId, t])

  useEffect(() => { loadEditor() }, [loadEditor])

  function openEditor(tpl: SavedTemplate) {
    setEditorHeaderId(tpl.id)
    setEditorStructureId(tpl.structure_id)
    setEditorDesc(tpl.description)
    setEditorItems([])
    setEditorLocations([])
  }

  // Timeline data from the editor
  const editorGroups = useMemo<TimelineGroup[]>(
    () => editorLocations.map(l => ({ id: l.id, content: escHtml(i18n.t('location.' + l.id, l.name)) })),
    [editorLocations],
  )
  const editorTimelineItems = useMemo<TimelineItem[]>(() => editorItems.map(tpl => {
    const skills = (tpl.requiredSkills ?? []).map(s => s.id ? i18n.t('skill.' + s.id, s.name ?? '') : s.name).filter(Boolean).join(', ')
    return {
      id: tpl.id,
      group: tpl.location_id,
      start: dateFor(refMonday, tpl.day_of_week, tpl.start_time),
      end: dateFor(refMonday, tpl.day_of_week, tpl.end_time),
      content: `<div style="padding:2px 5px;font-size:0.8em">${escHtml(skills)}</div>`,
      style: 'background-color:#a9dfbf;border-color:#27ae60;border-radius:4px',
      editable: false,
    } as TimelineItem
  }), [editorItems, refMonday])

  const tlOptions = useMemo(() => ({
    groupOrder: 'content',
    selectable: false, moveable: true, zoomable: true,
    orientation: { axis: 'top' as const },
    stack: true,
    xss: SAFE_TIMELINE_XSS,
    start: refMonday,
    end: new Date(refMonday.getTime() + 7 * DAY_MS),
    zoomMin: 60 * 60 * 1000,
    zoomMax: 8 * DAY_MS,
    format: {
      minorLabels: { day: 'ddd D' },
      majorLabels: { week: `[${t('config.window.week', 'Settimana')}] w` },
    },
  }), [refMonday, t])

  function handleItemClick(itemId: string | number) {
    const tpl = editorItems.find(x => x.id === Number(itemId))
    if (!tpl) return
    setEditShift(tpl)
    setPrefill({})
    setShiftModalOpen(true)
  }

  function handleCanvasClick(time: Date, groupId: string | number | null) {
    const day = Math.max(0, Math.min(6, Math.floor((time.getTime() - refMonday.getTime()) / DAY_MS)))
    const hh = String(time.getHours()).padStart(2, '0')
    setEditShift(null)
    setPrefill({ day, time: `${hh}:00`, locationId: groupId != null ? Number(groupId) : undefined })
    setShiftModalOpen(true)
  }

  async function handleSaveEditorDesc() {
    if (editorHeaderId == null || !editorStructureId) return
    setEditorDescBusy(true)
    try {
      await templatesApi.updateSaved(editorHeaderId, editorStructureId, editorDesc.trim())
      setSavedTemplates(current => current.map(tpl => tpl.id === editorHeaderId ? { ...tpl, description: editorDesc.trim() } : tpl))
      toast.success(t('toast.templateDescSaved', 'Descrizione salvata!'))
    } catch {
      toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
    } finally {
      setEditorDescBusy(false)
    }
  }

  function handleDeleteSavedTemplate(id: number, structureId: number) {
    setConfirm({
      message: t('confirm.deleteSavedTemplate', 'Eliminare questo template salvato?'),
      onConfirm: async () => {
        try {
          await templatesApi.removeSaved(id, structureId)
          setSavedTemplates(current => current.filter(tpl => tpl.id !== id))
          toast.success(t('toast.savedTemplateDeleted', 'Template eliminato.'))
        } catch {
          toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
        }
      },
    })
  }

  /** Localized label for window granularity (global setting, read-only here). */
  const windowLabel = shiftWindowMode === 'week'
    ? t('config.window.week', 'Settimana')
    : t('config.window.month', 'Mese')

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('config.title', 'Configurazione')}</h5>
      </div>

      <Row className="g-3">
        <Col md={3} xl={2}>
          <nav className="config-sidebar" aria-label={t('config.title', 'Configurazione')}>
            {([
              ['info', t('config.menu.info', 'Info'), t('config.menu.info.tooltip', 'Versioni e informazioni di sistema'), faCircleInfo],
              ['homeUi', t('config.menu.homeUi', 'Configurazione Generale'), t('config.menu.homeUi.tooltip', 'Gestisci copertina e testi della home'), faPalette],
              ['structures', t('config.menu.structures', 'Strutture'), t('config.menu.structures.tooltip', 'Gestisci le strutture organizzative'), faBuilding],
              ['general', t('config.menu.general', 'Parametri generali'), t('config.menu.general.tooltip', 'Configura i parametri generali'), faSliders],
              ['emailSettings', t('config.menu.emailSettings', 'Parametri Email'), t('config.menu.emailSettings.tooltip', 'Configura il server SMTP per l’invio delle email'), faAt],
              ['solverSettings', t('config.menu.solverSettings', 'Parametri Solver'), t('config.menu.solverSettings.tooltip', 'Configura i parametri del motore di pianificazione'), faRobot],
              ['localizations', t('config.menu.localizations', 'Localizzazioni'), t('config.menu.localizations.tooltip', 'Gestisci etichette e traduzioni'), faLanguage],
              ['skills', t('config.menu.skills', 'Competenze'), t('config.menu.skills.tooltip', 'Gestisci le competenze'), faCertificate],
              ['templates', t('config.menu.templates', 'Template turni'), t('config.menu.templates.tooltip', 'Configura i template dei turni'), faCalendarDays],
              ['emailTemplate', t('config.menu.emailTemplate', 'Template Email'), t('config.menu.emailTemplate.tooltip', 'Configura il template delle email'), faEnvelope],
              ['pdfTemplate', t('config.menu.pdfTemplate', 'Template PDF'), t('config.menu.pdfTemplate.tooltip', 'Configura logo, intestazione e piè di pagina dei PDF'), faFilePdf],
              ['backup', t('config.menu.backup', 'Backup'), t('config.menu.backup.tooltip', 'Backup e ripristino del database'), faDatabase],
            ] as const).map(([key, label, tooltip, icon]) => (
              <button
                key={key}
                type="button"
                className={`config-sidebar-item${activeSection === key ? ' active' : ''}`}
                title={tooltip}
                aria-current={activeSection === key ? 'page' : undefined}
                onClick={() => setSearchParams({ section: key }, { replace: true })}
              >
                <FontAwesomeIcon icon={icon} />
                <span>{label}</span>
              </button>
            ))}
          </nav>
        </Col>
        <Col md={9} xl={10}>
      {/* Section: Weekly shift templates */}
      {activeSection === 'templates' && <div>
        <h5 className="mb-3">{t('config.menu.templates', 'Template turni')}</h5>
        <p className="text-muted small">{t('config.template.listHint', 'Elenco dei template salvati da Gestione Turni. Usa la matita per modificarne descrizione e turni.')}</p>
        {loading ? <div className="text-center py-4"><Spinner /></div> :
          <Table bordered hover responsive size="sm" className="align-middle" style={{ maxWidth: 1000 }}>
            <thead className="table-dark"><tr>
              <th style={{ width: 55 }}>ID</th>
              <th>{t('col.company', 'Azienda')}</th>
              <th>{t('label.description', 'Descrizione')}</th>
              <th style={{ width: 130 }}>{t('config.template.shiftsConfigured', 'Turni configurati')}</th>
              <th style={{ width: 110 }} title={t('config.template.window.hint', 'Granularità impostata in Parametri generali.')}>{t('config.template.window', 'Finestra')}</th>
              <th style={{ width: 160 }}>{t('col.createdAt', 'Creato il')}</th>
              <th style={{ width: 90 }}>{t('col.actions', 'Azioni')}</th>
            </tr></thead>
            <tbody>
              {savedTemplates.length === 0
                ? <tr><td colSpan={7} className="text-center text-muted py-3">{t('savedTemplates.empty', 'Nessun template salvato.')}</td></tr>
                : savedTemplates.map((tpl, index) => <tr key={tpl.id}>
                    <td className="text-muted">{index + 1}</td>
                    <td className="fw-semibold">{tpl.structureName}</td>
                    <td>{tpl.description || '—'}</td>
                    <td>{tpl.item_count}</td>
                    <td>{windowLabel}</td>
                    <td className="text-muted small">{tpl.created_at}</td>
                    <td className="text-nowrap">
                      <Button variant="link" size="sm" className="p-0 me-2" title={t('btn.edit', 'Modifica')} onClick={() => openEditor(tpl)}><FontAwesomeIcon icon={faPen} /></Button>
                      <Button variant="link" size="sm" className="p-0 text-danger" title={t('btn.delete', 'Elimina')} onClick={() => handleDeleteSavedTemplate(tpl.id, tpl.structure_id)}><FontAwesomeIcon icon={faTrash} /></Button>
                    </td>
                  </tr>)}
            </tbody>
          </Table>}

        {/* Saved-template editor: description + shifts (Mon–Sun timeline) */}
        <Modal show={editorHeaderId !== null} onHide={() => setEditorHeaderId(null)} size="xl" fullscreen="lg-down" centered scrollable>
          <Modal.Header closeButton><Modal.Title>{t('modal.editSavedTemplate', 'Modifica template')}</Modal.Title></Modal.Header>
          <Modal.Body>
            <InputGroup size="sm" className="mb-3">
              <InputGroup.Text>{t('label.description', 'Descrizione')}</InputGroup.Text>
              <Form.Control
                value={editorDesc}
                onChange={e => setEditorDesc(e.target.value)}
                placeholder={t('placeholder.templateDescription', 'Descrizione del template…')}
                maxLength={200}
              />
              <Button variant="outline-primary" onClick={handleSaveEditorDesc} disabled={editorDescBusy}>
                {editorDescBusy ? <Spinner size="sm" /> : t('btn.save', 'Salva')}
              </Button>
            </InputGroup>
            <p className="text-muted small mb-3">{t('config.template.hint', 'Schema settimanale ricorrente: click su un’area vuota per aggiungere un turno, su un turno per modificarlo. Verrà usato per prepopolare le nuove finestre.')}</p>
            {editorLoading ? (
              <div className="text-center py-4"><Spinner /></div>
            ) : (
              <div className="border">
                <VisTimeline
                  key={`tpl-${editorHeaderId}-${language}`}
                  locale={language}
                  groups={editorGroups}
                  items={editorTimelineItems}
                  options={tlOptions}
                  onItemClick={handleItemClick}
                  onCanvasClick={handleCanvasClick}
                />
              </div>
            )}
          </Modal.Body>
          <Modal.Footer><Button variant="secondary" onClick={() => setEditorHeaderId(null)}>{t('common.close', 'Chiudi')}</Button></Modal.Footer>
        </Modal>
        <ConfirmModal
          show={confirm !== null}
          message={confirm?.message ?? ''}
          onConfirm={() => { confirm?.onConfirm(); setConfirm(null) }}
          onClose={() => setConfirm(null)}
        />
      </div>}

      <Suspense fallback={<div className="text-center py-4"><Spinner /></div>}>
        {activeSection === 'info' && <InfoSection />}
        {activeSection === 'homeUi' && <HomeUiSettingsSection />}
        {activeSection === 'localizations' && <LabelsPage embedded />}
        {activeSection === 'structures' && <StructuresPage embedded />}
        {activeSection === 'skills' && <SkillsPage embedded />}
        {activeSection === 'emailTemplate' && <EmailTemplateSection />}
        {activeSection === 'emailSettings' && <EmailSettingsSection />}
        {activeSection === 'backup' && <BackupSection />}
        {activeSection === 'pdfTemplate' && <PdfTemplateSection />}
        {activeSection === 'solverSettings' && <SolverSettingsSection />}
        {activeSection === 'general' && <GeneralSettingsSection />}
      </Suspense>
        </Col>
      </Row>

      <TemplateShiftModal
        show={shiftModalOpen}
        template={editShift}
        structureId={editorStructureId}
        headerId={editorHeaderId ?? undefined}
        prefillDay={prefill.day}
        prefillStartTime={prefill.time}
        prefillLocationId={prefill.locationId}
        onClose={() => setShiftModalOpen(false)}
        onSaved={() => { loadEditor(); loadTemplateRows() }}
        onDeleted={() => { loadEditor(); loadTemplateRows() }}
      />
    </div>
  )
}
