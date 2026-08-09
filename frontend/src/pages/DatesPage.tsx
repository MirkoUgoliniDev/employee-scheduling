/**
 * @file DatesPage.tsx
 * @brief "Employee date preferences" page — table of employees with date constraints.
 *
 * @details
 * Shows ONLY employees who have at least one constraint (Preferred / Undesired /
 * Unavailable time range), with per-type and total counts.
 * CRUD from the list:
 * - **Edit** (or row click) → opens {@link EmployeeDatesModal} (full editor)
 * - **Delete** → removes ALL employee constraints (with confirmation)
 * - **Add**: selector at the top with structure employees → opens the editor
 * The `?employeeId=X` deep link (from EmployeesPage) opens the editor directly.
 * Counts come from GET /demo-data/employee-dates-summary (a single GROUP BY query).
 */

import { useEffect, useState, useCallback, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Table, Form, Spinner, Badge } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPlus, faTrash, faPen } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { datesApi, type EmployeeDatesSummary } from '../api/dates'
import { employeesApi, type Employee } from '../api/employees'
import { useAppStore } from '../store/useAppStore'
import EmployeeDatesModal from '../components/employees/EmployeeDatesModal'
import ConfirmModal from '../components/ConfirmModal'

export default function DatesPage() {
  const { t } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const [searchParams, setSearchParams] = useSearchParams()

  const [summary, setSummary] = useState<EmployeeDatesSummary[]>([])
  const [employees, setEmployees] = useState<Employee[]>([])
  const [loading, setLoading] = useState(false)
  const [loadedStructureId, setLoadedStructureId] = useState(0)
  const loadRequestRef = useRef(0)
  const currentStructureRef = useRef(structureId)
  const deleteGeneration = useRef(0)
  currentStructureRef.current = structureId

  // "Add" selector + editor modal
  const [pickedEmployee, setPickedEmployee] = useState<number>(0)
  const [modalEmpId, setModalEmpId] = useState<number | null>(null)
  const [modalEmpName, setModalEmpName] = useState('')
  const [modalOpen, setModalOpen] = useState(false)

  // Delete all constraints for an employee
  const [deleteTarget, setDeleteTarget] = useState<{ summary: EmployeeDatesSummary; structureId: number } | null>(null)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(() => {
    const requestId = ++loadRequestRef.current
    if (!structureId) { setSummary([]); setEmployees([]); setLoadedStructureId(0); setLoading(false); return }
    setLoading(true)
    Promise.all([datesApi.summary(structureId), employeesApi.list(structureId)])
      .then(([sum, emps]) => {
        if (requestId === loadRequestRef.current) { setSummary(sum); setEmployees(emps); setLoadedStructureId(structureId) }
      })
      .catch(() => {
        if (requestId === loadRequestRef.current) toast.error(t('toast.errorLoad', 'Errore nel caricamento.'))
      })
      .finally(() => {
        if (requestId === loadRequestRef.current) setLoading(false)
      })
  }, [structureId, t])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    deleteGeneration.current++
    setSummary([])
    setEmployees([])
    setLoadedStructureId(0)
    setPickedEmployee(0)
    setModalOpen(false)
    setModalEmpId(null)
    setModalEmpName('')
    setDeleteTarget(null)
    setDeleting(false)
  }, [structureId])

  // Deep link ?employeeId=X&structureId=Y: Y prevents a link left in the URL from being
  // accidentally applied to a different structure. To guarantee complete isolation,
  // links without Y are consumed without opening modals.
  useEffect(() => {
    if (!structureId || loadedStructureId !== structureId) return
    const preselect = parseInt(searchParams.get('employeeId') ?? '0')
    if (!preselect) return
    const linkedStructureId = parseInt(searchParams.get('structureId') ?? '0')
    const structureMatches = linkedStructureId > 0 && linkedStructureId === structureId
    const employee = structureMatches ? employees.find(e => e.id === preselect) : undefined
    if (employee) {
      setModalEmpId(employee.id)
      setModalEmpName(`${employee.firstName} ${employee.lastName}`)
      setModalOpen(true)
    }
    setSearchParams(current => {
      const next = new URLSearchParams(current)
      next.delete('employeeId')
      next.delete('structureId')
      return next
    }, { replace: true })
  }, [employees, loadedStructureId, searchParams, setSearchParams, structureId])

  function nameOf(employeeId: number): string {
    const e = employees.find(x => x.id === employeeId)
    return e ? `${e.firstName} ${e.lastName}` : ''
  }

  function openEditor(employeeId: number) {
    setModalEmpId(employeeId)
    setModalEmpName(nameOf(employeeId))
    setModalOpen(true)
  }

  function closeEditor() {
    setModalOpen(false)
    setModalEmpId(null)
    load() // counts may have changed
  }

  /** @brief Deletes ALL date constraints for the selected employee. */
  async function confirmDeleteAll() {
    if (!deleteTarget) return
    const generation = ++deleteGeneration.current
    setDeleting(true)
    try {
      const targetStructureId = deleteTarget.structureId
      await datesApi.batchSave(deleteTarget.summary.employee_id, [], targetStructureId)
      if (generation !== deleteGeneration.current || currentStructureRef.current !== targetStructureId) return
      toast.success(t('dates.allDeleted', 'Vincoli eliminati.'))
      setDeleteTarget(null)
      load()
    } catch {
      if (generation === deleteGeneration.current && currentStructureRef.current === deleteTarget.structureId) toast.error(t('toast.errorDelete', "Errore durante l'eliminazione."))
    } finally {
      if (generation === deleteGeneration.current) setDeleting(false)
    }
  }

  if (loading) return <div className="text-center py-5"><Spinner /></div>

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3 flex-wrap gap-2">
        <h5 className="mb-0">{t('dates.title', 'Preferenze date Operatori')}</h5>
        <div className="d-flex gap-2 align-items-center">
          <Form.Select
            size="sm"
            style={{ width: 240 }}
            value={pickedEmployee}
            onChange={e => setPickedEmployee(parseInt(e.target.value))}
          >
            <option value={0}>{t('dates.pickEmployee', '— Seleziona operatore —')}</option>
            {employees.map(emp => (
              <option key={emp.id} value={emp.id}>{emp.firstName} {emp.lastName}</option>
            ))}
          </Form.Select>
          <Button variant="outline-primary" size="sm" disabled={!pickedEmployee} onClick={() => openEditor(pickedEmployee)}>
            <FontAwesomeIcon icon={faPlus} className="me-1" /> {t('btn.add', 'Aggiungi')}
          </Button>
        </div>
      </div>

      {summary.length === 0 ? (
        <p className="text-muted">
          {t('dates.noConstraints', 'Nessun operatore ha vincoli di data. Selezionane uno in alto per aggiungerli.')}
        </p>
      ) : (
        <Table hover bordered responsive size="sm" className="align-middle">
          <thead className="table-dark">
            <tr>
              <th>{t('label.employee', 'Operatore')}</th>
              <th className="text-center" style={{ width: 130 }}>{t('dateType.desired', 'Desiderata')}</th>
              <th className="text-center" style={{ width: 130 }}>{t('dateType.undesired', 'Indesiderata')}</th>
              <th className="text-center" style={{ width: 140 }}>{t('dateType.unavailable', 'Non disponibile')}</th>
              <th className="text-center" style={{ width: 90 }}>{t('table.total', 'Totale')}</th>
              <th className="text-center" style={{ width: 110 }}>{t('table.actions', 'Azioni')}</th>
            </tr>
          </thead>
          <tbody>
            {summary.map(row => (
              <tr key={row.employee_id} style={{ cursor: 'pointer' }} onClick={() => openEditor(row.employee_id)}>
                <td>{row.full_name}</td>
                <td className="text-center">
                  {row.desired > 0 ? <Badge bg="success">{row.desired}</Badge> : <span className="text-muted">—</span>}
                </td>
                <td className="text-center">
                  {row.undesired > 0 ? <Badge bg="warning" text="dark">{row.undesired}</Badge> : <span className="text-muted">—</span>}
                </td>
                <td className="text-center">
                  {row.unavailable > 0 ? <Badge bg="danger">{row.unavailable}</Badge> : <span className="text-muted">—</span>}
                </td>
                <td className="text-center fw-semibold">{row.total}</td>
                <td className="text-center" onClick={e => e.stopPropagation()}>
                  <Button
                    variant="link" size="sm" className="p-0 me-3"
                    title={t('btn.edit', 'Modifica')}
                    onClick={() => openEditor(row.employee_id)}
                  >
                    <FontAwesomeIcon icon={faPen} />
                  </Button>
                  <Button
                    variant="link" size="sm" className="p-0 text-danger"
                    title={t('dates.deleteAll', 'Elimina tutti i vincoli')}
                    onClick={() => setDeleteTarget({ summary: row, structureId })}
                  >
                    <FontAwesomeIcon icon={faTrash} />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <EmployeeDatesModal
        show={modalOpen}
        employeeId={modalEmpId}
        employeeName={modalEmpName}
        structureId={structureId}
        onClose={closeEditor}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('dates.deleteAllTitle', 'Elimina vincoli')}
        message={`${t('dates.deleteAllMsg', 'Eliminare TUTTI i vincoli di data di')} ${deleteTarget?.summary.full_name ?? ''}? (${deleteTarget?.summary.total ?? 0})`}
        confirmLabel={t('btn.delete', 'Elimina')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDeleteAll}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  )
}
