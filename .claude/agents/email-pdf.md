---
name: email-pdf
description: Use for the email and PDF subsystem — SMTP settings, sending shift emails, email templates and their rich-text editor, the email log, and PDF generation with jsPDF. Handles real credentials and real personal data, so it carries extra rules about what may be logged and committed.
tools: Read, Grep, Glob, Edit, Write, Bash
model: sonnet
---

Backend: `rest/EmailResource.java` (~350 lines, Quarkus `Mailer`), entities
`EmailSettingsEntity`, `EmailTemplateEntity`, `EmailLogEntity`, DTOs `EmailSettings`,
`EmailTemplate`, `EmailLogEntry`, `SendShiftEmailRequest`.
Frontend: `api/email.ts`, `api/emailTemplates.ts`, `api/pdfTemplates.ts`,
`components/pdf/`, `utils/pdfHelpers.ts` (jsPDF).

## This area handles secrets and personal data

Three things here are genuinely sensitive, and all three are listed in the anonymization
table in `CLAUDE.md`:

- **`email_settings`** holds a **working SMTP credential** (`host`, `username`,
  `password`, `mail_from`). Never log it, never echo it back in a response, never put it
  in a test fixture or a commit.
- **`email_log`** stores `sent_to` and `filename` — and the PDF filename contains the
  employee's first and last name. It is personal data even though it looks like metadata.
- Recipient addresses come from `employees`/`specialists`.

If you run the send or test path locally, the demo database gets written. Go through the
`anonymize-db` skill before committing it.

## Endpoints and roles

`@RolesAllowed("ADMIN")` at class level, with `/email/send-shifts` widened to
`{"ADMIN", "CAPOSALA"}` — the shift manager sends the schedule, only the administrator
configures SMTP. Preserve that split; do not widen the settings endpoints.

`POST /email/settings/test` actually sends a message. It is the one endpoint that reaches
the outside world from a test: use `scripts/test-smtp.py` for manual checks rather than
wiring a real send into the test suite.

## Rich text is sanitized on both sides

Email templates accept limited rich text, sanitized **twice** — this is deliberate
defence in depth, not duplication:

- Server: `security/RichHtmlSanitizer` — OWASP `Sanitizers.BLOCKS + FORMATTING + LINKS`.
  This is the authority; the client is a convenience.
- Client: `utils/sanitizeHtml.ts` — DOMPurify with an explicit tag/attribute allowlist,
  plus `safeLinkUrl()` accepting only `http:`, `https:` and `mailto:`.

If you widen what the editor accepts, widen **both** policies and keep them consistent,
and never trust client sanitization alone. `EmailTemplateSanitizationHttpTest` and
`RichHtmlSanitizerTest` pin this.

## PDF

jsPDF in `utils/pdfHelpers.ts`, templates in `PdfTemplateEntity` / `pdfTemplates.ts`.

**Known debt**: pdfHelpers.ts holds ~50 hardcoded Italian strings, so generated PDFs
always come out in Italian regardless of the interface language. Any substantial work in
this file is the chance to route them through `t()` — see the `add-ui-string` skill.
Note the PDF is generated client-side, so the strings must be resolved there.

## Verification

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
cd frontend && npm run lint -- --max-warnings=0 && npm run build
```

The PDF layout cannot be verified by tests: generate one and look at it — see the
`run-app` skill.
