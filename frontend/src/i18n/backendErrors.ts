/**
 * @file i18n/backendErrors.ts
 * @brief Translation of error codes returned by the backend.
 *
 * @details
 * The backend does not know the user's language, so it cannot compose the message: it responds
 * with a stable code (`{"error": "EMPLOYEE_CODE_REQUIRED"}`), while the text remains here with
 * all other UI strings. Previously these messages were written in Italian in REST responses
 * and appeared unchanged even for English- or German-speaking users.
 *
 * The map lives in one file because the same codes are handled by multiple modals: duplicating it
 * per component means translating a new entry in one place and forgetting it in the others.
 *
 * Every `msg.err.*` key must exist in `src/main/resources/i18n/ui-translations.tsv` for
 * all five languages.
 */

/** @brief i18n key and Italian fallback for each backend error code. */
const BACKEND_ERRORS: Record<string, [key: string, fallback: string]> = {
  BACKUP_TOOLS_UNAVAILABLE: ['msg.err.backupToolsUnavailable',
    'Backup non disponibile: gli strumenti client del database non sono installati o sono più vecchi del server.'],
  BACKUP_ADMIN_AUTH_REQUIRED: ['msg.err.backupAdminAuthRequired',
    'Token amministrativo dei backup richiesto.'],
  BACKUP_ADMIN_TOKEN_NOT_CONFIGURED: ['msg.err.backupAdminTokenNotConfigured',
    'API backup disabilitata: configura BACKUP_ADMIN_TOKEN sul server.'],
  BACKUP_ADMIN_TLS_REQUIRED: ['msg.err.backupAdminTlsRequired',
    'Connessione sicura richiesta per amministrare i backup.'],
  BACKUP_ADMIN_RATE_LIMITED: ['msg.err.backupAdminRateLimited',
    'Troppi tentativi di autenticazione: attendi un minuto.'],
  RESTORE_NOT_SUPPORTED: ['msg.err.restoreNotSupported',
    'Il ripristino automatico non è disponibile su questo database: scarica il backup e ripristinalo con pg_restore.'],
  DATABASE_BUSY: ['msg.err.databaseBusy',
    'Database occupato: riprova fra qualche istante. Nessun dato è stato modificato.'],
  BACKUP_IN_PROGRESS: ['msg.err.backupInProgress',
    'Backup in corso: riprova al termine. Nessun dato è stato modificato.'],
  SAFETY_BACKUP_FAILED: ['msg.err.safetyBackupFailed',
    'Operazione annullata: non è stato possibile creare il backup di sicurezza.'],
  NOT_A_DATABASE: ['msg.err.notADatabase',
    'Il file selezionato non è un backup del database leggibile. Nessun dato è stato modificato.'],
  INCOMPATIBLE_DATABASE: ['msg.err.incompatibleDatabase',
    'Il backup non contiene lo schema completo di questa applicazione. Nessun dato è stato modificato.'],
  NO_ROLLBACK_SNAPSHOT: ['msg.err.noRollbackSnapshot',
    'Non è stato possibile creare il backup di sicurezza: ripristino annullato senza modifiche.'],
  PROMOTION_BUSY: ['msg.err.promotionBusy',
    'Il database è occupato: ripristino annullato senza modifiche. Riprova fra qualche istante.'],
  PROMOTION_IO_ERROR: ['msg.err.promotionIoError',
    'Il ripristino non è riuscito; verifica lo stato indicato dal server.'],

  EMPLOYEE_FIRST_NAME_REQUIRED: ['msg.err.employeeFirstNameRequired', "Il nome dell'operatore è obbligatorio."],
  EMPLOYEE_LAST_NAME_REQUIRED: ['msg.err.employeeLastNameRequired', "Il cognome dell'operatore è obbligatorio."],
  EMPLOYEE_CODE_REQUIRED: ['msg.err.employeeCodeRequired', "Il codice dell'operatore è obbligatorio."],
  EMPLOYEE_CODE_IN_USE: ['msg.err.employeeCodeInUse', 'Codice operatore già in uso. Scegline uno diverso.'],
  EMPLOYEE_EMAIL_INVALID: ['msg.err.employeeEmailInvalid', "Indirizzo email non valido."],

  SPECIALIST_FIRST_NAME_REQUIRED: ['msg.err.specialistFirstNameRequired', 'Il nome dello specialista è obbligatorio.'],
  SPECIALIST_LAST_NAME_REQUIRED: ['msg.err.specialistLastNameRequired', 'Il cognome dello specialista è obbligatorio.'],
  SPECIALIST_CODE_REQUIRED: ['msg.err.specialistCodeRequired', 'Il codice dello specialista è obbligatorio.'],
  SPECIALIST_CODE_IN_USE: ['msg.err.specialistCodeInUse', 'Codice specialista già in uso. Scegline uno diverso.'],
  SPECIALIST_EMAIL_INVALID: ['msg.err.specialistEmailInvalid', 'Indirizzo email non valido.'],

  LOCATION_NAME_REQUIRED: ['msg.err.locationNameRequired', 'Il nome della sede è obbligatorio.'],
  LOCATION_ORDER_REQUIRED: ['msg.err.locationOrderRequired', "L'ordine della sede deve essere maggiore di zero."],
  LOCATION_CODE_IN_USE: ['msg.err.locationCodeInUse', 'Codice sede già in uso. Scegline uno diverso.'],
  LOCATION_SKILLS_INVALID: ['msg.err.locationSkillsInvalid', 'Competenze non valide per la sede.'],
  LOCATION_SPECIALIST_INVALID: ['msg.err.locationSpecialistInvalid', 'Specialista non valido per questa struttura.'],
  LOCATION_IN_USE: ['msg.err.locationInUse', 'Sede utilizzata da turni o template: non può essere eliminata.'],

  LANGUAGE_CODE_DESCRIPTION_REQUIRED: ['msg.warning.langCodeDescRequired', 'Codice e descrizione sono obbligatori.'],
  LANGUAGE_NOT_FOUND: ['msg.err.languageNotFound', 'Lingua non trovata.'],
  LANGUAGE_ACTIVE_CANNOT_DELETE: ['msg.err.languageActiveCannotDelete', 'Impossibile eliminare la lingua attiva.'],
  LANGUAGE_INSERT_FAILED: ['msg.err.languageInsertFailed', "Errore durante l'inserimento della lingua."],

  LABEL_KEY_DESCRIPTION_REQUIRED: ['msg.warning.labelKeyDescRequired', 'Chiave e descrizione obbligatorie.'],
  LABEL_NOT_FOUND: ['msg.err.labelNotFound', 'Etichetta non trovata.'],
  LABEL_INSERT_FAILED: ['msg.err.labelInsertFailed', "Errore durante l'inserimento dell'etichetta."],

  STRUCTURE_NAME_REQUIRED: ['msg.warning.structureNameRequired', 'Il nome della struttura è obbligatorio.'],

  USER_USERNAME_REQUIRED: ['msg.err.userUsernameRequired', 'Il nome utente è obbligatorio.'],
  USER_PASSWORD_REQUIRED: ['msg.err.userPasswordRequired', 'La password è obbligatoria.'],
  USER_ROLE_INVALID: ['msg.err.userRoleInvalid', 'Ruolo non valido.'],
  USER_DUPLICATE: ['msg.err.userDuplicate', 'Nome utente già in uso.'],
  USER_NOT_FOUND: ['msg.err.userNotFound', 'Utente non trovato.'],
  USER_CANNOT_SELF_DEACTIVATE: ['msg.err.userCannotSelfDeactivate', 'Non puoi disattivare il tuo account.'],
  USER_EMAIL_DUPLICATE: ['msg.err.userEmailDuplicate', 'Indirizzo email già in uso.'],
  USER_LAST_ADMIN: ['msg.err.userLastAdmin', 'Non puoi rimuovere l\'ultimo amministratore attivo.'],
  ACCOUNT_INACTIVE: ['msg.err.accountInactive', 'Account in attesa di approvazione o disattivato.'],

  EMAIL_INVALID: ['msg.err.emailInvalid', 'Indirizzo email non valido.'],
  EMAIL_ALREADY_REGISTERED: ['msg.err.emailAlreadyRegistered', 'Questa email è già registrata.'],
  OTP_INVALID: ['msg.err.otpInvalid', 'Codice non valido o scaduto.'],
  OTP_ALREADY_USED: ['msg.err.otpAlreadyUsed', 'Codice già utilizzato. Richiedine uno nuovo.'],
  OTP_TOO_MANY: ['msg.err.otpTooMany', 'Troppi tentativi. Riprova fra qualche minuto.'],
  OTP_SEND_FAILED: ['msg.err.otpSendFailed', "Invio del codice non riuscito. Riprova."],
  OTP_NOT_REQUIRED: ['msg.err.otpNotRequired', 'La verifica email non è richiesta in questa installazione.'],
}

/** @brief Minimal react-i18next `t` signature, to avoid coupling the module to the full type. */
type Translate = (key: string, fallback: string) => string

/**
 * @brief Translates a backend error code into the message to display.
 * @param code Code extracted with `errorCode()`, or null if the response did not contain one.
 * @param t    react-i18next translation function.
 * @return The localized message, or null if the code is unknown: in that case the caller
 *         displays its own generic message without exposing technical strings.
 */
export function backendErrorText(code: string | null, t: Translate): string | null {
  if (!code) return null
  const entry = BACKEND_ERRORS[code]
  return entry ? t(entry[0], entry[1]) : null
}
