/**
 * @file ConfirmModal.tsx
 * @brief Reusable confirmation modal (replaces `window.confirm()`).
 *
 * @details
 * Used throughout the project for destructive operations (deleting an employee,
 * location, shift, skill, label, or structure).
 * Supports a loading state to disable buttons during the API call.
 */

import { useLayoutEffect, useRef } from 'react'
import { Modal, Button, Spinner } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'

/**
 * @brief ConfirmModal component props.
 */
interface Props {
  show: boolean
  title?: string
  message: string
  confirmLabel?: string
  confirmVariant?: string
  loading?: boolean
  onConfirm: () => void | Promise<void>
  onClose: () => void
}

export default function ConfirmModal({
  show, title, message,
  confirmLabel, confirmVariant = 'danger',
  loading = false, onConfirm, onClose,
}: Props) {
  const { t } = useTranslation()
  const resolvedTitle = title ?? t('confirm.title', 'Conferma')
  const resolvedLabel = confirmLabel ?? t('btn.confirm', 'Conferma')
  const confirmInFlight = useRef<symbol | null>(null)

  useLayoutEffect(() => {
    confirmInFlight.current = null
  }, [show, message, title])

  function handleConfirm() {
    if (loading || confirmInFlight.current) return
    const operation = Symbol('confirm')
    confirmInFlight.current = operation
    try {
      const result = onConfirm()
      Promise.resolve(result).then(
        () => { if (confirmInFlight.current === operation) confirmInFlight.current = null },
        () => { if (confirmInFlight.current === operation) confirmInFlight.current = null },
      )
    } catch (error) {
      if (confirmInFlight.current === operation) confirmInFlight.current = null
      throw error
    }
  }

  return (
    <Modal show={show} onHide={() => { if (!loading) onClose() }} keyboard={!loading} backdrop={loading ? 'static' : true} centered>
      <Modal.Header closeButton={!loading}>
        <Modal.Title>{resolvedTitle}</Modal.Title>
      </Modal.Header>
      <Modal.Body>{message}</Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={onClose} disabled={loading}>
          {t('btn.cancel', 'Annulla')}
        </Button>
        <Button variant={confirmVariant} onClick={handleConfirm} disabled={loading}>
          {loading ? <Spinner size="sm" /> : resolvedLabel}
        </Button>
      </Modal.Footer>
    </Modal>
  )
}
