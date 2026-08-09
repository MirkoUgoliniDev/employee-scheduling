/**
 * @file EmployeesPage.tsx
 * @brief Employee management page.
 *
 * @details
 * On load, performs two calls in parallel:
 * - `employeesApi.list()` — employee list with assigned skills
 * - `shiftsApi.usage()` — only IDs referenced by shifts
 *
 * The delete column shows a disabled (pink) icon for employees with at least one
 * assigned shift, preventing deletions that would violate referential integrity.
 */

import { useEffect, useState, useCallback, useRef } from 'react'
import { Button, Table, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faTrash, faUserPlus } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { employeesApi, type Employee } from '../api/employees'
import { shiftsApi } from '../api/shifts'
import { specialistsApi, type Specialist } from '../api/specialists'
import { affinityApi, AFFINITY_AVOID, AFFINITY_INCOMPATIBLE, type SpecialistAffinity } from '../api/affinity'
import { useAppStore } from '../store/useAppStore'
import EmployeeModal from '../components/employees/EmployeeModal'
import ConfirmModal from '../components/ConfirmModal'

export default function EmployeesPage() {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const [employees, setEmployees] = useState<Employee[]>([])
  const [usedEmployeeIds, setUsedEmployeeIds] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(false)
  // Operator↔Specialist compatibility: structure relationships + specialist records
  const [affinities, setAffinities] = useState<SpecialistAffinity[]>([])
  const [specialists, setSpecialists] = useState<Specialist[]>([])
  const loadGeneration = useRef(0)
  const currentStructureRef = useRef(structureId)
  const deleteGeneration = useRef(0)
  currentStructureRef.current = structureId

  // Modal add/edit
  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)

  // Modal confirm delete
  const [deleteTarget, setDeleteTarget] = useState<{ employee: Employee; structureId: number } | null>(null)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(() => {
    const generation = ++loadGeneration.current
    setLoading(true)
    Promise.all([
      employeesApi.list(structureId),
      shiftsApi.usage(structureId),
      affinityApi.byStructure(structureId),
      specialistsApi.list(structureId),
    ])
      .then(([emps, usage, affs, specs]) => {
        if (generation !== loadGeneration.current) return
        setEmployees(emps)
        setUsedEmployeeIds(new Set(usage.employeeIds))
        setAffinities(affs)
        setSpecialists(specs)
      })
      .catch(() => { if (generation === loadGeneration.current) toast.error(i18n.t('toast.errorLoad', 'Errore nel caricamento.')) })
      .finally(() => { if (generation === loadGeneration.current) setLoading(false) })
  }, [structureId, i18n])

  useEffect(() => {
    const generationRef = loadGeneration
    load()
    return () => { generationRef.current++ }
  }, [load])

  useEffect(() => {
    deleteGeneration.current++
    setModalOpen(false)
    setEditId(null)
    setDeleteTarget(null)
    setDeleting(false)
  }, [structureId])

  /** @brief Names of specialists related to the operator for the given type. */
  function affinityNames(operatorId: number, type: number): string[] {
    return affinities
      .filter(a => a.operatorId === operatorId && a.type === type)
      .map(a => {
        const sp = specialists.find(s => s.id === a.specialistId)
        return sp ? `${sp.lastName} ${sp.firstName}` : `#${a.specialistId}`
      })
      .sort()
  }

  function openAdd() {
    setEditId(null)
    setModalOpen(true)
  }

  function openEdit(id: number) {
    setEditId(id)
    setModalOpen(true)
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    const generation = ++deleteGeneration.current
    setDeleting(true)
    try {
      await employeesApi.delete(deleteTarget.employee.id, deleteTarget.structureId)
      if (generation !== deleteGeneration.current || deleteTarget.structureId !== currentStructureRef.current) return
      toast.success(t('toast.employeeDeleted', 'Operatore eliminato.'))
      setDeleteTarget(null)
      load()
    } catch {
      if (generation === deleteGeneration.current && deleteTarget.structureId === currentStructureRef.current) toast.error(t('toast.errorDelete', "Errore durante l'eliminazione."))
    } finally {
      if (generation === deleteGeneration.current) setDeleting(false)
    }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('nav.employees', 'Operatori')}</h5>
        <Button
          variant="primary"
          size="sm"
          title={t('btn.addEmployee', 'Aggiungi Operatore')}
          onClick={openAdd}
        >
          <FontAwesomeIcon icon={faUserPlus} className="me-2" />
          {t('btn.add', 'Aggiungi')}
        </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : (
        <Table hover bordered responsive size="sm">
          <thead className="table-dark">
            <tr>
              <th style={{ width: 140 }}>{t('table.code', 'Codice')}</th>
              <th style={{ width: 220 }}>{t('label.firstName', 'Nome')}</th>
              <th style={{ width: 220 }}>{t('label.lastName', 'Cognome')}</th>
              <th style={{ width: 260 }}>{t('label.email', 'Email')}</th>
              <th>{t('label.skills', 'Competenze')}</th>
              <th>{t('col.avoidSpecialists', 'Spec. da evitare')}</th>
              <th>{t('col.incompatibleSpecialists', 'Spec. incompatibili')}</th>
              <th style={{ width: 80 }}>{t('label.active', 'Attivo')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Azioni')}</th>
            </tr>
          </thead>
          <tbody>
            {employees.length === 0 ? (
              <tr><td colSpan={9} className="text-center text-muted">{t('msg.noEmployees', 'Nessun operatore.')}</td></tr>
            ) : employees.map(emp => (
              <tr key={emp.id} className={emp.active === false ? 'table-secondary' : undefined}>
                <td><code>{emp.code}</code></td>
                <td>{emp.firstName}</td>
                <td>{emp.lastName}</td>
                <td>{emp.email || '—'}</td>
                <td>
                  {(emp.skills ?? []).filter(s => s.used).map(s => (
                    <span key={s.id} className="badge bg-secondary me-1">{t('skill.' + s.id, s.name)}</span>
                  ))}
                </td>
                <td>
                  {affinityNames(emp.id, AFFINITY_AVOID).map(name => (
                    <span key={name} className="badge bg-warning text-dark me-1">⚠ {name}</span>
                  ))}
                </td>
                <td>
                  {affinityNames(emp.id, AFFINITY_INCOMPATIBLE).map(name => (
                    <span key={name} className="badge bg-danger me-1">✗ {name}</span>
                  ))}
                </td>
                <td className="text-center">
                  {emp.active === false
                    ? <span className="badge bg-secondary">{t('label.inactive', 'No')}</span>
                    : <span className="badge bg-success">{t('label.activeYes', 'Sì')}</span>}
                </td>
                <td>
                  <Button
                    variant="link" size="sm" className="p-0 me-3 text-primary"
                    title={t('tooltip.editEmployee', 'Modifica')}
                    onClick={() => openEdit(emp.id)}
                  >
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  {usedEmployeeIds.has(emp.id) ? (
                    <FontAwesomeIcon
                      icon={faTrash}
                      style={{ color: '#f5a0a0', cursor: 'not-allowed', fontSize: '0.85rem' }}
                      title={t('tooltip.employeeHasShifts', 'Operatore con turni assegnati — impossibile eliminare')}
                    />
                  ) : (
                    <Button
                      variant="link" size="sm" className="p-0 text-danger"
                      title={t('tooltip.delete', 'Elimina')}
                      onClick={() => setDeleteTarget({ employee: emp, structureId })}
                    >
                      <FontAwesomeIcon icon={faTrash} />
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <EmployeeModal
        show={modalOpen}
        employeeId={editId}
        structureId={structureId}
        onClose={() => setModalOpen(false)}
        onSaved={load}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('confirm.deleteEmployeeTitle', 'Elimina Operatore')}
        message={`${t('confirm.deletePrefix', 'Sei sicuro di voler eliminare')} ${deleteTarget?.employee.firstName ?? ''} ${deleteTarget?.employee.lastName ?? ''}?`}
        confirmLabel={t('btn.delete', 'Elimina')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  )
}
