/**
 * @file ShiftDaysCalendar.tsx
 * @brief Mini monthly calendar that highlights days with shifts.
 *
 * @details
 * Used in Configuration to select the source week for prepopulation:
 * days with at least one shift are highlighted (green). Clicking a day selects that day
 * (and therefore its week). Navigate by month with ‹ ›.
 * No external dependencies: custom Mon–Sun grid.
 */

import { useState } from 'react'
import { useTranslation } from 'react-i18next'

interface Props {
  /** @brief Set of "yyyy-MM-dd" days that have shifts. */
  shiftDays: Set<string>
  /** @brief Selected day in "yyyy-MM-dd" format. */
  value: string
  /** @brief Callback when a day is selected. */
  onChange: (isoDate: string) => void
}

const pad = (n: number) => String(n).padStart(2, '0')
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
/** @brief Monday 00:00 of the week containing `d`. */
function mondayOf(d: Date): Date {
  const diff = (d.getDay() + 6) % 7
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - diff)
}

export default function ShiftDaysCalendar({ shiftDays, value, onChange }: Props) {
  const { t } = useTranslation()
  const selected = value ? new Date(value.replace(/-/g, '/')) : new Date()
  const [view, setView] = useState(() => new Date(selected.getFullYear(), selected.getMonth(), 1))

  const MONTHS = [
    t('month.january', 'Gennaio'), t('month.february', 'Febbraio'), t('month.march', 'Marzo'),
    t('month.april', 'Aprile'), t('month.may', 'Maggio'), t('month.june', 'Giugno'),
    t('month.july', 'Luglio'), t('month.august', 'Agosto'), t('month.september', 'Settembre'),
    t('month.october', 'Ottobre'), t('month.november', 'Novembre'), t('month.december', 'Dicembre'),
  ]
  const DOW = [
    t('dowShort.mon', 'Lu'), t('dowShort.tue', 'Ma'), t('dowShort.wed', 'Me'),
    t('dowShort.thu', 'Gi'), t('dowShort.fri', 'Ve'), t('dowShort.sat', 'Sa'), t('dowShort.sun', 'Do'),
  ]

  const selectedWeekMonday = value ? mondayOf(selected).getTime() : -1

  // Grid: 6 weeks starting from the Monday of the week containing the first day of the month.
  const gridStart = mondayOf(new Date(view.getFullYear(), view.getMonth(), 1))
  const weeks: Date[][] = []
  for (let w = 0; w < 6; w++) {
    const row: Date[] = []
    for (let d = 0; d < 7; d++) {
      row.push(new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + w * 7 + d))
    }
    weeks.push(row)
  }

  function shiftMonth(delta: number) {
    setView(v => new Date(v.getFullYear(), v.getMonth() + delta, 1))
  }

  const btn: React.CSSProperties = { border: '1px solid #dee2e6', background: '#fff', borderRadius: 4, cursor: 'pointer', lineHeight: 1, padding: '2px 8px' }

  return (
    <div style={{ width: 250, fontSize: '0.8rem', userSelect: 'none' }}>
      <div className="d-flex align-items-center justify-content-between mb-1">
        <button type="button" style={btn} onClick={() => shiftMonth(-1)} aria-label="prev">‹</button>
        <span className="fw-semibold">{MONTHS[view.getMonth()]} {view.getFullYear()}</span>
        <button type="button" style={btn} onClick={() => shiftMonth(1)} aria-label="next">›</button>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2, textAlign: 'center' }}>
        {DOW.map((d, i) => (
          <div key={i} className="text-muted" style={{ fontSize: '0.68rem', fontWeight: 600 }}>{d}</div>
        ))}
        {weeks.flat().map((day, i) => {
          const dISO = iso(day)
          const inMonth = day.getMonth() === view.getMonth()
          const hasShift = shiftDays.has(dISO)
          const isSelected = value === dISO
          const inSelectedWeek = mondayOf(day).getTime() === selectedWeekMonday
          const bg = isSelected ? '#0d6efd' : hasShift ? '#a9dfbf' : inSelectedWeek ? '#e7f1ff' : 'transparent'
          const color = isSelected ? '#fff' : inMonth ? '#212529' : '#adb5bd'
          return (
            <div
              key={i}
              onClick={() => onChange(dISO)}
              title={hasShift ? t('config.template.hasShifts', 'Giorno con turni') : undefined}
              style={{
                cursor: 'pointer', borderRadius: 4, padding: '3px 0',
                background: bg, color,
                fontWeight: hasShift || isSelected ? 700 : 400,
                border: hasShift && !isSelected ? '1px solid #27ae60' : '1px solid transparent',
              }}
            >
              {day.getDate()}
            </div>
          )
        })}
      </div>
      <div className="d-flex align-items-center gap-1 mt-2 text-muted" style={{ fontSize: '0.7rem' }}>
        <span style={{ width: 12, height: 12, background: '#a9dfbf', border: '1px solid #27ae60', borderRadius: 3, display: 'inline-block' }} />
        {t('config.template.daysWithShifts', 'Giorni con turni')}
      </div>
    </div>
  )
}
