/**
 * @file LabelsPage.tsx
 * @brief Translation-label (i18n) management page.
 *
 * @details
 * Lists all translation keys with full-text search (key + description)
 * and client-side pagination (15 records per page).
 * The add/edit modal (LabelModal) includes tabs for per-language translations.
 */

import { useEffect, useState, useCallback } from 'react'
import { Button, Table, Spinner, Form, InputGroup, Pagination } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faTrash, faPlus, faMagnifyingGlass, faXmark } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { labelsApi, type Label } from '../api/labels'
import LabelModal from '../components/labels/LabelModal'
import ConfirmModal from '../components/ConfirmModal'
import { useAppStore } from '../store/useAppStore'
import './LabelsPage.css'

const PAGE_SIZE = 15

interface LabelsPageProps {
  embedded?: boolean
}

export default function LabelsPage({ embedded = false }: LabelsPageProps) {
  const { t, i18n } = useTranslation()
  const language = useAppStore(s => s.language)
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  const showKeys = useAppStore(s => s.showTranslationKeys)
  const setShowKeys = useAppStore(s => s.setShowTranslationKeys)
  const [labels, setLabels] = useState<Label[]>([])
  const [loading, setLoading] = useState(false)
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)

  const [modalOpen, setModalOpen] = useState(false)
  const [editLabel, setEditLabel] = useState<Label | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Label | null>(null)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    // UI labels (labels table) + entity-name pseudo-labels
    // (skill.<id>/location.<id>, localizzazioni table) combined in the same list.
    Promise.all([labelsApi.list(), labelsApi.dynamicNames(structureId)])
      .then(([base, dynamic]) => setLabels([...base, ...dynamic]))
      .catch(() => toast.error(t('msg.error.loadLabels', 'Errore durante il caricamento delle etichette.')))
      .finally(() => setLoading(false))
  }, [t, structureId])

  useEffect(() => { load() }, [load])
  useEffect(() => { setPage(1) }, [search])
  function toggleShowKeys(enabled: boolean) {
    setShowKeys(enabled)
    i18n.changeLanguage(enabled ? 'cimode' : language)
  }

  const filtered = labels.filter(l =>
    !search ||
    l.key.toLowerCase().includes(search.toLowerCase()) ||
    l.description.toLowerCase().includes(search.toLowerCase())
  )
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const paginated = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)

  function pageItems() {
    const items: (number | '...')[] = []
    if (totalPages <= 7) {
      for (let i = 1; i <= totalPages; i++) items.push(i)
    } else {
      items.push(1)
      if (page > 3) items.push('...')
      for (let i = Math.max(2, page - 1); i <= Math.min(totalPages - 1, page + 1); i++) items.push(i)
      if (page < totalPages - 2) items.push('...')
      items.push(totalPages)
    }
    return items
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await labelsApi.delete(deleteTarget.id)
      toast.success(t('msg.success.deleteLabel', 'Etichetta eliminata.'))
      setDeleteTarget(null)
      load()
    } catch {
      toast.error(t('toast.errorDelete', "Errore durante l'eliminazione."))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{embedded ? t('config.menu.localizations', 'Localizzazioni') : t('nav.labels', 'Etichette')}</h5>
        <Button
          variant="primary"
          size="sm"
          title={t('labels.addTooltip', 'Aggiungi una nuova etichetta')}
          onClick={() => { setEditLabel(null); setModalOpen(true) }}
        >
          <FontAwesomeIcon icon={faPlus} className="me-2" />{t('btn.add', 'Aggiungi')}
        </Button>
      </div>

      <div className="localization-controls mb-3">
        <Form.Check
          type="switch"
          id="translation-keys-switch"
          className="translation-keys-switch mb-0"
          label={t('labels.showTranslationKeys', 'Mostra chiavi')}
          checked={showKeys}
          onChange={e => toggleShowKeys(e.target.checked)}
          title={showKeys
            ? t('tooltip.hideTranslationKeys', 'Disattiva modalità chiavi')
            : t('tooltip.showTranslationKeys', 'Mostra chiavi di traduzione')}
        />
      </div>

      <div className="mb-3" style={{ maxWidth: 420 }}>
        <InputGroup size="sm">
          <InputGroup.Text><FontAwesomeIcon icon={faMagnifyingGlass} /></InputGroup.Text>
          <Form.Control
            placeholder={t('labels.searchPlaceholder', 'Cerca per chiave o descrizione…')}
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
          {search && (
            <Button variant="outline-secondary" onClick={() => setSearch('')}>
              <FontAwesomeIcon icon={faXmark} />
            </Button>
          )}
        </InputGroup>
        {search && <small className="text-muted ms-1">{t('labels.results', '{{count}} risultato/i', { count: filtered.length })}</small>}
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : (
        <>
          <Table hover bordered responsive size="sm">
            <thead className="table-dark">
              <tr>
                <th style={{ width: 50 }}>#</th>
                <th>{t('table.key', 'Chiave')}</th>
                <th>{t('table.description', 'Descrizione')}</th>
                <th style={{ width: 90 }}>{t('table.actions', 'Azioni')}</th>
              </tr>
            </thead>
            <tbody>
              {paginated.length === 0 ? (
                <tr><td colSpan={4} className="text-center text-muted">{t('labels.noResults', 'Nessuna etichetta trovata.')}</td></tr>
              ) : paginated.map(l => {
                const isEntity = !!l.entityType && l.entityType !== 'labels'
                return (
                <tr key={`${l.entityType ?? 'labels'}-${l.id}`}>
                  <td className="text-muted small align-middle">{isEntity ? '—' : l.id}</td>
                  <td className="align-middle"><code>{l.key}</code></td>
                  <td className="align-middle">{l.description}</td>
                  <td className="align-middle">
                    <Button variant="link" size="sm" className="p-0 me-3 text-primary" onClick={() => { setEditLabel(l); setModalOpen(true) }}>
                      <FontAwesomeIcon icon={faPenToSquare} />
                    </Button>
                    {/* Entity-name pseudo-labels are not deleted from here:
                        the key belongs to the skill/location. */}
                    {!isEntity && (
                      <Button variant="link" size="sm" className="p-0 text-danger" onClick={() => setDeleteTarget(l)}>
                        <FontAwesomeIcon icon={faTrash} />
                      </Button>
                    )}
                  </td>
                </tr>
              )})}
            </tbody>
          </Table>

          {totalPages > 1 && (
            <div className="d-flex align-items-center justify-content-between mt-2">
              <small className="text-muted">
                {(page - 1) * PAGE_SIZE + 1}–{Math.min(page * PAGE_SIZE, filtered.length)} di {filtered.length}
              </small>
              <Pagination size="sm" className="mb-0">
                <Pagination.Prev disabled={page === 1} onClick={() => setPage(p => p - 1)} />
                {pageItems().map((item, i) =>
                  item === '...'
                    ? <Pagination.Ellipsis key={`e${i}`} disabled />
                    : <Pagination.Item key={item} active={page === item} onClick={() => setPage(item as number)}>{item}</Pagination.Item>
                )}
                <Pagination.Next disabled={page === totalPages} onClick={() => setPage(p => p + 1)} />
              </Pagination>
            </div>
          )}
        </>
      )}

      <LabelModal
        show={modalOpen}
        label={editLabel}
        onClose={() => setModalOpen(false)}
        onSaved={() => { setModalOpen(false); load() }}
      />

      <ConfirmModal
        show={deleteTarget !== null}
        title={t('labels.deleteTitle', 'Elimina etichetta')}
        message={`${t('confirm.deleteLabel', 'Eliminare questa etichetta e tutte le sue traduzioni?')} "${deleteTarget?.key}"`}
        confirmLabel={t('btn.delete', 'Elimina')}
        confirmVariant="danger"
        loading={deleting}
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />
    </div>
  )
}
