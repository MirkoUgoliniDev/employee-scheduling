/**
 * @file StructuresPage.tsx
 * @brief Organizational-structure management page.
 *
 * @details
 * The structure with id=1 ("Default") cannot be deleted (button disabled).
 * If the active structure is deleted, the Default structure is selected automatically.
 * The current structure is marked with an "Active" badge in the table.
 */

import { useEffect, useState, useCallback } from 'react'
import { Button, Table, Spinner, Badge } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faTrash, faPlus } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { structuresApi, type Structure } from '../api/structures'
import { errorCode } from '../api/client'
import { useAppStore } from '../store/useAppStore'
import StructureModal from '../components/structures/StructureModal'
import ConfirmModal from '../components/ConfirmModal'

const DEFAULT_ID = 1

interface StructuresPageProps {
  embedded?: boolean
}

export default function StructuresPage({ embedded = false }: StructuresPageProps) {
  const { t } = useTranslation()
  const { currentStructure, setCurrentStructure } = useAppStore()
  const [structures, setStructures] = useState<Structure[]>([])
  const [loading, setLoading] = useState(false)

  const [modalTarget, setModalTarget] = useState<Structure | null | false>(false) // false=closed, null=add, Structure=edit
  const [deleteTarget, setDeleteTarget] = useState<Structure | null>(null)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    structuresApi.list()
      .then(setStructures)
      .catch(() => toast.error(t('structures.loadError', 'Errore nel caricamento delle strutture.')))
      .finally(() => setLoading(false))
  }, [t])

  useEffect(() => { load() }, [load])

  async function confirmDelete() {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await structuresApi.delete(deleteTarget.id)
      toast.success(t('msg.success.deleteStructure', 'Struttura eliminata.'))
      // If it was the active one, switch back to Default
      if (currentStructure?.id === deleteTarget.id) {
        setCurrentStructure(structures.find(s => s.id === DEFAULT_ID) ?? null)
      }
      setDeleteTarget(null)
      load()
    } catch (err) {
      const code = errorCode(err)
      toast.error(
        code === 'STRUCTURE_IN_USE'
          ? t('msg.error.deleteStructure', 'Impossibile eliminare: la struttura è in uso.')
          : t('msg.error.save', 'Errore durante il salvataggio. Riprova più tardi.')
      )
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{embedded ? t('config.menu.structures', 'Strutture') : t('nav.structures', 'Strutture')}</h5>
        <Button
          variant="primary"
          size="sm"
          title={t('structures.addTooltip', 'Aggiungi una nuova struttura')}
          onClick={() => setModalTarget(null)}
        >
          <FontAwesomeIcon icon={faPlus} className="me-2" />
          {t('btn.add', 'Aggiungi')}
        </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : (
        <Table hover bordered responsive size="sm" style={{ maxWidth: 1100 }}>
          <thead className="table-dark">
            <tr>
              <th style={{ width: 50 }}>{t('table.id', 'ID')}</th>
              <th>{t('table.name', 'Nome')}</th>
              <th>{t('table.address', 'Indirizzo')}</th>
              <th>{t('table.phone', 'Telefono')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Azioni')}</th>
            </tr>
          </thead>
          <tbody>
            {structures.length === 0 ? (
              <tr><td colSpan={5} className="text-center text-muted">{t('structures.noResults', 'Nessuna struttura.')}</td></tr>
            ) : structures.map(s => (
              <tr key={s.id}>
                <td className="text-muted">{s.id}</td>
                <td>
                  <strong>{s.name}</strong>
                  {currentStructure?.id === s.id && (
                    <Badge bg="success" className="ms-2">{t('structures.active', 'Attiva')}</Badge>
                  )}
                </td>
                <td>{s.address ?? '—'}</td>
                <td>{s.phone ?? '—'}</td>
                <td>
                  <Button
                    variant="link" size="sm" className="p-0 me-3 text-primary"
                    title={t('tooltip.edit', 'Modifica')}
                    onClick={() => setModalTarget(s)}
                  >
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  <Button
                    variant="link" size="sm" className="p-0 text-danger"
                    disabled={s.id === DEFAULT_ID}
                    title={s.id === DEFAULT_ID
                      ? t('structures.defaultDeleteTooltip', 'La struttura predefinita non può essere eliminata')
                      : t('tooltip.delete', 'Elimina')}
                    onClick={() => setDeleteTarget(s)}
                  >
                    <FontAwesomeIcon icon={faTrash} className={s.id === DEFAULT_ID ? 'opacity-25' : ''} />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <StructureModal
        show={modalTarget !== false}
        structure={modalTarget || null}
        onClose={() => setModalTarget(false)}
        onSaved={load}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('structures.deleteTitle', 'Elimina struttura')}
        message={`${t('confirm.deleteStructure', 'Eliminare questa struttura?')} "${deleteTarget?.name}"`}
        confirmLabel={t('btn.delete', 'Elimina')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  )
}
