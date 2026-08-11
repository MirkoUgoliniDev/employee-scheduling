/**
 * @file Navbar.tsx
 * @brief Main application navigation bar.
 *
 * @details
 * Includes:
 * - Brand with current structure name and active section
 * - Links to the main sections in the application's operational order
 * - Structure-selector dropdown (visible only when multiple structures exist)
 * - Language-selector dropdown (it, en, fr, es, de)
 *
 * On first load, automatically selects the first available structure
 * when none is already saved in the store.
 */

import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { Navbar as BsNavbar, Nav, Container, NavDropdown } from 'react-bootstrap'
import { useTranslation } from 'react-i18next'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import {
  faCalendarDays,
  faUsers,
  faUserDoctor,
  faLocationDot,
  faCalendarCheck,
  faChartColumn,
  faGear,
  faCircleUser,
  faRightFromBracket,
  faUserGear,
  faPowerOff,
  faHouse,
} from '@fortawesome/free-solid-svg-icons'
import { useAppStore } from '../store/useAppStore'
import { api } from '../api/client'
import { generalSettingsApi } from '../api/generalSettings'
import { useAuth } from '../auth/AuthContext'
import toast from 'react-hot-toast'
import { systemInfoApi } from '../api/systemInfo'
import type { Structure } from '../types'
import ConfirmModal from './ConfirmModal'

export default function Navbar() {
  const { t, i18n } = useTranslation()
  const { session, isAdmin, logout } = useAuth()
  const { currentStructure, setCurrentStructure, language, setLanguage, showTranslationKeys,
    setShiftWindowMode, setAutoPopulateFromTemplate } = useAppStore()
  const [structures, setStructures] = useState<Structure[]>([])
  const [exitConfirmOpen, setExitConfirmOpen] = useState(false)
  const [exitBusy, setExitBusy] = useState(false)

  function tryCloseWindow() {
    try {
      window.open('', '_self')
      window.close()
    } catch {
      // best-effort
    }
    // If the browser blocks window.close(), at least clear the page.
    setTimeout(() => {
      try {
        if (!window.closed) {
          window.location.replace('about:blank')
        }
      } catch {
        // ignore
      }
    }, 200)
  }

  // Configuration: administrators only. The backend rejects access anyway, but showing an entry
  // that leads to an error is worse than hiding it.
  const LINKS = [
    { to: '/shifts',    label: t('nav.shifts', 'Gestione Turni'), icon: faCalendarDays },
    { to: '/locations', label: t('nav.locations', 'Sedi'), icon: faLocationDot },
    { to: '/employees', label: t('nav.employees', 'Operatori'), icon: faUsers },
    { to: '/dates',     label: t('nav.dates', 'Preferenze date Operatori'), icon: faCalendarCheck },
    { to: '/specialists', label: t('nav.specialists', 'Specialisti'), icon: faUserDoctor },
    { to: '/report',    label: t('nav.report', 'Report'), icon: faChartColumn },
    ...(isAdmin ? [
      { to: '/config', label: t('nav.config', 'Configurazione'), icon: faGear },
      { to: '/users', label: t('nav.users', 'Utenti'), icon: faUserGear },
    ] : []),
  ]

  useEffect(() => {
    // Needed by everyone, not just administrators: without the list there is no selected
    // structure and every page remains empty. It is not sensitive read-only data.
    api.get<Structure[]>('/structures')
      .then(setStructures)
      .catch(() => {})
  }, [])

  // The selection lives in localStorage and survives reinstallations, backup restores, and
  // deletions performed elsewhere: compare it with the actual list rather than merely checking
  // for its presence. A structure that no longer exists yields empty lists rather than errors.
  useEffect(() => {
    if (structures.length === 0) return
    const selected = currentStructure && structures.find(s => s.id === currentStructure.id)
    if (!selected) {
      setCurrentStructure(structures[0])
    } else if (selected.name !== currentStructure.name) {
      // Name changed elsewhere: the store retains the entire object, not just the ID.
      setCurrentStructure(selected)
    }
  }, [structures, currentStructure, setCurrentStructure])

  // Synchronize General Settings (window granularity + auto-population) from the selected
  // structure: the backend is the source of truth (per structure), while the store is only
  // the current-structure cache read by Shift Management/Report.
  useEffect(() => {
    const id = currentStructure?.id
    if (!id) return
    let cancelled = false
    generalSettingsApi.get(id)
      .then(gs => {
        if (cancelled) return
        setShiftWindowMode(gs.shift_window_mode)
        setAutoPopulateFromTemplate(gs.auto_populate_from_template)
      })
      .catch(() => {})
    return () => { cancelled = true }
  }, [currentStructure?.id, setShiftWindowMode, setAutoPopulateFromTemplate])

  const LANGUAGES = [
    { code: 'it', label: 'Italiano' },
    { code: 'en', label: 'English' },
    { code: 'fr', label: 'Français' },
    { code: 'es', label: 'Español' },
    { code: 'de', label: 'Deutsch' },
  ]

  return (
    <>
      <BsNavbar bg="dark" variant="dark" expand="lg" className="px-3" style={{ minHeight: 64 }}>
        <Container fluid>
        <BsNavbar.Brand as={NavLink} to="/" className="fw-bold" aria-label={t('nav.shiftManagement', 'Gestione Turni')}>
          <FontAwesomeIcon icon={faHouse} className="me-1" />
        </BsNavbar.Brand>

        <BsNavbar.Toggle aria-controls="main-nav" />
        <BsNavbar.Collapse id="main-nav">
          <Nav className="me-auto ms-4 ps-3 gap-2">
            {LINKS.map(({ to, label, icon }) => (
              <Nav.Link key={to} as={NavLink} to={to}>
                <FontAwesomeIcon icon={icon} className="me-2" />
                {label}
              </Nav.Link>
            ))}
          </Nav>

          <Nav className="ms-auto align-items-lg-center gap-2">
            {/* Structure selector */}
            {structures.length > 0 && (
              <NavDropdown
                title={currentStructure?.name ?? t('nav.structure', 'Struttura')}
                id="structure-dropdown"
                align="end"
              >
                {structures.map(s => (
                  <NavDropdown.Item
                    key={s.id}
                    active={currentStructure?.id === s.id}
                    onClick={() => setCurrentStructure(s)}
                  >
                    {s.name}
                  </NavDropdown.Item>
                ))}
              </NavDropdown>
            )}

            {/* Language selector */}
            <NavDropdown
              title={language.toUpperCase()}
              id="lang-dropdown"
              align="end"
            >
              {LANGUAGES.map(l => (
                <NavDropdown.Item
                  key={l.code}
                  active={language === l.code}
                  onClick={() => {
                    setLanguage(l.code)
                    i18n.changeLanguage(showTranslationKeys ? 'cimode' : l.code)
                  }}
                >
                  {l.label}
                </NavDropdown.Item>
              ))}
            </NavDropdown>

            {/* Signed-in user and logout */}
            <NavDropdown
              title={<><FontAwesomeIcon icon={faCircleUser} className="me-1" />{session?.displayName ?? session?.username ?? ''}</>}
              id="user-dropdown"
              align="end"
            >
              <NavDropdown.Header>
                {isAdmin ? t('user.roleAdmin', 'Amministratore') : t('user.roleCaposala', 'Gestione turni')}
              </NavDropdown.Header>
              <NavDropdown.Divider />
              {/* Shown to a CAPOSALA only on the desktop package, where closing the application
                  is closing the window in front of them. On a shared server it would take the
                  service down for everyone, and the backend answers 403 EXIT_REQUIRES_ADMIN. */}
              {(isAdmin || session?.standalone) && (
                <NavDropdown.Item onClick={() => setExitConfirmOpen(true)}>
                  <FontAwesomeIcon icon={faPowerOff} className="me-2" />
                  {t('user.exitApp', 'Chiudi applicazione')}
                </NavDropdown.Item>
              )}
              <NavDropdown.Item onClick={() => { void logout() }}>
                <FontAwesomeIcon icon={faRightFromBracket} className="me-2" />
                {t('user.logout', 'Esci')}
              </NavDropdown.Item>
            </NavDropdown>

          </Nav>
        </BsNavbar.Collapse>
        </Container>
      </BsNavbar>

      <ConfirmModal
        show={exitConfirmOpen}
        title={t('confirm.exitAppTitle', 'Chiudere l\'applicazione?')}
        message={t('confirm.exitAppBody', 'L\'app verrà chiusa per tutti gli utenti collegati su questo PC.')}
        confirmLabel={t('confirm.exitAppConfirm', 'Chiudi app')}
        confirmVariant="danger"
        loading={exitBusy}
        onClose={() => { if (!exitBusy) setExitConfirmOpen(false) }}
        onConfirm={async () => {
          if (exitBusy) return
          setExitBusy(true)
          try {
            await systemInfoApi.exit()
            tryCloseWindow()
          } catch {
            toast.error(t('toast.errorSave', 'Errore durante il salvataggio.'))
            setExitBusy(false)
            return
          }
          setExitBusy(false)
          setExitConfirmOpen(false)
        }}
      />
    </>
  )
}
