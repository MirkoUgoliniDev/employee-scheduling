/**
 * @file assignmentSeriesErrors.ts
 * @brief User-facing messages for the validation codes of the series endpoints.
 *
 * @details
 * Shared by the series panel and the assignment rows that cancel or restore a single occurrence:
 * both hit `/assignments/series` and must report the same text, so the mapping lives outside the
 * component file.
 */

/**
 * @brief Maps a series validation code to a user-facing message.
 * @details Exported because the per-occurrence buttons live on {@link AssignmentsPage}: cancel and
 *          restore hit the same endpoints and must report the same messages.
 */
export function translateSeriesError(code: string | null, t: (key: string, fallback: string) => string): string {
  switch (code) {
    case 'SERIES_RANGE_REQUIRED':
      return t('assignments.series.errorRange', 'Gültigkeitszeitraum ist erforderlich.')
    case 'SERIES_RANGE_INVALID':
      return t('assignments.series.errorRangeOrder', 'Das Ende muss nach dem Beginn liegen.')
    case 'SERIES_WEEKDAYS_REQUIRED':
    case 'SERIES_WEEKDAYS_INVALID':
      return t('assignments.series.errorWeekdays', 'Mindestens ein Wochentag ist nötig.')
    case 'SERIES_NTH_INVALID':
      return t('assignments.series.errorNth', 'Der Wochentag im Monat ist ungültig.')
    case 'SERIES_PATTERN_INVALID':
      return t('assignments.series.errorPattern', 'Die Wiederholung ist ungültig.')
    case 'SERIES_TOO_MANY_OCCURRENCES':
      return t('assignments.series.errorTooMany', 'Der Zeitraum enthält zu viele Termine.')
    case 'SERIES_EXCEPTION_MOVED':
      return t('assignments.series.errorMoved',
        'Dieser Termin wurde verschoben und kann nicht wiederhergestellt werden.')
    case 'SERIES_OCCURRENCE_UNKNOWN':
    case 'SERIES_UNKNOWN':
      return t('assignments.series.errorOccurrence', 'Termin nicht gefunden.')
    case 'ASSIGNMENT_CUSTOMER_UNKNOWN':
      return t('assignments.errorCustomer', 'Kunde nicht gefunden.')
    case 'ASSIGNMENT_LOCATION_UNKNOWN':
      return t('assignments.errorLocation', 'Ort nicht gefunden.')
    case 'ASSIGNMENT_LOCATION_CUSTOMER_MISMATCH':
      return t('assignments.errorLocationCustomer', 'Der Ort gehört nicht zu diesem Kunden.')
    case 'ASSIGNMENT_REQUIREMENT_REQUIRED':
      return t('assignments.errorRequirement', 'Mindestens eine Anforderung ist nötig.')
    case 'ASSIGNMENT_TITLE_REQUIRED':
      return t('assignments.errorTitle', 'Bezeichnung ist erforderlich.')
    default:
      return t('assignments.errorGeneric', 'Speichern fehlgeschlagen.')
  }
}
