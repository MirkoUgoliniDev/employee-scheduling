/**
 * @file LocationShiftsModal.tsx
 * @brief Modal listing a location's shifts, with add, edit, and delete support.
 *
 * @details
 * Receives the complete shift list from the parent page and filters by `location_id`.
 * Supporta:
 * - Table view with start/end, assigned operator, and skills
 * - Add new shift (opens ShiftModal with a preselected location)
 * - Edit an existing shift (opens ShiftModal in edit mode)
 * - Deletion with ConfirmModal
 * Calls `onChanged()` after every operation to reload the timeline.
 */

import { useState } from 'react'
import { Modal, Button, Table, Badge } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPlus, faTrash, faPencilAlt } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { shiftsApi, type Shift } from '../../api/shifts'
import ConfirmModal from '../ConfirmModal'
import ShiftModal from '../shifts/ShiftModal'

interface Props {
  show: boolean
  locationId: number | null
  locationName: string
  shifts: Shift[]
  structureId: number
  onClose: () => void
  onChanged: () => void   // reload the timeline after changes
}

function fmtDate(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getDate())}/${pad(d.getMonth()+1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default function LocationShiftsModal({ show, locationId, locationName, shifts, structureId, onClose, onChanged }: Props) {
  const { t } = useTranslation()
  const [deleteTarget, setDeleteTarget] = useState<number | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [shiftModalOpen, setShiftModalOpen] = useState(false)
  const [editShiftId, setEditShiftId] = useState<number | null>(null)

  const locationShifts = shifts.filter(s => s.location_id === locationId)

  async function confirmDelete() {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await shiftsApi.delete(deleteTarget, structureId)
      setDeleteTarget(null)
      toast.success(t('toast.shiftDeleted', 'Turno eliminato.'))
      onChanged()
    } catch {
      toast.error(t('toast.errorDelete', "Errore durante l'eliminazione del turno."))
    } finally {
      setDeleting(false)
    }
  }

  function openEdit(shiftId: number) {
    setEditShiftId(shiftId)
    setShiftModalOpen(true)
  }

  function openAdd() {
    setEditShiftId(null)
    setShiftModalOpen(true)
  }

  return (
    <>
      <Modal show={show} onHide={onClose} size="xl" centered>
        <Modal.Header closeButton>
          <Modal.Title>Turni — {locationName}</Modal.Title>
        </Modal.Header>

        <Modal.Body style={{ maxHeight: '65vh', overflowY: 'auto' }}>
          {locationShifts.length === 0 ? (
            <p className="text-muted">{t('msg.noShiftsForLocation', 'Nessun turno per questa sede.')}</p>
          ) : (
            <Table hover bordered responsive size="sm">
              <thead className="table-dark">
                <tr>
                  <th style={{ width: 50 }}>{t('table.id', 'ID')}</th>
                  <th>{t('label.start', 'Inizio')}</th>
                  <th>{t('label.end', 'Fine')}</th>
                  <th>{t('label.operator', 'Operatore')}</th>
                  <th>{t('label.requiredSkills', 'Skills richieste')}</th>
                  <th>{t('label.optionalSkills', 'Skills opzionali')}</th>
                  <th style={{ width: 80 }}></th>
                </tr>
              </thead>
              <tbody>
                {locationShifts
                  .slice()
                  .sort((a, b) => new Date(a.start).getTime() - new Date(b.start).getTime())
                  .map(shift => {
                    const reqSkills = (shift.requiredSkills ?? []).filter(s => s.used)
                    const optSkills = (shift.optionalSkills ?? []).filter(s => s.used)
                    return (
                      <tr key={shift.id}>
                        <td className="align-middle text-muted small">{shift.id}</td>
                        <td className="align-middle">{fmtDate(shift.start)}</td>
                        <td className="align-middle">{fmtDate(shift.end)}</td>
                        <td className="align-middle">
                          {shift.employee
                            ? shift.employee.fullName
                            : <Badge bg="danger">{t('msg.unassigned', 'Non assegnato')}</Badge>}
                        </td>
                        <td className="align-middle">
                          {reqSkills.length > 0
                            ? reqSkills.map((s, i) => <Badge key={i} bg="primary" className="me-1 mb-1">{s.name}</Badge>)
                            : <span className="text-muted small">—</span>}
                        </td>
                        <td className="align-middle">
                          {optSkills.length > 0
                            ? optSkills.map((s, i) => <Badge key={i} bg="warning" text="dark" className="me-1 mb-1">{s.name}</Badge>)
                            : <span className="text-muted small">—</span>}
                        </td>
                        <td className="align-middle text-center">
                          <Button variant="link" size="sm" className="p-0 me-2 text-primary" onClick={() => openEdit(shift.id)}>
                            <FontAwesomeIcon icon={faPencilAlt} />
                          </Button>
                          <Button variant="link" size="sm" className="p-0 text-danger" onClick={() => setDeleteTarget(shift.id)}>
                            <FontAwesomeIcon icon={faTrash} />
                          </Button>
                        </td>
                      </tr>
                    )
                  })}
              </tbody>
            </Table>
          )}
        </Modal.Body>

        <Modal.Footer>
          <Button variant="outline-primary" size="sm" onClick={openAdd}>
            <FontAwesomeIcon icon={faPlus} className="me-1" />{t('btn.addShift', 'Aggiungi turno')}
          </Button>
          <Button variant="secondary" onClick={onClose}>{t('btn.close', 'Chiudi')}</Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('confirm.deleteShiftTitle', 'Elimina Turno')}
        message={t('confirm.deleteShiftMessage', 'Eliminare questo turno?')}
        confirmLabel={t('btn.delete', 'Elimina')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />

      <ShiftModal
        show={shiftModalOpen}
        shiftId={editShiftId}
        prefillLocationId={editShiftId === null ? (locationId ?? undefined) : undefined}
        structureId={structureId}
        onClose={() => setShiftModalOpen(false)}
        onSaved={() => { setShiftModalOpen(false); onChanged() }}
        onDeleted={() => { setShiftModalOpen(false); onChanged() }}
      />
    </>
  )
}
