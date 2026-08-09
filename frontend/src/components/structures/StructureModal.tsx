/**
 * @file StructureModal.tsx
 * @brief Modal for adding and editing an organizational structure.
 *
 * @details
 * Works in both "add" (structure=null) and "edit" (structure=object) modes.
 * Fields: name (required), address, and phone number.
 */

import { useEffect, useState } from 'react'
import { Modal, Button, Form, Spinner } from 'react-bootstrap'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { structuresApi, type Structure } from '../../api/structures'
import { errorCode } from '../../api/client'

/**
 * @brief StructureModal component props.
 */
interface Props {
  show: boolean
  /** @brief Structure to edit, or `null` to create a new structure. */
  structure: Structure | null
  onClose: () => void
  onSaved: () => void
}

const EMPTY = { name: '', address: '', phone: '' }

export default function StructureModal({ show, structure, onClose, onSaved }: Props) {
  const { t } = useTranslation()
  const isEdit = structure !== null
  const [form, setForm] = useState(EMPTY)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!show) return
    setForm(isEdit
      ? { name: structure!.name, address: structure!.address ?? '', phone: structure!.phone ?? '' }
      : EMPTY
    )
  }, [show, structure, isEdit])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true)
    const payload = { name: form.name.trim(), address: form.address.trim(), phone: form.phone.trim() }
    try {
      if (isEdit) {
        await structuresApi.update(structure!.id, { ...structure!, ...payload })
      } else {
        await structuresApi.add(payload)
      }
      toast.success(isEdit ? t('toast.structureUpdated', 'Struttura aggiornata.') : t('toast.structureAdded', 'Struttura aggiunta.'))
      onSaved()
      onClose()
    } catch (err) {
      const code = errorCode(err)
      toast.error(
        code === 'STRUCTURE_NAME_REQUIRED'
          ? t('msg.warning.structureNameRequired', 'Il nome della struttura è obbligatorio.')
          : t('msg.error.save', 'Errore durante il salvataggio. Riprova più tardi.')
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal show={show} onHide={onClose} centered>
      <Form onSubmit={handleSubmit}>
        <Modal.Header closeButton>
          <Modal.Title>{isEdit ? t('modal.editStructure', 'Modifica Struttura') : t('modal.addStructure', 'Aggiungi Struttura')}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Form.Group className="mb-3">
            <Form.Label>{t('label.name', 'Nome')} <span className="text-danger">*</span></Form.Label>
            <Form.Control
              required
              value={form.name}
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
            />
          </Form.Group>
          <Form.Group className="mb-3">
            <Form.Label>{t('label.address', 'Indirizzo')}</Form.Label>
            <Form.Control
              value={form.address}
              onChange={e => setForm(f => ({ ...f, address: e.target.value }))}
            />
          </Form.Group>
          <Form.Group>
            <Form.Label>{t('label.phone', 'Telefono')}</Form.Label>
            <Form.Control
              value={form.phone}
              onChange={e => setForm(f => ({ ...f, phone: e.target.value }))}
            />
          </Form.Group>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={onClose} disabled={saving}>{t('btn.cancel', 'Annulla')}</Button>
          <Button type="submit" variant="primary" disabled={saving}>
            {saving ? <Spinner size="sm" /> : isEdit ? t('btn.save', 'Salva') : t('btn.add', 'Aggiungi')}
          </Button>
        </Modal.Footer>
      </Form>
    </Modal>
  )
}
