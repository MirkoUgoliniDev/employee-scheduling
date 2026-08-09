/**
 * @file SolveResultModal.tsx
 * @brief Summary modal shown after Timefold solving completes.
 *
 * @details
 * Displays:
 * - Overall solution score (green = optimal, red = violated constraints)
 * - Violated-constraint table (red) with weight, score contribution, and violation count
 * - Satisfied-constraint table (green)
 *
 * Uses `unknown` types for score and weight because Timefold may return either a string
 * (e.g. "0hard/-2soft") or a structured object depending on the version.
 * `scoreToString()` normalizes both formats for display.
 *
 * A constraint is considered violated only when the HARD part of its score is negative.
 * In the satisfied table, a negative soft score (preference/balancing cost) is marked
 * in gray rather than green.
 */

import { useState } from 'react'
import { Modal, Button, Table, Badge, Spinner, Alert } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFloppyDisk, faTriangleExclamation, faXmark, faChevronRight, faChevronDown } from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../store/useAppStore'
import type { ScoreAnalysis } from '../../api/shifts'

/**
 * @brief SolveResultModal component props.
 */
interface Props {
  show: boolean
  /** @brief Timefold analysis result, or `null` when not yet available. */
  analysis: ScoreAnalysis | null
  /** @brief Saves the solution assignments to the database. */
  onSave: () => void
  /** @brief Discards the solution (the view returns to persisted state). */
  onDiscard: () => void
  /** @brief Save in progress (disables buttons). */
  saving?: boolean
}

/**
 * @brief Converts a score value (string or object) to a readable string.
 * @param score Score value of unknown type
 * @returns String representation of the score
 */
function scoreToString(score: unknown): string {
  if (!score) return '—'
  if (typeof score === 'string') return score
  if (typeof score === 'object') return JSON.stringify(score)
  return String(score)
}

/**
 * @brief Formats a score for display by separating the numeric value from the
 *        description: "0hard/-200.873soft" → "0 hard / -200.873 soft".
 * @details Cosmetic only: logic functions (isViolated/isHardViolated) continue to use
 *          the raw scoreToString() string.
 */
function formatScoreForDisplay(score: unknown): string {
  return scoreToString(score)
    .replace(/(-?[\d.]+)\s*(hard|medium|soft)/g, '$1 $2')
    .replace(/\s*\/\s*/g, ' / ')
}

function isViolated(score: unknown): boolean {
  const s = scoreToString(score)
  return s.split('/').some(part => part.trim().startsWith('-'))
}

/**
 * @brief true only when the HARD portion of the score is negative.
 * @details A negative soft score is normal (preference/balancing cost) and must not be
 *          reported as a violation: only hard scores indicate broken strict constraints
 *          (e.g. unassigned shifts, missing skills, overlaps).
 */
function isHardViolated(score: unknown): boolean {
  const m = scoreToString(score).match(/(-?[\d.]+)\s*hard/)
  return m ? parseFloat(m[1]) < 0 : isViolated(score)
}

export default function SolveResultModal({ show, analysis, onSave, onDiscard, saving = false }: Props) {
  const { t } = useTranslation()
  const structureId = useAppStore(s => s.currentStructure?.id ?? 0)
  // Guard against saving infeasible solutions: the first click on "Save" when hard
  // violations exist does not save; it shows a warning and asks for explicit confirmation.
  const [confirmInfeasible, setConfirmInfeasible] = useState(false)
  // The "Satisfied constraints" block is long and informative but rarely needed: it starts
  // collapsed and expands when its heading is clicked.
  const [showSatisfied, setShowSatisfied] = useState(false)
  // SYNCHRONOUS reset during render (not in a post-paint effect) when the solution changes
  // or the modal reopens: this ensures the first frame never shows the "Save anyway" button
  // already armed by a previous session, preventing an immediate click from bypassing the
  // guard and persisting an infeasible solution.
  const [tracked, setTracked] = useState<{ a: ScoreAnalysis | null; s: boolean }>({ a: analysis, s: show })
  if (tracked.a !== analysis || tracked.s !== show) {
    setTracked({ a: analysis, s: show })
    setConfirmInfeasible(false)
  }
  if (!analysis) return null

  /**
   * @brief Opens Solver Settings for the current company in a new tab, with the modal
   *        already open and the clicked constraint field highlighted
   *        (does not close the solution preview in Shift Management).
   */
  function openSolverSettings(constraintName?: string) {
    const q = new URLSearchParams({ section: 'solverSettings' })
    if (structureId) q.set('structureId', String(structureId))
    if (constraintName) q.set('constraint', constraintName)
    window.open(`/config?${q.toString()}`, '_blank', 'noopener')
  }

  const constraints = analysis.constraints ?? []
  // Only a negative hard score is a violation: a negative soft score is the normal cost
  // of preferences and balancing, and classifying it as violated would drown out
  // broken hard constraints amid the noise.
  const violated = constraints.filter(c => isHardViolated(c.score))
  const satisfied = constraints.filter(c => !isHardViolated(c.score))
  const globalViolated = isHardViolated(analysis.score)

  /**
   * @brief First click with hard violations: arms confirmation without saving.
   *        Second click (or a feasible solution): actually saves.
   */
  function handleSaveClick() {
    if (globalViolated && !confirmInfeasible) {
      setConfirmInfeasible(true)
      return
    }
    onSave()
  }

  return (
    <Modal show={show} onHide={onDiscard} centered size="lg">
      <Modal.Header closeButton>
        <Modal.Title>{t('modal.solveResult', 'Risultato Solve')}</Modal.Title>
      </Modal.Header>
      <Modal.Body>

        {/* Overall score */}
        <div className="d-flex align-items-center gap-3 mb-4">
          <span className="fw-semibold">{t('label.score', 'Punteggio')}:</span>
          <Badge bg={globalViolated ? 'danger' : 'success'} style={{ fontSize: '1rem' }}>
            {formatScoreForDisplay(analysis.score)}
          </Badge>
          {violated.length > 0 ? (
            <span className="text-danger fw-semibold">{violated.length} {t('msg.constraintsViolated', 'vincolo/i violato/i')}</span>
          ) : globalViolated ? (
            <span className="text-danger fw-semibold">
              {t('msg.solutionHasViolations', 'La soluzione viola dei vincoli: alcuni turni potrebbero essere rimasti non assegnati.')}
            </span>
          ) : (
            <span className="text-success fw-semibold">{t('msg.allConstraintsSatisfied', 'Tutti i vincoli rispettati ✓')}</span>
          )}
        </div>

        {constraints.length > 0 && (
          <p className="text-muted small mb-2">
            {t('hint.clickConstraintForSettings', 'Clicca un vincolo per aprire i Parametri Solver in una nuova scheda.')}
          </p>
        )}

        {/* Violated constraints */}
        {violated.length > 0 && (
          <div className="mb-3">
            <h6 className="text-danger">{t('label.violatedConstraints', 'Vincoli violati')}</h6>
            <Table size="sm" bordered>
              <thead className="table-danger">
                <tr>
                  <th>{t('table.constraint', 'Vincolo')}</th>
                  <th>{t('table.weight', 'Peso')}</th>
                  <th>{t('table.score', 'Score')}</th>
                  <th>{t('table.violations', 'Violazioni')}</th>
                </tr>
              </thead>
              <tbody>
                {violated.map((c, i) => (
                  <tr key={i} onClick={() => openSolverSettings(c.name)} style={{ cursor: 'pointer' }}
                      title={t('tooltip.openSolverSettings', 'Apri i Parametri Solver in una nuova scheda')}>
                    <td className="text-decoration-underline">{c.name ?? '—'}</td>
                    <td><code>{formatScoreForDisplay(c.weight)}</code></td>
                    <td><Badge bg="danger">{formatScoreForDisplay(c.score)}</Badge></td>
                    <td>{c.matchCount ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </div>
        )}

        {/* Satisfied constraints — collapsible, collapsed by default */}
        {satisfied.length > 0 && (
          <div>
            <h6
              className="text-success"
              style={{ cursor: 'pointer', userSelect: 'none' }}
              onClick={() => setShowSatisfied(v => !v)}
              role="button"
              aria-expanded={showSatisfied}
            >
              <FontAwesomeIcon icon={showSatisfied ? faChevronDown : faChevronRight} className="me-2" />
              {t('label.satisfiedConstraints', 'Vincoli rispettati')} ({satisfied.length})
            </h6>
            {showSatisfied && (
            <Table size="sm" bordered>
              <thead className="table-success">
                <tr>
                  <th>{t('table.constraint', 'Vincolo')}</th>
                  <th>{t('table.weight', 'Peso')}</th>
                  <th>{t('table.score', 'Score')}</th>
                </tr>
              </thead>
              <tbody>
                {satisfied.map((c, i) => (
                  <tr key={i} onClick={() => openSolverSettings(c.name)} style={{ cursor: 'pointer' }}
                      title={t('tooltip.openSolverSettings', 'Apri i Parametri Solver in una nuova scheda')}>
                    <td className="text-decoration-underline">{c.name ?? '—'}</td>
                    <td><code>{formatScoreForDisplay(c.weight)}</code></td>
                    <td><Badge bg={isViolated(c.score) ? 'secondary' : 'success'}>{formatScoreForDisplay(c.score)}</Badge></td>
                  </tr>
                ))}
              </tbody>
            </Table>
            )}
          </div>
        )}

        {constraints.length === 0 && (
          <p className="text-muted">{t('msg.noConstraintData', 'Nessun dato sui vincoli disponibile.')}</p>
        )}

        {/* Explicit confirmation required before persisting an infeasible solution */}
        {globalViolated && confirmInfeasible && (
          <Alert variant="danger" className="mt-3 mb-0">
            <FontAwesomeIcon icon={faTriangleExclamation} className="me-2" />
            {t('msg.infeasibleSaveWarning', 'La soluzione viola dei vincoli rigidi: salvandola, le violazioni verranno scritte nei turni. Premi "Salva comunque" per confermare.')}
          </Alert>
        )}

      </Modal.Body>
      <Modal.Footer>
        <Button variant="outline-secondary" onClick={onDiscard} disabled={saving}>
          <FontAwesomeIcon icon={faXmark} className="me-1" />{t('btn.discardSolution', 'Scarta')}
        </Button>
        <Button variant={globalViolated && confirmInfeasible ? 'danger' : 'primary'} onClick={handleSaveClick} disabled={saving}>
          {saving ? <Spinner size="sm" /> : globalViolated && confirmInfeasible
            ? <><FontAwesomeIcon icon={faTriangleExclamation} className="me-1" />{t('btn.saveAnyway', 'Salva comunque')}</>
            : <><FontAwesomeIcon icon={faFloppyDisk} className="me-1" />{t('btn.saveAssignments', 'Salva assegnazioni')}</>}
        </Button>
      </Modal.Footer>
    </Modal>
  )
}
