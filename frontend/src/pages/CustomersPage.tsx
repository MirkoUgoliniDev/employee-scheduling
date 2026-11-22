/**
 * @file CustomersPage.tsx
 * @brief Customer master data (MVZ): who is visited, where, with which defaults.
 *
 * @details
 * A customer carries the defaults that make planning fast — usual start time, usual duration,
 * usual qualification — and the restrictions a scheduler must not forget. The locations of a
 * customer are edited here as a multi-select: the relation is stored once, on the location
 * (`locations.customer_id`), so a location belongs to at most one customer.
 */

import { useCallback, useEffect, useState } from 'react'
import { Badge, Button, Col, Form, Modal, Row, Spinner, Table } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faPenToSquare, faPlus, faTrash } from '@fortawesome/free-solid-svg-icons'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../store/useAppStore'
import { customersApi, type Customer, type CustomerPayload } from '../api/customers'
import { locationsApi, type Location } from '../api/locations'
import { skillsApi, type Skill } from '../api/skills'

/** @brief Draft state of the edit modal. */
function emptyDraft(): CustomerPayload {
  return {
    code: '',
    name: '',
    address: '',
    phone: '',
    active: true,
    defaultStartTime: '08:00',
    defaultDurationMinutes: 120,
    defaultSkillId: null,
    recurrencePattern: '',
    restrictions: '',
    locationIds: [],
  }
}

export default function CustomersPage() {
  const { t, i18n } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)

  const [customers, setCustomers] = useState<Customer[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [skills, setSkills] = useState<Skill[]>([])
  const [loading, setLoading] = useState(false)

  const [modalOpen, setModalOpen] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)
  const [draft, setDraft] = useState<CustomerPayload>(emptyDraft)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    if (structureId <= 0) return
    setLoading(true)
    customersApi.list(structureId)
      .then(setCustomers)
      .catch(() => toast.error(i18n.t('customers.errorLoad', 'Fehler beim Laden.')))
      .finally(() => setLoading(false))
  }, [structureId, i18n])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    if (structureId <= 0) return
    locationsApi.list(structureId).then(setLocations).catch(() => {})
    skillsApi.list(structureId).then(setSkills).catch(() => {})
  }, [structureId])

  function openNew() {
    const next = String(customers.length + 1).padStart(3, '0')
    setEditId(null)
    setDraft({ ...emptyDraft(), code: `K${next}` })
    setModalOpen(true)
  }

  function openEdit(customer: Customer) {
    setEditId(customer.id)
    setDraft({
      code: customer.code,
      name: customer.name,
      address: customer.address ?? '',
      phone: customer.phone ?? '',
      active: customer.active,
      defaultStartTime: customer.defaultStartTime ?? '08:00',
      defaultDurationMinutes: customer.defaultDurationMinutes ?? 120,
      defaultSkillId: customer.defaultSkillId ?? null,
      recurrencePattern: customer.recurrencePattern ?? '',
      restrictions: customer.restrictions ?? '',
      locationIds: customer.locationIds ?? [],
    })
    setModalOpen(true)
  }

  async function handleSave() {
    setBusy(true)
    try {
      if (editId === null) await customersApi.create(draft, structureId)
      else await customersApi.update(editId, draft)
      toast.success(t('customers.saved', 'Kunde gespeichert.'))
      setModalOpen(false)
      load()
    } catch {
      toast.error(t('customers.errorSave', 'Speichern fehlgeschlagen.'))
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete(customer: Customer) {
    if (!window.confirm(t('customers.confirmDelete', 'Diesen Kunden wirklich löschen?'))) return
    try {
      await customersApi.remove(customer.id)
      load()
    } catch (err) {
      void err
      toast.error(t('customers.errorDelete', 'Kunde konnte nicht gelöscht werden (noch Einsätze vorhanden?).'))
    }
  }

  function locationNames(ids: number[]): string {
    return ids
      .map(id => locations.find(l => l.id === id)?.name)
      .filter(Boolean)
      .join(', ')
  }

  return (
    <div className="container-fluid py-3">
      <div className="d-flex align-items-center mb-3">
        <h4 className="mb-0 me-auto">{t('customers.title', 'Kunden')}</h4>
        <Button variant="primary" onClick={openNew}>
          <FontAwesomeIcon icon={faPlus} className="me-1" />
          {t('customers.new', 'Neuer Kunde')}
        </Button>
      </div>

      {loading ? (
        <div className="text-center py-5"><Spinner animation="border" /></div>
      ) : customers.length === 0 ? (
        <div className="text-muted">{t('customers.empty', 'Noch keine Kunden angelegt.')}</div>
      ) : (
        <Table bordered hover responsive size="sm" className="align-middle">
          <thead>
            <tr>
              <th>{t('customers.code', 'Kürzel')}</th>
              <th>{t('customers.name', 'Name')}</th>
              <th>{t('customers.locations', 'Orte')}</th>
              <th>{t('customers.defaults', 'Vorgaben')}</th>
              <th>{t('customers.qualification', 'Qualifikation')}</th>
              <th>{t('customers.recurrence', 'Rhythmus')}</th>
              <th>{t('customers.restrictions', 'Einschränkungen')}</th>
              <th style={{ width: 100 }}>{t('customers.actions', 'Aktionen')}</th>
            </tr>
          </thead>
          <tbody>
            {customers.map(c => (
              <tr key={c.id}>
                <td>{c.code}</td>
                <td>
                  {c.name}
                  {!c.active && <Badge bg="secondary" className="ms-2">{t('customers.inactive', 'Inaktiv')}</Badge>}
                  {c.address && <div className="text-muted small">{c.address}</div>}
                </td>
                <td className="small">{locationNames(c.locationIds) || '—'}</td>
                <td className="small text-nowrap">
                  {c.defaultStartTime} · {c.defaultDurationMinutes}′
                </td>
                <td className="small">{c.defaultSkillName || '—'}</td>
                <td className="small">{c.recurrencePattern || '—'}</td>
                <td className="small">{c.restrictions || '—'}</td>
                <td className="text-nowrap">
                  <Button size="sm" variant="outline-secondary" className="me-1" onClick={() => openEdit(c)}>
                    <FontAwesomeIcon icon={faPenToSquare} />
                  </Button>
                  <Button size="sm" variant="outline-danger" onClick={() => handleDelete(c)}>
                    <FontAwesomeIcon icon={faTrash} />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <Modal show={modalOpen} onHide={() => setModalOpen(false)} size="lg">
        <Modal.Header closeButton>
          <Modal.Title>
            {editId === null ? t('customers.new', 'Neuer Kunde') : t('customers.edit', 'Kunde bearbeiten')}
          </Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Row className="g-2">
            <Col md={3}>
              <Form.Label>{t('customers.code', 'Kürzel')}</Form.Label>
              <Form.Control value={draft.code} onChange={e => setDraft({ ...draft, code: e.target.value })} />
            </Col>
            <Col md={9}>
              <Form.Label>{t('customers.name', 'Name')}</Form.Label>
              <Form.Control value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} />
            </Col>
            <Col md={8}>
              <Form.Label>{t('customers.address', 'Adresse')}</Form.Label>
              <Form.Control value={draft.address ?? ''}
                onChange={e => setDraft({ ...draft, address: e.target.value })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('customers.phone', 'Telefon')}</Form.Label>
              <Form.Control value={draft.phone ?? ''}
                onChange={e => setDraft({ ...draft, phone: e.target.value })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('customers.defaultStart', 'Übliche Beginnzeit')}</Form.Label>
              <Form.Control type="time" value={draft.defaultStartTime ?? '08:00'}
                onChange={e => setDraft({ ...draft, defaultStartTime: e.target.value })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('customers.defaultDuration', 'Übliche Dauer (Minuten)')}</Form.Label>
              <Form.Control type="number" min={15} step={15} value={draft.defaultDurationMinutes ?? 120}
                onChange={e => setDraft({ ...draft, defaultDurationMinutes: Number(e.target.value) })} />
            </Col>
            <Col md={4}>
              <Form.Label>{t('customers.qualification', 'Qualifikation')}</Form.Label>
              <Form.Select value={draft.defaultSkillId ?? ''}
                onChange={e => setDraft({ ...draft, defaultSkillId: e.target.value ? Number(e.target.value) : null })}>
                <option value="">{t('customers.noQualification', 'Keine')}</option>
                {skills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </Form.Select>
            </Col>
            <Col md={6}>
              <Form.Label>{t('customers.recurrence', 'Rhythmus')}</Form.Label>
              <Form.Control value={draft.recurrencePattern ?? ''}
                placeholder={t('customers.recurrenceHint', 'z. B. jeden 2. Montag')}
                onChange={e => setDraft({ ...draft, recurrencePattern: e.target.value })} />
            </Col>
            <Col md={6}>
              <Form.Label>{t('customers.restrictions', 'Einschränkungen')}</Form.Label>
              <Form.Control value={draft.restrictions ?? ''}
                onChange={e => setDraft({ ...draft, restrictions: e.target.value })} />
            </Col>
            <Col md={12}>
              <Form.Label>{t('customers.locations', 'Orte')}</Form.Label>
              <Form.Select multiple htmlSize={Math.min(6, Math.max(3, locations.length))}
                value={(draft.locationIds ?? []).map(String)}
                onChange={e => setDraft({
                  ...draft,
                  locationIds: [...e.target.selectedOptions].map(o => Number(o.value)),
                })}>
                {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
              </Form.Select>
              <Form.Text className="text-muted">
                {t('customers.locationsHint', 'Mehrfachauswahl mit Strg/Cmd.')}
              </Form.Text>
            </Col>
            <Col md={12}>
              <Form.Check type="checkbox" id="customer-active" checked={draft.active ?? true}
                label={t('customers.active', 'Aktiv')}
                onChange={e => setDraft({ ...draft, active: e.target.checked })} />
            </Col>
          </Row>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" onClick={() => setModalOpen(false)} disabled={busy}>
            {t('btn.cancel', 'Abbrechen')}
          </Button>
          <Button variant="primary" onClick={handleSave} disabled={busy}>
            {busy && <Spinner size="sm" animation="border" className="me-1" />}
            {t('btn.save', 'Speichern')}
          </Button>
        </Modal.Footer>
      </Modal>
    </div>
  )
}
