/**
 * @file EmployeeDatesModal.tsx
 * @brief Modal for managing an employee's availability time ranges.
 *
 * @details
 * Supports adding, editing, and deleting three types of time ranges:
 * Preferred, Undesired, Unavailable.
 * Ranges are shown in separate tabs by type (+ an "All" tab).
 * Saving is batched: all changed/new rows are persisted with `Promise.all`.
 * New rows (isNew=true) use POST; existing rows use PUT.
 * The `_key` key is a local React counter (not the database ID).
 */

import { useEffect, useLayoutEffect, useState, useCallback, useRef } from 'react'
import { Modal, Button, Table, Form, Spinner, Tabs, Tab, Badge } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPlus, faTrash, faFloppyDisk } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { datesApi, DATE_TYPES, type EmployeeDate } from '../../api/dates'
import ConfirmModal from '../ConfirmModal'

function toInputValue(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

interface DateRow extends EmployeeDate {
  isNew?: boolean
  _key: number
}

let _keyCounter = 0
const nextKey = () => ++_keyCounter

interface Props {
  show: boolean
  employeeId: number | null
  employeeName: string
  structureId: number
  onClose: () => void
}

export default function EmployeeDatesModal({ show, employeeId, employeeName, structureId, onClose }: Props) {
  const { t, i18n } = useTranslation()
  const [rows, setRows] = useState<DateRow[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<DateRow | null>(null)
  const [deleting, setDeleting] = useState(false)
  const loadGeneration = useRef(0)
  const sessionIdentity = `${show}:${structureId}:${employeeId ?? 'none'}`
  const currentSession = useRef(sessionIdentity)
  const operationInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    currentSession.current = sessionIdentity
    operationInFlight.current = null
    setSaving(false)
    setDeleting(false)
    setDeleteTarget(null)
  }, [sessionIdentity])

  const loadDates = useCallback(() => {
    if (!employeeId) return
    const generation = ++loadGeneration.current
    setLoading(true)
    return datesApi.listForEmployee(employeeId, structureId)
      .then(dates => { if (generation === loadGeneration.current) setRows(dates.map(d => ({ ...d, _key: nextKey() }))) })
      .catch(() => { if (generation === loadGeneration.current) toast.error(i18n.t('toast.errorLoadDates', 'Errore nel caricamento delle date.')) })
      .finally(() => { if (generation === loadGeneration.current) setLoading(false) })
  }, [employeeId, structureId, i18n])

  useEffect(() => {
    const generationRef = loadGeneration
    if (show && employeeId) loadDates()
    if (!show) setRows([])
    return () => { generationRef.current++ }
  }, [show, employeeId, loadDates])

  function updateRow(key: number, field: keyof EmployeeDate, value: string | number) {
    setRows(r => r.map(row => row._key === key ? { ...row, [field]: value } : row))
  }

  function addRow() {
    if (!employeeId) return
    const now = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const startStr = `${now.getFullYear()}-${pad(now.getMonth()+1)}-${pad(now.getDate())}T08:00`
    const endStr   = `${now.getFullYear()}-${pad(now.getMonth()+1)}-${pad(now.getDate())}T16:00`
    setRows(r => [...r, {
      id: 0, employee_id: employeeId,
      dateStart: startStr, dateEnd: endStr,
      dateTypeId: 3, isNew: true, _key: nextKey()
    }])
  }

  async function save() {
    if (!employeeId) return
    if (operationInFlight.current) return
    const operation = Symbol('dates-save')
    operationInFlight.current = operation
    setSaving(true)
    const submitSession = sessionIdentity
    try {
      const payload = rows.map(row => ({
        employee_id: employeeId, dateStart: row.dateStart, dateEnd: row.dateEnd, dateTypeId: row.dateTypeId,
      }))
      await datesApi.batchSave(employeeId, payload, structureId)
      if (currentSession.current !== submitSession) return
      toast.success(t('toast.datesSaved', 'Date salvate!'))
      await loadDates()
    } catch {
      if (currentSession.current === submitSession) toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
    } finally {
      if (operationInFlight.current === operation) {
        operationInFlight.current = null
        if (currentSession.current === submitSession) setSaving(false)
      }
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    if (operationInFlight.current) return
    const operation = Symbol('date-delete')
    operationInFlight.current = operation
    setDeleting(true)
    const deleteSession = sessionIdentity
    try {
      if (!deleteTarget.isNew) await datesApi.delete(deleteTarget.id, structureId)
      if (currentSession.current !== deleteSession) return
      setRows(r => r.filter(row => row._key !== deleteTarget._key))
      setDeleteTarget(null)
      toast.success(t('toast.dateDeleted', 'Data eliminata.'))
    } catch {
      if (currentSession.current === deleteSession) toast.error(t('toast.errorDelete', "Errore durante l'eliminazione."))
    } finally {
      if (operationInFlight.current === operation) {
        operationInFlight.current = null
        if (currentSession.current === deleteSession) setDeleting(false)
      }
    }
  }

  function DateTable({ typeId }: { typeId: number }) {
    const filtered = typeId === 0 ? rows : rows.filter(r => r.dateTypeId === typeId)
    if (filtered.length === 0)
      return <p className="text-muted mt-2">{t('msg.noDates', 'Nessuna data.')}</p>
    return (
      <Table hover bordered responsive size="sm" className="mt-2">
        <thead className="table-dark">
          <tr>
            <th style={{ width: 50 }}>{t('table.id', 'ID')}</th>
            <th>{t('label.start', 'Inizio')}</th>
            <th>{t('label.end', 'Fine')}</th>
            <th style={{ width: 160 }}>{t('label.type', 'Tipo')}</th>
            <th style={{ width: 40 }}></th>
          </tr>
        </thead>
        <tbody>
          {filtered.map(row => (
            <tr key={row._key}>
              <td className="text-muted align-middle small">{row.isNew ? 'Nuovo' : row.id}</td>
              <td>
                <Form.Control type="datetime-local" size="sm"
                  value={toInputValue(row.dateStart)}
                  onChange={e => updateRow(row._key, 'dateStart', e.target.value)} />
              </td>
              <td>
                <Form.Control type="datetime-local" size="sm"
                  value={toInputValue(row.dateEnd)}
                  onChange={e => updateRow(row._key, 'dateEnd', e.target.value)} />
              </td>
              <td>
                <Form.Select size="sm" value={row.dateTypeId}
                  onChange={e => updateRow(row._key, 'dateTypeId', Number(e.target.value))}>
                  {DATE_TYPES.map(t => <option key={t.id} value={t.id}>{t.label}</option>)}
                </Form.Select>
              </td>
              <td className="text-center align-middle">
                <Button variant="link" size="sm" className="p-0 text-danger" onClick={() => setDeleteTarget(row)}>
                  <FontAwesomeIcon icon={faTrash} />
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </Table>
    )
  }

  const counts = DATE_TYPES.map(t => ({ ...t, count: rows.filter(r => r.dateTypeId === t.id).length }))

  return (
    <>
      <Modal show={show} onHide={() => { if (!saving && !deleting) onClose() }} size="xl" centered>
        <Modal.Header closeButton={!saving && !deleting}>
          <Modal.Title>Disponibilità — {employeeName}</Modal.Title>
        </Modal.Header>

        <Modal.Body style={{ maxHeight: '65vh', overflowY: 'auto' }}>
          <fieldset disabled={saving || deleting} className="border-0 p-0 m-0 w-100">
          {loading ? (
            <div className="text-center py-4"><Spinner /></div>
          ) : (
            <Tabs defaultActiveKey="all" className="mb-1">
              <Tab eventKey="all" title={<>{t('tab.all', 'Tutte')} <Badge bg="secondary">{rows.length}</Badge></>}>
                <DateTable typeId={0} />
              </Tab>
              {counts.map(({ id, label, bg, count }) => (
                <Tab key={id} eventKey={String(id)} title={<>{label} <Badge bg={bg}>{count}</Badge></>}>
                  <DateTable typeId={id} />
                </Tab>
              ))}
            </Tabs>
          )}
          </fieldset>
        </Modal.Body>

        <Modal.Footer>
          <Button variant="outline-primary" size="sm" onClick={addRow} disabled={loading || saving || deleting}>
            <FontAwesomeIcon icon={faPlus} className="me-1" />{t('btn.addRange', 'Aggiungi fascia')}
          </Button>
          <Button variant="secondary" onClick={onClose} disabled={saving || deleting}>{t('btn.close', 'Chiudi')}</Button>
          <Button variant="primary" onClick={save} disabled={saving || deleting || loading}>
            {saving ? <Spinner size="sm" /> : <><FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.save', 'Salva')}</>}
          </Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('confirm.deleteDateTitle', 'Elimina Data')}
        message={t('confirm.deleteDateMessage', 'Eliminare questa fascia oraria?')}
        confirmLabel={t('btn.delete', 'Elimina')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </>
  )
}
