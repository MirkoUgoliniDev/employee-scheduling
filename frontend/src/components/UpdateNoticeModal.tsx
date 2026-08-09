/**
 * @file UpdateNoticeModal.tsx
 * @brief Notice shown at login when a new version is available.
 *
 * @details It appears only once per version: users who dismiss it do not see it again until
 *          another version is released (`localStorage`). It is shown only to ADMIN users,
 *          who are the only ones able to perform an update; it would be noise for a head nurse.
 *
 *          It also explains HOW to update because downloading is not enough on Windows: the
 *          application must be uninstalled and reinstalled, while the data stays in place.
 *          A notice announcing a version without explaining what to do only creates questions.
 */

import { useEffect, useState } from 'react'
import { Button, Modal } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faCircleUp, faArrowUpRightFromSquare } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import { systemInfoApi, type AppUpdate } from '../api/systemInfo'

/** @brief Per-version dismissal key: a new release makes the notice visible again. */
const DISMISSED_PREFIX = 'app-update-dismissed-'

export default function UpdateNoticeModal() {
  const { t } = useTranslation()
  const [update, setUpdate] = useState<AppUpdate | null>(null)

  useEffect(() => {
    let cancelled = false
    systemInfoApi.appUpdate()
      .then(result => {
        if (cancelled || !result.updateAvailable || !result.latest) return
        if (localStorage.getItem(DISMISSED_PREFIX + result.latest)) return
        setUpdate(result)
      })
      // No network, insufficient permissions, or a disabled endpoint: the notice is not a user
      // operation and must never surface as an error.
      .catch(() => { /* intentionally silent */ })
    return () => { cancelled = true }
  }, [])

  if (!update || !update.latest) return null

  function dismiss(forever: boolean) {
    if (forever && update?.latest) {
      localStorage.setItem(DISMISSED_PREFIX + update.latest, '1')
    }
    setUpdate(null)
  }

  return (
    <Modal show onHide={() => dismiss(false)} centered>
      <Modal.Header closeButton>
        <Modal.Title className="h5">
          <FontAwesomeIcon icon={faCircleUp} className="me-2 text-primary" />
          {t('update.title', 'È disponibile una nuova versione')}
        </Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <p className="mb-3">
          {t('update.body', 'La versione installata è la {{current}}, mentre l\'ultima pubblicata è la {{latest}}.',
            { current: update.current, latest: update.latest })}
        </p>
        <p className="text-muted small mb-0">
          {t('update.howTo',
            'Per aggiornare: scarica il nuovo installatore, disinstalla la versione attuale e installa quella nuova. I tuoi dati — database, backup e configurazione — restano dove sono e li ritrovi al primo avvio.')}
        </p>
      </Modal.Body>
      <Modal.Footer className="d-flex justify-content-between">
        <Button variant="link" size="sm" className="text-decoration-none text-muted"
                onClick={() => dismiss(true)}>
          {t('update.dismissForever', 'Non ricordarmelo per questa versione')}
        </Button>
        <div>
          <Button variant="secondary" className="me-2" onClick={() => dismiss(false)}>
            {t('update.later', 'Più tardi')}
          </Button>
          {update.releaseUrl && (
            <Button variant="primary" href={update.releaseUrl} target="_blank" rel="noopener noreferrer"
                    onClick={() => dismiss(true)}>
              <FontAwesomeIcon icon={faArrowUpRightFromSquare} className="me-2" />
              {t('update.download', 'Vai al download')}
            </Button>
          )}
        </div>
      </Modal.Footer>
    </Modal>
  )
}
