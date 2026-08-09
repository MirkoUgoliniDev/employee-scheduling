import { useEffect, useState, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Table, Spinner, Modal, Form, Row, Col } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faUserPlus, faUserSlash, faUserCheck } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { usersApi, type AppUser, type CreateUserPayload, type UpdateUserPayload } from '../api/users'
import { errorCode } from '../api/client'
import { backendErrorText } from '../i18n/backendErrors'
import { useAuth } from '../auth/AuthContext'
import ConfirmModal from '../components/ConfirmModal'

const ROLES: { value: 'ADMIN' | 'CAPOSALA'; label: string }[] = [
  { value: 'ADMIN', label: 'Administrator' },
  { value: 'CAPOSALA', label: 'Caposala' },
]

export default function UsersPage() {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (!isAdmin) navigate('/', { replace: true })
  }, [isAdmin, navigate])

  const currentUsername = useAuth().session?.username
  const [users, setUsers] = useState<AppUser[]>([])
  const [loading, setLoading] = useState(false)
  const loadGeneration = useRef(0)

  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)
  const [form, setForm] = useState({ username: '', displayName: '', email: '', role: 'CAPOSALA' as 'ADMIN' | 'CAPOSALA', rawPassword: '' })
  const [saving, setSaving] = useState(false)

  const [deactivateTarget, setDeactivateTarget] = useState<AppUser | null>(null)
  const [deactivating, setDeactivating] = useState(false)
  const [activateTarget, setActivateTarget] = useState<AppUser | null>(null)
  const [activating, setActivating] = useState(false)

  const load = useCallback(() => {
    const generation = ++loadGeneration.current
    setLoading(true)
    usersApi.list()
      .then(data => { if (generation === loadGeneration.current) setUsers(data) })
      .catch(() => { if (generation === loadGeneration.current) toast.error(t('toast.errorLoad', 'Errore nel caricamento.')) })
      .finally(() => { if (generation === loadGeneration.current) setLoading(false) })
  }, [t])

  // Administrators only: without this condition, the GET still starts in the same
  // commit as the redirect, returns 403, and leaves an error toast on screen that survives
  // the page change.
  useEffect(() => { if (isAdmin) load() }, [isAdmin, load])

  function openAdd() {
    setEditId(null)
    setForm({ username: '', displayName: '', email: '', role: 'CAPOSALA', rawPassword: '' })
    setModalOpen(true)
  }

  function openEdit(user: AppUser) {
    setEditId(user.id)
    setForm({ username: user.username, displayName: user.displayName ?? '', email: user.email ?? '', role: user.role, rawPassword: '' })
    setModalOpen(true)
  }

  async function handleSave() {
    if (!form.username.trim()) {
      toast.error(t('msg.err.userUsernameRequired', 'Il nome utente è obbligatorio.'))
      return
    }
    if (!editId && !form.rawPassword.trim()) {
      toast.error(t('msg.err.userPasswordRequired', 'La password è obbligatoria.'))
      return
    }
    setSaving(true)
    try {
      if (editId) {
        const payload: UpdateUserPayload = { username: form.username.trim(), role: form.role, displayName: form.displayName.trim(), email: form.email.trim() }
        if (form.rawPassword.trim()) payload.rawPassword = form.rawPassword
        await usersApi.update(editId, payload)
        toast.success(t('toast.userSaved', 'Utente aggiornato.'))
      } else {
        const payload: CreateUserPayload = { username: form.username.trim(), rawPassword: form.rawPassword, role: form.role, displayName: form.displayName.trim(), email: form.email.trim() }
        await usersApi.create(payload)
        toast.success(t('toast.userCreated', 'Utente creato.'))
      }
      setModalOpen(false)
      load()
    } catch (e) {
      const code = errorCode(e)
      const msg = backendErrorText(code, t) ?? t('toast.errorSave', 'Errore durante il salvataggio.')
      toast.error(msg)
    } finally {
      setSaving(false)
    }
  }

  async function confirmDeactivate() {
    if (!deactivateTarget) return
    setDeactivating(true)
    try {
      await usersApi.deactivate(deactivateTarget.id)
      toast.success(t('toast.userDeactivated', 'Utente disattivato.'))
      setDeactivateTarget(null)
      load()
    } catch (e) {
      const code = errorCode(e)
      const msg = backendErrorText(code, t) ?? t('toast.errorDelete', "Errore durante l'operazione.")
      toast.error(msg)
    } finally {
      setDeactivating(false)
    }
  }

  async function confirmActivate() {
    if (!activateTarget) return
    setActivating(true)
    try {
      await usersApi.update(activateTarget.id, { active: true })
      toast.success(t('toast.userActivated', 'Utente attivato.'))
      setActivateTarget(null)
      load()
    } catch (e) {
      const code = errorCode(e)
      const msg = backendErrorText(code, t) ?? t('toast.errorSave', 'Errore durante il salvataggio.')
      toast.error(msg)
    } finally {
      setActivating(false)
    }
  }

  return (
    <div>
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h5 className="mb-0">{t('nav.users', 'Utenti')}</h5>
        <Button variant="primary" size="sm" title={t('btn.addUser', 'Aggiungi utente')} onClick={openAdd}>
          <FontAwesomeIcon icon={faUserPlus} className="me-2" />
          {t('btn.add', 'Aggiungi')}
        </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner /></div>
      ) : users.length === 0 ? (
        <p className="text-muted">{t('users.empty', 'Nessun utente.')}</p>
      ) : (
        <Table hover bordered responsive size="sm">
          <thead className="table-dark">
            <tr>
              <th style={{ width: 180 }}>{t('login.username', 'Nome utente')}</th>
              <th style={{ width: 180 }}>{t('user.displayName', 'Nome visualizzato')}</th>
              <th>{t('user.email', 'Email')}</th>
              <th style={{ width: 150 }}>{t('user.role', 'Ruolo')}</th>
              <th style={{ width: 100 }}>{t('label.active', 'Attivo')}</th>
              <th style={{ width: 180 }}>{t('label.createdAt', 'Creato il')}</th>
              <th style={{ width: 90 }}>{t('table.actions', 'Azioni')}</th>
            </tr>
          </thead>
          <tbody>
            {users.map(user => (
              <tr key={user.id} className={!user.active ? 'table-secondary' : undefined}>
                <td><code>{user.username}</code></td>
                <td>{user.displayName || '—'}</td>
                <td className="text-muted small">{user.email || '—'}</td>
                <td>
                  {user.role === 'ADMIN'
                    ? <span className="badge bg-primary">{t('user.roleAdmin', 'Amministratore')}</span>
                    : <span className="badge bg-secondary">{t('user.roleCaposala', 'Gestione turni')}</span>}
                </td>
                <td className="text-center">
                  {user.active
                    ? <span className="badge bg-success">{t('label.activeYes', 'Sì')}</span>
                    : <span className="badge bg-warning text-dark">{t('user.pending', 'In attesa')}</span>}
                </td>
                <td className="text-muted small">{user.createdAt || '—'}</td>
                <td>
                  <Button
                    variant="link" size="sm" className="p-0 me-3 text-primary"
                    title={t('btn.edit', 'Modifica')}
                    onClick={() => openEdit(user)}
                  >
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  {!user.active && (
                    <Button
                      variant="link" size="sm" className="p-0 me-3 text-success"
                      title={t('btn.approve', 'Approva / attiva')}
                      onClick={() => setActivateTarget(user)}
                    >
                      <FontAwesomeIcon icon={faUserCheck} />
                    </Button>
                  )}
                  {user.active && user.username !== currentUsername && (
                    <Button
                      variant="link" size="sm" className="p-0 text-danger"
                      title={t('btn.deactivate', 'Disattiva')}
                      onClick={() => setDeactivateTarget(user)}
                    >
                      <FontAwesomeIcon icon={faUserSlash} />
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <Modal show={modalOpen} onHide={() => setModalOpen(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title>{editId ? t('modal.editUser', 'Modifica utente') : t('modal.addUser', 'Nuovo utente')}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Form>
            <Form.Group className="mb-3">
              <Form.Label>{t('login.username', 'Nome utente')}</Form.Label>
              <Form.Control
                autoFocus
                value={form.username}
                onChange={e => setForm({ ...form, username: e.target.value })}
                placeholder={t('login.username', 'Nome utente')}
                maxLength={100}
                required
              />
            </Form.Group>
            <Form.Group className="mb-3">
              <Form.Label>{t('user.displayName', 'Nome visualizzato')}</Form.Label>
              <Form.Control
                value={form.displayName}
                onChange={e => setForm({ ...form, displayName: e.target.value })}
                placeholder={t('user.displayNamePlaceholder', 'Es. Mario Rossi')}
                maxLength={100}
              />
            </Form.Group>
            <Form.Group className="mb-3">
              <Form.Label>{t('user.email', 'Email')}</Form.Label>
              <Form.Control
                type="email"
                value={form.email}
                onChange={e => setForm({ ...form, email: e.target.value })}
                placeholder="mario.rossi@example.com"
                maxLength={200}
              />
            </Form.Group>
            <Row className="mb-3">
              <Col>
                <Form.Label>{t('user.role', 'Ruolo')}</Form.Label>
                <Form.Select value={form.role} onChange={e => setForm({ ...form, role: e.target.value as 'ADMIN' | 'CAPOSALA' })}>
                  {ROLES.map(r => (
                    <option key={r.value} value={r.value}>
                      {r.value === 'ADMIN' ? t('user.roleAdmin', 'Amministratore') : t('user.roleCaposala', 'Gestione turni')}
                    </option>
                  ))}
                </Form.Select>
              </Col>
            </Row>
            <Form.Group className="mb-3">
              <Form.Label>
                {editId ? t('user.newPassword', 'Nuova password (lasciare vuoto per non cambiarla)') : t('login.password', 'Password')}
              </Form.Label>
              <Form.Control
                type="password"
                value={form.rawPassword}
                onChange={e => setForm({ ...form, rawPassword: e.target.value })}
                placeholder={t('login.password', 'Password')}
                autoComplete="new-password"
                required={!editId}
              />
            </Form.Group>
          </Form>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setModalOpen(false)} disabled={saving}>
            {t('btn.cancel', 'Annulla')}
          </Button>
          <Button variant="primary" onClick={handleSave} disabled={saving}>
            {saving ? <Spinner size="sm" /> : t('btn.save', 'Salva')}
          </Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={deactivateTarget !== null}
        title={t('user.deactivateTitle', 'Disattiva utente')}
        message={`${t('user.deactivateMsg', "L'utente")} "${deactivateTarget?.username ?? ''}" ${t('user.deactivateMsg2', 'verrà disattivato e non potrà più accedere.')}`}
        confirmLabel={t('btn.deactivate', 'Disattiva')}
        confirmVariant="warning"
        loading={deactivating}
        onConfirm={confirmDeactivate}
        onClose={() => setDeactivateTarget(null)}
      />

      <ConfirmModal
        show={activateTarget !== null}
        title={t('user.activateTitle', 'Approva utente')}
        message={`${t('user.activateMsg', "L'utente")} "${activateTarget?.username ?? ''}" ${t('user.activateMsg2', 'verrà attivato e potrà accedere all\'applicazione.')}`}
        confirmLabel={t('btn.approve', 'Approva / attiva')}
        confirmVariant="success"
        loading={activating}
        onConfirm={confirmActivate}
        onClose={() => setActivateTarget(null)}
      />
    </div>
  )
}
