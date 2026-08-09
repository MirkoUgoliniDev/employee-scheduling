/**
 * @file SpecialistsPage.tsx
 * @brief Specialist (clinic physician) management page.
 *
 * @details
 * CRUD records linked to the current structure, using the same style as the Operators page
 * but without skills/dates. Loads the list with `specialistsApi.list()`.
 */

import { useEffect, useState, useCallback, useRef } from 'react'
import { Button, Table, Spinner } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faTrash, faUserPlus } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { specialistsApi, type Specialist } from '../api/specialists'
import { useAppStore } from '../store/useAppStore'
import SpecialistModal from '../components/specialists/SpecialistModal'
import ConfirmModal from '../components/ConfirmModal'

export default function SpecialistsPage() {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const [specialists, setSpecialists] = useState<Specialist[]>([])
  const [loading, setLoading] = useState(false)
  const loadGeneration = useRef(0)
  const currentStructureRef = useRef(structureId)
  const deleteGeneration = useRef(0)
  currentStructureRef.current = structureId

  // Modal add/edit
  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)

  // Modal confirm delete
  const [deleteTarget, setDeleteTarget] = useState<{ specialist: Specialist; structureId: number } | null>(null)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(() => {
    const generation = ++loadGeneration.current
    setLoading(true)
    specialistsApi.list(structureId)
      .then(items => { if (generation === loadGeneration.current) setSpecialists(items) })
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
      await specialistsApi.delete(deleteTarget.specialist.id, deleteTarget.structureId)
      if (generation !== deleteGeneration.current || deleteTarget.structureId !== currentStructureRef.current) return
      toast.success(t('toast.specialistDeleted', 'Specialista eliminato.'))
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
        <h5 className="mb-0">{t('nav.specialists', 'Specialisti')}</h5>
        <Button
          variant="primary"
          size="sm"
          title={t('btn.addSpecialist', 'Aggiungi Specialista')}
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
              <th>{t('label.email', 'Email')}</th>
              <th style={{ width: 80 }}>{t('label.active', 'Attivo')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Azioni')}</th>
            </tr>
          </thead>
          <tbody>
            {specialists.length === 0 ? (
              <tr><td colSpan={6} className="text-center text-muted">{t('msg.noSpecialists', 'Nessuno specialista.')}</td></tr>
            ) : specialists.map(spec => (
              <tr key={spec.id} className={spec.active === false ? 'table-secondary' : undefined}>
                <td><code>{spec.code}</code></td>
                <td>{spec.firstName}</td>
                <td>{spec.lastName}</td>
                <td>{spec.email || '—'}</td>
                <td className="text-center">
                  {spec.active === false
                    ? <span className="badge bg-secondary">{t('label.inactive', 'No')}</span>
                    : <span className="badge bg-success">{t('label.activeYes', 'Sì')}</span>}
                </td>
                <td>
                  <Button
                    variant="link" size="sm" className="p-0 me-3 text-primary"
                    title={t('btn.edit', 'Modifica')}
                    onClick={() => openEdit(spec.id)}
                  >
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  <Button
                    variant="link" size="sm" className="p-0 text-danger"
                    title={t('tooltip.delete', 'Elimina')}
                    onClick={() => setDeleteTarget({ specialist: spec, structureId })}
                  >
                    <FontAwesomeIcon icon={faTrash} />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <SpecialistModal
        show={modalOpen}
        specialistId={editId}
        structureId={structureId}
        onClose={() => setModalOpen(false)}
        onSaved={load}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('confirm.deleteSpecialistTitle', 'Elimina Specialista')}
        message={`${t('confirm.deletePrefix', 'Sei sicuro di voler eliminare')} ${deleteTarget?.specialist.firstName ?? ''} ${deleteTarget?.specialist.lastName ?? ''}?`}
        confirmLabel={t('btn.delete', 'Elimina')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  )
}
