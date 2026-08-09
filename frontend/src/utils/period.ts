/**
 * @file period.ts
 * @brief Shared helpers for weekly/monthly periods (window, label, slug).
 *
 * @details
 * The granularity (`WindowMode`) comes from the store (`shiftWindowMode`, configured
 * under Configuration → General parameters). Used by ReportPage; ShiftsPage and
 * ConfigPage still have equivalent local copies (future refactoring).
 */

export type WindowMode = 'week' | 'month'

/** @brief Minimal translation function (key + fallback). */
type TFn = (key: string, def: string) => string

/** @brief Monday at 00:00 of the week containing `d` (ISO, consistent with the Italian locale). */
export function startOfWeekMonday(d: Date): Date {
  const diff = (d.getDay() + 6) % 7 // getDay: 0=Sun..6=Sat → 0=Mon..6=Sun
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - diff)
}

/** @brief Normalizes a date to the start of its period (Monday for a week, the 1st for a month). Idempotent. */
export function normalizeViewStart(d: Date, mode: WindowMode): Date {
  return mode === 'week'
    ? startOfWeekMonday(d)
    : new Date(d.getFullYear(), d.getMonth(), 1)
}

/** @brief Right-open [start, end) window for the selected granularity. */
export function windowFor(viewStart: Date, mode: WindowMode): { start: Date; end: Date } {
  const start = normalizeViewStart(viewStart, mode)
  const end = mode === 'week'
    ? new Date(start.getFullYear(), start.getMonth(), start.getDate() + 7)
    : new Date(start.getFullYear(), start.getMonth() + 1, 1)
  return { start, end }
}

/** @brief Previous/next period (delta = ±1). */
export function addPeriods(viewStart: Date, mode: WindowMode, delta: number): Date {
  return mode === 'week'
    ? new Date(viewStart.getFullYear(), viewStart.getMonth(), viewStart.getDate() + 7 * delta)
    : new Date(viewStart.getFullYear(), viewStart.getMonth() + delta, 1)
}

/** @brief Filename-safe period slug: "2024-12" (month) or "2024-12-16" (Monday of the week). */
export function periodSlug(viewStart: Date, mode: WindowMode): string {
  const s = normalizeViewStart(viewStart, mode)
  const p = (n: number) => String(n).padStart(2, '0')
  return mode === 'week'
    ? `${s.getFullYear()}-${p(s.getMonth() + 1)}-${p(s.getDate())}`
    : `${s.getFullYear()}-${p(s.getMonth() + 1)}`
}

/**
 * @brief Localized period label: "December 2024" (month) or "16–22 Dec 2024" (week).
 * @details Same adaptive format as Shift Management, including weeks spanning two months or years.
 */
export function periodLabel(viewStart: Date, mode: WindowMode, t: TFn): string {
  const MONTHS = [
    t('month.january', 'Gennaio'), t('month.february', 'Febbraio'), t('month.march', 'Marzo'),
    t('month.april', 'Aprile'), t('month.may', 'Maggio'), t('month.june', 'Giugno'),
    t('month.july', 'Luglio'), t('month.august', 'Agosto'), t('month.september', 'Settembre'),
    t('month.october', 'Ottobre'), t('month.november', 'Novembre'), t('month.december', 'Dicembre'),
  ]
  const MONTHS_SHORT = [
    t('monthShort.jan', 'Gen'), t('monthShort.feb', 'Feb'), t('monthShort.mar', 'Mar'),
    t('monthShort.apr', 'Apr'), t('monthShort.may', 'Mag'), t('monthShort.jun', 'Giu'),
    t('monthShort.jul', 'Lug'), t('monthShort.aug', 'Ago'), t('monthShort.sep', 'Set'),
    t('monthShort.oct', 'Ott'), t('monthShort.nov', 'Nov'), t('monthShort.dec', 'Dic'),
  ]
  if (mode === 'month')
    return `${MONTHS[viewStart.getMonth()]} ${viewStart.getFullYear()}`
  const s = startOfWeekMonday(viewStart)
  const e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 6) // inclusive Sunday
  if (s.getMonth() === e.getMonth())
    return `${s.getDate()}–${e.getDate()} ${MONTHS_SHORT[s.getMonth()]} ${s.getFullYear()}`
  if (s.getFullYear() === e.getFullYear())
    return `${s.getDate()} ${MONTHS_SHORT[s.getMonth()]} – ${e.getDate()} ${MONTHS_SHORT[e.getMonth()]} ${e.getFullYear()}`
  return `${s.getDate()} ${MONTHS_SHORT[s.getMonth()]} ${s.getFullYear()} – ${e.getDate()} ${MONTHS_SHORT[e.getMonth()]} ${e.getFullYear()}`
}
