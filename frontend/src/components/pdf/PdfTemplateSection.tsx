import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Alert, Button, Col, Form, Modal, Row, Spinner, Table } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFileCirclePlus, faFloppyDisk, faImage, faPen, faTrash } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import toast from 'react-hot-toast'
import { EMPTY_PDF_TEMPLATE, pdfTemplatesApi, type PdfTemplate } from '../../api/pdfTemplates'
import { structuresApi, type Structure } from '../../api/structures'
import ConfirmModal from '../ConfirmModal'

const MAX_SOURCE_BYTES = 5 * 1024 * 1024
const MAX_DIMENSION = 1200
type PdfForm = typeof EMPTY_PDF_TEMPLATE

function prepareLogo(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    if (!['image/png', 'image/jpeg'].includes(file.type)) return reject(new Error('FORMAT'))
    if (file.size > MAX_SOURCE_BYTES) return reject(new Error('SIZE'))
    const reader = new FileReader()
    reader.onerror = () => reject(new Error('READ'))
    reader.onload = () => {
      const image = new Image()
      image.onerror = () => reject(new Error('READ'))
      image.onload = () => {
        const scale = Math.min(1, MAX_DIMENSION / Math.max(image.width, image.height))
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(image.width * scale))
        canvas.height = Math.max(1, Math.round(image.height * scale))
        canvas.getContext('2d')?.drawImage(image, 0, 0, canvas.width, canvas.height)
        resolve(canvas.toDataURL(file.type === 'image/jpeg' ? 'image/jpeg' : 'image/png', 0.9))
      }
      image.src = String(reader.result)
    }
    reader.readAsDataURL(file)
  })
}

interface EditorProps {
  show: boolean
  template: PdfTemplate | null
  structures: Structure[]
  usedStructureIds: Set<number>
  onClose: () => void
  onSaved: () => void
}

function PdfTemplateModal({ show, template, structures, usedStructureIds, onClose, onSaved }: EditorProps) {
  const { t } = useTranslation()
  const isEdit = template !== null
  const available = isEdit ? structures : structures.filter(s => !usedStructureIds.has(s.id))
  const firstAvailableId = available[0]?.id ?? 0
  const [structureId, setStructureId] = useState(0)
  const [form, setForm] = useState<PdfForm>(EMPTY_PDF_TEMPLATE)
  const [saving, setSaving] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const selectedStructure = structures.find(x => x.id === structureId)
  const editorIdentity = `${show}:${template?.id ?? 'new'}`
  const currentEditor = useRef(editorIdentity)
  const logoRequest = useRef(0)
  const saveInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentEditor.current = editorIdentity
    saveInFlight.current = null
    logoRequest.current++
    setSaving(false)
    if (!show) return
    setStructureId(template?.structure_id ?? 0)
    setForm(template ? {
      header_text: template.header_text, footer_text: template.footer_text,
      logo_data_url: template.logo_data_url, primary_color: template.primary_color,
    } : { ...EMPTY_PDF_TEMPLATE })
  }, [show, template, editorIdentity])

  // Keep the selection valid without reinitializing the form: a concurrent update
  // to available templates must not erase unsaved changes.
  const selectionAvailable = isEdit || available.some(item => item.id === structureId)
  useEffect(() => {
    if (!show || isEdit || selectionAvailable) return
    setStructureId(firstAvailableId)
  }, [show, isEdit, selectionAvailable, firstAvailableId])

  async function selectLogo(file?: File) {
    if (!file) return
    const request = ++logoRequest.current
    const requestEditor = editorIdentity
    try {
      const logoDataUrl = await prepareLogo(file)
      if (request !== logoRequest.current || currentEditor.current !== requestEditor) return
      setForm(f => ({ ...f, logo_data_url: logoDataUrl }))
    } catch (err) {
      if (request !== logoRequest.current || currentEditor.current !== requestEditor) return
      toast.error(err instanceof Error && err.message === 'SIZE'
        ? t('pdfTpl.logoTooLarge', 'Il logo non può superare 5 MB.')
        : t('pdfTpl.logoInvalid', 'Seleziona un’immagine PNG o JPG valida.'))
    } finally {
      if (request === logoRequest.current && currentEditor.current === requestEditor && inputRef.current) inputRef.current.value = ''
    }
  }

  async function save() {
    if (!structureId || !selectionAvailable) return
    if (saveInFlight.current) return
    const operation = Symbol('pdf-save')
    saveInFlight.current = operation
    setSaving(true)
    const submitEditor = editorIdentity
    const targetStructureId = structureId
    try {
      await pdfTemplatesApi.save(targetStructureId, form)
      if (currentEditor.current !== submitEditor) return
      toast.success(t('toast.pdfTemplateSaved', 'Template PDF salvato.'))
      onSaved(); onClose()
    } catch { if (currentEditor.current === submitEditor) toast.error(t('toast.errorSave', 'Errore durante il salvataggio.')) }
    finally {
      if (saveInFlight.current === operation) {
        saveInFlight.current = null
        if (currentEditor.current === submitEditor) setSaving(false)
      }
    }
  }

  return <Modal show={show} onHide={() => { if (!saving) onClose() }} size="xl" centered>
    <Modal.Header closeButton={!saving}><Modal.Title>{isEdit
      ? t('pdfTpl.editTitle', 'Modifica template PDF')
      : t('pdfTpl.addTitle', 'Nuovo template PDF')}</Modal.Title></Modal.Header>
    <Modal.Body>
      <fieldset disabled={saving} className="border-0 p-0 m-0 w-100">
      <Row className="g-4">
        <Col lg={6}>
          <Form.Group className="mb-3">
            <Form.Label className="fw-semibold">{t('pdfTpl.structure', 'Azienda')}</Form.Label>
            <Form.Select value={structureId} disabled={isEdit} onChange={e => setStructureId(Number(e.target.value))}>
              <option value={0}>{t('placeholder.selectFacility', 'Seleziona struttura…')}</option>
              {available.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </Form.Select>
            {isEdit && <Form.Text>{t('pdfTpl.structureLocked', 'L’azienda associata non può essere cambiata durante la modifica.')}</Form.Text>}
          </Form.Group>
          <Form.Group className="mb-3">
            <Form.Label className="fw-semibold">{t('pdfTpl.logo', 'Logo')}</Form.Label>
            <div className="d-flex gap-2">
              <Button variant="outline-primary" onClick={() => inputRef.current?.click()}>
                <FontAwesomeIcon icon={faImage} className="me-2" />{t('pdfTpl.chooseLogo', 'Scegli logo')}
              </Button>
              {form.logo_data_url && <Button variant="outline-danger" onClick={() => setForm(f => ({ ...f, logo_data_url: '' }))}>
                <FontAwesomeIcon icon={faTrash} className="me-2" />{t('btn.remove', 'Rimuovi')}
              </Button>}
              <input ref={inputRef} hidden type="file" accept="image/png,image/jpeg" onChange={e => selectLogo(e.target.files?.[0])} />
            </div>
            <Form.Text>{t('pdfTpl.logoHint', 'PNG o JPG, massimo 5 MB. Ridimensionamento automatico.')}</Form.Text>
          </Form.Group>
          <Form.Group className="mb-3">
            <Form.Label className="fw-semibold">{t('pdfTpl.header', 'Testo intestazione')}</Form.Label>
            <Form.Control as="textarea" rows={2} maxLength={500} value={form.header_text}
              placeholder={selectedStructure?.name ?? ''} onChange={e => setForm(f => ({ ...f, header_text: e.target.value }))} />
          </Form.Group>
          <Form.Group className="mb-3">
            <Form.Label className="fw-semibold">{t('pdfTpl.footer', 'Testo piè di pagina')}</Form.Label>
            <Form.Control as="textarea" rows={2} maxLength={500} value={form.footer_text}
              onChange={e => setForm(f => ({ ...f, footer_text: e.target.value }))} />
          </Form.Group>
          <Form.Group>
            <Form.Label className="fw-semibold">{t('pdfTpl.color', 'Colore principale')}</Form.Label>
            <div className="d-flex gap-2"><Form.Control type="color" style={{ width: 56 }} value={form.primary_color}
              onChange={e => setForm(f => ({ ...f, primary_color: e.target.value.toUpperCase() }))} />
              <Form.Control value={form.primary_color} style={{ maxWidth: 130 }}
                onChange={e => /^#[0-9A-Fa-f]{0,6}$/.test(e.target.value) && setForm(f => ({ ...f, primary_color: e.target.value.toUpperCase() }))} />
            </div>
          </Form.Group>
        </Col>
        <Col lg={6}>
          <Form.Label className="fw-semibold">{t('pdfTpl.preview', 'Anteprima')}</Form.Label>
          <div className="border shadow-sm bg-white mx-auto" style={{ aspectRatio: '210 / 297', maxWidth: 390, position: 'relative', overflow: 'hidden' }}>
            <div className="d-flex align-items-center px-4 text-white" style={{ height: '20%', background: form.primary_color }}>
              {form.logo_data_url && <img src={form.logo_data_url} alt="Logo" style={{ width: '24%', height: '70%', objectFit: 'contain' }} />}
              <div className="text-center flex-grow-1"><div className="fw-bold">{form.header_text || selectedStructure?.name}</div><small>{t('pdf.titleMonthlyShifts', 'Report Turni')}</small></div>
            </div>
            <div className="p-4">{[0, 1, 2, 3].map(i => <div key={i} className="mb-2" style={{ height: 18, background: i % 2 ? '#fff' : `${form.primary_color}18`, borderBottom: '1px solid #ddd' }} />)}</div>
            <div className="position-absolute bottom-0 w-100 text-center text-muted small px-3 pb-2">{form.footer_text || t('pdfTpl.previewFooter', 'Data di generazione e numero pagina')}</div>
          </div>
        </Col>
      </Row>
      </fieldset>
    </Modal.Body>
    <Modal.Footer><Button variant="secondary" onClick={onClose} disabled={saving}>{t('btn.cancel', 'Annulla')}</Button>
      <Button onClick={save} disabled={saving || !structureId || !selectionAvailable}>{saving ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.save', 'Salva')}</>}</Button>
    </Modal.Footer>
  </Modal>
}

export default function PdfTemplateSection() {
  const { t, i18n } = useTranslation()
  const [structures, setStructures] = useState<Structure[]>([])
  const [templates, setTemplates] = useState<PdfTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [editor, setEditor] = useState<PdfTemplate | false | null>(null)
  const [deleting, setDeleting] = useState<PdfTemplate | null>(null)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const loadGeneration = useRef(0)

  const load = useCallback(async () => {
    const generation = ++loadGeneration.current
    setLoading(true)
    try {
      const companies = await structuresApi.list()
      const all = await Promise.all(companies.map(company => pdfTemplatesApi.get(company.id)))
      if (generation !== loadGeneration.current) return
      setStructures(companies)
      setTemplates(all.filter(item => item.id > 0))
    } catch { if (generation === loadGeneration.current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) }
    finally { if (generation === loadGeneration.current) setLoading(false) }
  }, [i18n])
  useEffect(() => {
    const generationRef = loadGeneration
    load()
    return () => { generationRef.current++ }
  }, [load])

  async function confirmDelete() {
    if (!deleting) return
    setDeleteBusy(true)
    try {
      await pdfTemplatesApi.delete(deleting.structure_id)
      toast.success(t('pdfTpl.deleted', 'Template PDF eliminato.'))
      setDeleting(null); await load()
    } catch { toast.error(t('msg.error.delete', 'Errore durante l’eliminazione.')) }
    finally { setDeleteBusy(false) }
  }

  const usedIds = new Set(templates.map(item => item.structure_id))
  return <div>
    <div className="d-flex justify-content-between align-items-center mb-3">
      <h5 className="mb-0">{t('config.menu.pdfTemplate', 'Template PDF')}</h5>
      <Button size="sm" onClick={() => setEditor(false)} disabled={structures.length === templates.length}>
        <FontAwesomeIcon icon={faFileCirclePlus} className="me-2" />{t('btn.add', 'Aggiungi')}
      </Button>
    </div>
    {loading ? <div className="text-center py-5"><Spinner /></div> : templates.length === 0
      ? <Alert variant="light" className="border text-muted">{t('pdfTpl.noFormats', 'Nessun formato PDF configurato.')}</Alert>
      : <Table responsive bordered hover size="sm" className="align-middle" style={{ maxWidth: 1100 }}>
          <thead className="table-dark"><tr><th style={{ width: 55 }}>ID</th><th>{t('pdfTpl.structure', 'Azienda')}</th><th>{t('pdfTpl.logo', 'Logo')}</th><th>{t('pdfTpl.header', 'Intestazione')}</th><th>{t('pdfTpl.footer', 'Footer')}</th><th>{t('pdfTpl.color', 'Colore')}</th><th style={{ width: 95 }}>{t('table.actions', 'Azioni')}</th></tr></thead>
          <tbody>{templates.map(item => <tr key={item.id}>
            <td className="text-muted">{item.id}</td>
            <td className="fw-semibold">{structures.find(s => s.id === item.structure_id)?.name ?? `#${item.structure_id}`}</td>
            <td>{item.logo_data_url ? <img src={item.logo_data_url} alt="" style={{ width: 48, height: 28, objectFit: 'contain' }} /> : '—'}</td>
            <td>{item.header_text || '—'}</td><td className="text-truncate" style={{ maxWidth: 230 }}>{item.footer_text || '—'}</td>
            <td><span className="d-inline-block border align-middle me-1" style={{ width: 18, height: 18, background: item.primary_color }} />{item.primary_color}</td>
            <td><Button variant="link" size="sm" className="p-0 me-3" title={t('tooltip.edit', 'Modifica')} onClick={() => setEditor(item)}><FontAwesomeIcon icon={faPen} /></Button>
              <Button variant="link" size="sm" className="p-0 text-danger" title={t('tooltip.delete', 'Elimina')} onClick={() => setDeleting(item)}><FontAwesomeIcon icon={faTrash} /></Button></td>
          </tr>)}</tbody>
        </Table>}
    <PdfTemplateModal show={editor !== null} template={editor || null} structures={structures} usedStructureIds={usedIds} onClose={() => setEditor(null)} onSaved={load} />
    <ConfirmModal show={deleting !== null} title={t('pdfTpl.deleteTitle', 'Elimina template PDF')}
      message={`${t('pdfTpl.deleteConfirm', 'Eliminare il template PDF di')} “${structures.find(s => s.id === deleting?.structure_id)?.name ?? ''}”?`}
      confirmLabel={t('btn.delete', 'Elimina')} confirmVariant="danger" loading={deleteBusy}
      onConfirm={confirmDelete} onClose={() => setDeleting(null)} />
  </div>
}
