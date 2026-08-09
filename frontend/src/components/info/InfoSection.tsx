import { useEffect, useState } from 'react'
import { Accordion, Alert, Badge, Button, Spinner, Table } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faAtom, faBell, faBolt, faCode, faCubes, faDatabase, faDisplay, faEnvelope, faFilePdf, faLanguage, faLayerGroup, faMugHot, faPalette, faRobot, faRotate, faServer, faTimeline } from '@fortawesome/free-solid-svg-icons'
import { systemInfoApi, type SystemInfo, type UpdateInfo } from '../../api/systemInfo'

export default function InfoSection() {
  const { t } = useTranslation()
  const [info, setInfo] = useState<SystemInfo | null>(null)
  const [error, setError] = useState(false)
  const [updates, setUpdates] = useState<Record<string, UpdateInfo> | null>(null)
  const [checking, setChecking] = useState(false)

  useEffect(() => {
    systemInfoApi.get().then(setInfo).catch(() => setError(true))
  }, [])

  async function checkUpdates() {
    if (!info) return
    setChecking(true)
    try {
      const installed: Record<string, string> = {
        timefold: info.timefoldVersion,
        quarkus: info.quarkusVersion,
        hibernate: info.hibernateVersion,
        java: info.javaVersion,
        react: __TECH_VERSIONS__.react,
        typescript: __TECH_VERSIONS__.typescript,
        vite: __TECH_VERSIONS__.vite,
        bootstrap: __TECH_VERSIONS__.bootstrap,
        jspdf: __TECH_VERSIONS__.jspdf,
        i18next: __TECH_VERSIONS__.i18next,
        reactI18next: __TECH_VERSIONS__.reactI18next,
        reactBootstrap: __TECH_VERSIONS__.reactBootstrap,
        fontawesome: __TECH_VERSIONS__.fontawesome,
        visTimeline: __TECH_VERSIONS__.visTimeline,
        zustand: __TECH_VERSIONS__.zustand,
        reactHotToast: __TECH_VERSIONS__.reactHotToast,
      }
      if (info.databaseUpdateComponent) {
        installed[info.databaseUpdateComponent] = info.jdbcDriverVersion
      }
      setUpdates(await systemInfoApi.checkUpdates(installed))
    } finally {
      setChecking(false)
    }
  }

  function updateStatus(component: string) {
    if (!updates) return <span className="text-muted">—</span>
    const update = updates[component]
    if (!update || update.status === 'UNAVAILABLE') return <Badge bg="secondary">{t('config.info.unavailable', 'Non disponibile')}</Badge>
    if (update.status === 'UPDATE_AVAILABLE') return <Badge bg="warning" text="dark">{t('config.info.updateAvailable', 'Disponibile')} {update.latestVersion}</Badge>
    return <Badge bg="success">{t('config.info.upToDate', 'Aggiornato')}</Badge>
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3" style={{ maxWidth: 900 }}>
        <h5 className="mb-0">{t('config.menu.info', 'Info')}</h5>
        <Button variant="outline-primary" size="sm" onClick={checkUpdates} disabled={!info || checking}>
          <FontAwesomeIcon icon={faRotate} spin={checking} className="me-1" />
          {t('config.info.checkUpdates', 'Verifica aggiornamenti')}
        </Button>
      </div>
      <p className="text-muted">{t('config.info.description', 'Versioni dei componenti principali dell’applicazione.')}</p>
      {error && <Alert variant="danger">{t('config.info.error', 'Impossibile caricare le informazioni del backend.')}</Alert>}
      {info && (
        <div
          className="d-flex align-items-center gap-2 border rounded bg-light px-3 py-2 mb-3"
          style={{ width: '100%', maxWidth: 900 }}
          data-testid="active-database"
          aria-live="polite"
        >
          <FontAwesomeIcon icon={faDatabase} className="text-secondary" fixedWidth />
          <span className="fw-semibold">{t('config.info.activeDatabase', 'Database attivo')}:</span>
          <Badge bg={info.databaseUpdateComponent === 'postgresql' ? 'primary' : 'secondary'}>
            {info.databaseProductName}
          </Badge>
          <code>{info.databaseProductVersion}</code>
        </div>
      )}
      {!info && !error ? <div className="py-4 text-center"><Spinner /></div> : (
        <>
        <h6 className="text-muted">{t('config.info.appTitle', 'Applicazione')}</h6>
        <div className="table-responsive mb-4" style={{ width: '100%', maxWidth: 900 }}>
        <Table bordered hover className="align-middle mb-0" style={{ width: '100%' }}>
          <colgroup><col style={{ width: '42%' }} /><col style={{ width: '23%' }} /><col style={{ width: '35%' }} /></colgroup>
          <thead className="table-dark">
            <tr><th>{t('config.info.component', 'Componente')}</th><th>{t('config.info.version', 'Versione')}</th><th>{t('config.info.updates', 'Aggiornamenti')}</th></tr>
          </thead>
          <tbody>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faDisplay} className="me-2 text-info" fixedWidth />Frontend</td><td><code>{__APP_VERSION__}</code></td><td className="text-muted">—</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faServer} className="me-2 text-secondary" fixedWidth />Backend</td><td><code>{info?.backendVersion ?? '—'}</code></td><td className="text-muted">—</td></tr>
          </tbody>
        </Table>
        </div>
        <h6 className="text-muted">{t('config.info.mainTitle', 'Componenti principali')}</h6>
        <div className="table-responsive" style={{ width: '100%', maxWidth: 900 }}>
        <Table bordered hover className="align-middle mb-0" style={{ width: '100%' }}>
          <colgroup><col style={{ width: '42%' }} /><col style={{ width: '23%' }} /><col style={{ width: '35%' }} /></colgroup>
          <thead className="table-dark">
            <tr><th>{t('config.info.component', 'Componente')}</th><th>{t('config.info.version', 'Versione')}</th><th>{t('config.info.updates', 'Aggiornamenti')}</th></tr>
          </thead>
          <tbody>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faRobot} className="me-2 text-success" fixedWidth />Timefold Solver</td><td><code>{info?.timefoldVersion ?? '—'}</code></td><td>{updateStatus('timefold')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faBolt} className="me-2 text-primary" fixedWidth />Quarkus</td><td><code>{info?.quarkusVersion ?? '—'}</code></td><td>{updateStatus('quarkus')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faLayerGroup} className="me-2 text-warning" fixedWidth />Hibernate ORM</td><td><code>{info?.hibernateVersion ?? '—'}</code></td><td>{updateStatus('hibernate')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faMugHot} className="me-2 text-danger" fixedWidth />Java</td><td><code>{info?.javaVersion ?? '—'}</code></td><td>{updateStatus('java')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faAtom} className="me-2 text-info" fixedWidth />React</td><td><code>{__TECH_VERSIONS__.react}</code></td><td>{updateStatus('react')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faCode} className="me-2 text-primary" fixedWidth />TypeScript</td><td><code>{__TECH_VERSIONS__.typescript}</code></td><td>{updateStatus('typescript')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faBolt} className="me-2 text-warning" fixedWidth />Vite</td><td><code>{__TECH_VERSIONS__.vite}</code></td><td>{updateStatus('vite')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faLayerGroup} className="me-2 text-primary" fixedWidth />Bootstrap</td><td><code>{__TECH_VERSIONS__.bootstrap}</code></td><td>{updateStatus('bootstrap')}</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faDatabase} className="me-2 text-secondary" fixedWidth />{info?.databaseProductName ?? 'Database'}</td><td><code>{info?.databaseProductVersion ?? '—'}</code></td><td className="text-muted">—</td></tr>
            <tr><td className="fw-semibold"><FontAwesomeIcon icon={faDatabase} className="me-2 text-secondary" fixedWidth />{info?.jdbcDriverName ?? 'JDBC'}</td><td><code>{info?.jdbcDriverVersion ?? '—'}</code></td><td>{updateStatus(info?.databaseUpdateComponent ?? 'database')}</td></tr>
          </tbody>
        </Table>
        </div>
        </>
      )}
      {info && (
        <Accordion className="mt-3" style={{ maxWidth: 900 }}>
          <Accordion.Item eventKey="0">
            <Accordion.Header>{t('config.info.secondaryTitle', 'Librerie e componenti secondari')}</Accordion.Header>
            <Accordion.Body className="p-0">
              <div className="table-responsive">
              <Table bordered hover className="align-middle mb-0" style={{ width: '100%' }}>
                <colgroup><col style={{ width: '42%' }} /><col style={{ width: '23%' }} /><col style={{ width: '35%' }} /></colgroup>
                <thead className="table-light">
                  <tr><th>{t('config.info.component', 'Componente')}</th><th>{t('config.info.version', 'Versione')}</th><th>{t('config.info.updates', 'Aggiornamenti')}</th></tr>
                </thead>
                <tbody>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faFilePdf} className="me-2 text-danger" fixedWidth />jsPDF</td><td><code>{__TECH_VERSIONS__.jspdf}</code></td><td>{updateStatus('jspdf')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faEnvelope} className="me-2 text-primary" fixedWidth />Quarkus Mailer</td><td><code>{info.quarkusVersion}</code></td><td>{updateStatus('quarkus')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faLanguage} className="me-2 text-success" fixedWidth />i18next</td><td><code>{__TECH_VERSIONS__.i18next}</code></td><td>{updateStatus('i18next')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faLanguage} className="me-2 text-info" fixedWidth />React i18next</td><td><code>{__TECH_VERSIONS__.reactI18next}</code></td><td>{updateStatus('reactI18next')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faPalette} className="me-2 text-primary" fixedWidth />React Bootstrap</td><td><code>{__TECH_VERSIONS__.reactBootstrap}</code></td><td>{updateStatus('reactBootstrap')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faCubes} className="me-2 text-secondary" fixedWidth />Font Awesome</td><td><code>{__TECH_VERSIONS__.fontawesome}</code></td><td>{updateStatus('fontawesome')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faTimeline} className="me-2 text-primary" fixedWidth />Vis Timeline</td><td><code>{__TECH_VERSIONS__.visTimeline}</code></td><td>{updateStatus('visTimeline')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faDatabase} className="me-2 text-warning" fixedWidth />Zustand</td><td><code>{__TECH_VERSIONS__.zustand}</code></td><td>{updateStatus('zustand')}</td></tr>
                  <tr><td className="fw-semibold"><FontAwesomeIcon icon={faBell} className="me-2 text-danger" fixedWidth />React Hot Toast</td><td><code>{__TECH_VERSIONS__.reactHotToast}</code></td><td>{updateStatus('reactHotToast')}</td></tr>
                </tbody>
              </Table>
              </div>
            </Accordion.Body>
          </Accordion.Item>
        </Accordion>
      )}
    </div>
  )
}
