package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.concurrent.TimeUnit;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.mail.LoginOption;
import io.vertx.ext.mail.MailAttachment;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.StartTLSOptions;

import org.acme.employeescheduling.dto.EmailSettings;
import org.acme.employeescheduling.dto.EmailTemplate;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.SendShiftEmailRequest;
import org.acme.employeescheduling.security.RichHtmlSanitizer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.LaunchMode;

/**
 * @brief Emails shifts to employees (SMTP from DB or .env fallback).
 *
 * @details Subject and HTML body come from the structure email template (Configuration → Email
 *          Template); {{Nominativo}} and {{Giorno}} placeholders are replaced with the employee's
 *          name and period label. If SMTP is not fully configured, sending is blocked
 *          (409 SMTP_NOT_CONFIGURED) and the frontend disables email buttons. The shifts PDF
 *          arrives from the frontend (Base64) and is attached. With quarkus.mailer.mock=true
 *          (the development default), emails are only logged.
 */
@RolesAllowed("ADMIN")
@Path("/email")
@Produces(MediaType.APPLICATION_JSON)
public class EmailResource {

    private static final Logger logger = Logger.getLogger(EmailResource.class.getName());
    private static final int MAX_PDF_BYTES = 10 * 1024 * 1024;
    private static final int MAX_BASE64_LENGTH = ((MAX_PDF_BYTES + 2) / 3) * 4;

    /** @brief Subject used if the structure template is not configured. */
    private static final String DEFAULT_SUBJECT = "Turni {{Giorno}}";
    /** @brief Body used if the structure template is not configured. */
    private static final String DEFAULT_BODY =
        "<p>Ciao {{Nominativo}},<br>in allegato trovi i tuoi turni per il periodo {{Giorno}}.</p>";

    private final Mailer mailer;
    private final DemoDataRepository repo;
    private final Vertx vertx;

    @Inject
    public EmailResource(Mailer mailer, DemoDataRepository repo, Vertx vertx) {
        this.mailer = mailer;
        this.repo = repo;
        this.vertx = vertx;
    }

    /**
     * @brief true if email sending is available (SMTP fully configured).
     * @details Two valid cases:
     *          - complete DB settings: host present, sender derivable (mail_from or username),
     *            and password present if a user is required;
     *          - empty DB but active .env fallback: quarkus.mailer.host present and mock disabled
     *            (mock is active by default in development/tests → not configured; emails would
     *            only go to logs).
     *          If the host is in the DB but configuration is incomplete, do NOT fall back:
     *          deliver() would still use DB values.
     */
    private boolean isSmtpConfigured() {
        EmailSettings s = repo.getEmailSettingsOrm();
        if (s != null && s.getHost() != null && !s.getHost().isBlank()) {
            boolean credentialsOk = s.getUsername() == null || s.getUsername().isBlank()
                || (s.getPassword() != null && !s.getPassword().isBlank());
            boolean senderOk = (s.getMailFrom() != null && !s.getMailFrom().isBlank())
                || (s.getUsername() != null && !s.getUsername().isBlank());
            return credentialsOk && senderOk;
        }
        Config config = ConfigProvider.getConfig();
        String host = config.getOptionalValue("quarkus.mailer.host", String.class).orElse("");
        if (host.isBlank()) return false;
        boolean mock = config.getOptionalValue("quarkus.mailer.mock", Boolean.class)
            .orElse(LaunchMode.current() != LaunchMode.NORMAL);
        return !mock;
    }

    /**
     * @brief Delivers email using DB SMTP settings when configured, otherwise the .env fallback.
     * @details With a host configured under Configuration → Email Settings, builds a Vert.x
     *          MailClient on demand (changes take effect IMMEDIATELY, without restart); without a
     *          host, uses the Quarkus Mailer configured by .env (which may be in mock mode in development).
     */
    private void deliver(String to, String subject, String html, String filename, byte[] pdf) throws Exception {
        EmailSettings s = repo.getEmailSettingsOrm();
        if (s == null || s.getHost() == null || s.getHost().isBlank()) {
            Mail mail = Mail.withHtml(to, subject, html);
            if (pdf != null) mail.addAttachment(filename, pdf, "application/pdf");
            mailer.send(mail);
            return;
        }
        MailConfig cfg = new MailConfig()
            .setHostname(s.getHost())
            .setPort(s.getPort() > 0 ? s.getPort() : 587)
            .setStarttls(s.isStartTls() ? StartTLSOptions.REQUIRED : StartTLSOptions.OPTIONAL);
        if (s.getUsername() != null && !s.getUsername().isBlank()) {
            cfg.setLogin(LoginOption.REQUIRED)
               .setUsername(s.getUsername())
               .setPassword(s.getPassword() != null ? s.getPassword() : "");
        }
        MailClient client = MailClient.create(vertx, cfg);
        try {
            String from = s.getMailFrom() != null && !s.getMailFrom().isBlank() ? s.getMailFrom() : s.getUsername();
            MailMessage message = new MailMessage()
                .setFrom(from)
                .setTo(to)
                .setSubject(subject)
                .setHtml(html);
            if (pdf != null) {
                message.setAttachment(MailAttachment.create()
                    .setName(filename)
                    .setContentType("application/pdf")
                    .setData(Buffer.buffer(pdf)));
            }
            client.sendMail(message).toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        } finally {
            client.close();
        }
    }

    /** @brief Replaces {{Nominativo}} and {{Giorno}} placeholders in text. */
    private static String fillPlaceholders(String text, String fullName, String periodLabel) {
        return text
            .replace("{{Nominativo}}", fullName != null ? fullName : "")
            .replace("{{Giorno}}", periodLabel != null ? periodLabel : "");
    }

    /**
     * @brief Classifies a sending exception into a typed code for the frontend.
     * @details Walks the cause chain and inspects classes and SMTP message excerpts (535/550
     *          codes, auth/sender/recipient/quota keywords...). The frontend translates the code
     *          into a clear localized user message; raw details remain only in server logs.
     */
    private static String classifySendError(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException
                || t instanceof java.net.ConnectException
                || t instanceof java.net.NoRouteToHostException
                || t instanceof java.net.SocketTimeoutException)
                return "CONNECTION_FAILED";
            all.append(t.getClass().getSimpleName()).append(' ')
               .append(t.getMessage() != null ? t.getMessage() : "").append('\n');
        }
        String msg = all.toString().toLowerCase();
        if (msg.contains("535") || msg.contains("auth")) return "AUTH_FAILED";
        if (msg.contains("quota") || msg.contains("limit exceeded") || msg.contains("too many")
            || msg.contains("rate")) return "QUOTA_EXCEEDED";
        if (msg.contains("sender") || msg.contains("mail from")) return "SENDER_REJECTED";
        if (msg.contains("recipient") || msg.contains("rcpt") || msg.contains("mailbox")) return "RECIPIENT_REJECTED";
        if (msg.contains("connect") || msg.contains("timeout") || msg.contains("starttls")
            || msg.contains("ssl") || msg.contains("tls") || msg.contains("handshake")) return "CONNECTION_FAILED";
        return "SEND_FAILED";
    }

    /**
     * @brief Structure delivery log for a period (latest successful delivery per employee).
     * @details The frontend loads it after "Generate PDF" to show "Sent on ..." even after a page
     *          change or restart.
     */
    @GET
    @Path("/log")
    public Response getLog(@QueryParam("structureId") @DefaultValue("0") int structureId,
                           @QueryParam("periodSlug") String periodSlug) {
        if (structureId <= 0 || !isValidPeriodSlug(periodSlug))
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
        return Response.ok(repo.getEmailLogOrm(structureId, periodSlug)).build();
    }

    private static boolean isValidPeriodSlug(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,64}");
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String safeFilename(String value) {
        String name = value == null ? "" : value.replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_").trim();
        if (name.isBlank()) name = "turni.pdf";
        if (name.toLowerCase().endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        return name.substring(0, Math.min(name.length(), 116)) + ".pdf";
    }

    /**
     * @brief Sends the employee an email with their shifts and the attached PDF.
     * @return 200 {sent:true} | 400 NO_EMAIL/BAD_REQUEST | 404 NO_EMPLOYEE | 409 SMTP_NOT_CONFIGURED | 502 SEND_FAILED
     */
    // Sending shifts is operational work, not administration: the class is restricted to ADMIN
    // because it exposes SMTP settings, but this endpoint is also needed by CAPOSALA users.
    @RolesAllowed({"ADMIN", "CAPOSALA"})
    @POST
    @Path("/send-shifts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendShifts(SendShiftEmailRequest req) {
        if (req == null || req.getEmployeeId() <= 0 || req.getStructureId() <= 0
                || !isValidPeriodSlug(req.getPeriodSlug())
                || req.getPeriodLabel() == null || req.getPeriodLabel().length() > 200
                || req.getPdfBase64() == null || req.getPdfBase64().isBlank()
                || req.getPdfBase64().length() > MAX_BASE64_LENGTH)
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();

        if (!isSmtpConfigured())
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "SMTP_NOT_CONFIGURED")).build();

        Employee employee = repo.findEmployeeByIdOrm(req.getEmployeeId());
        if (employee == null)
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "NO_EMPLOYEE")).build();
        if (!repo.employeeBelongsToStructureOrm(req.getEmployeeId(), req.getStructureId()))
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
        // A disabled employee must not receive shifts: the report (which does not filter active
        // employees) would still list them among recipients.
        if (!employee.isActive())
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "EMPLOYEE_INACTIVE")).build();

        String address = employee.getEmail();
        if (address == null || address.isBlank())
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "NO_EMAIL")).build();
        if (!address.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "INVALID_EMAIL")).build();

        EmailTemplate tpl = repo.getEmailTemplateOrm(req.getStructureId());
        String subjectTpl = tpl.getSubject() != null && !tpl.getSubject().isBlank() ? tpl.getSubject() : DEFAULT_SUBJECT;
        String bodyTpl = tpl.getBody() != null && !tpl.getBody().isBlank() ? tpl.getBody() : DEFAULT_BODY;

        String fullName = employee.getFullName();
        String subject = fillPlaceholders(subjectTpl, fullName, req.getPeriodLabel())
            .replace('\r', ' ').replace('\n', ' ').trim();
        String body = RichHtmlSanitizer.sanitize(
                fillPlaceholders(bodyTpl, escapeHtml(fullName), escapeHtml(req.getPeriodLabel())));

        byte[] pdf;
        try {
            pdf = Base64.getDecoder().decode(req.getPdfBase64());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_PDF")).build();
        }
        if (pdf.length == 0 || pdf.length > MAX_PDF_BYTES || pdf.length < 5
                || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-')
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_PDF")).build();

        String filename = safeFilename(req.getFilename());
        try {
            deliver(address, subject, body, filename, pdf);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error sending shift email to " + address, e);
            return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", classifySendError(e))).build();
        }
        boolean[] audited = { false };
        try {
            // SMTP delivery stays outside the writer gate; only the brief audit upsert is
            // serialized to avoid WAL upgrades with concurrent snapshots. Employee revalidation
            // occurs inside the upsert transaction: during SMTP delivery, the employee may have
            // been moved or deleted.
            DatabaseRequestGate.withWriterPermit(() -> audited[0] = repo.logEmailSentOrm(
                req.getStructureId(), req.getEmployeeId(), req.getPeriodSlug(),
                req.getPeriodLabel(), address, filename));
            if (!audited[0])
                logger.log(Level.WARNING, "Email delivered to " + address + " but audit log skipped: employee "
                    + req.getEmployeeId() + " no longer belongs to structure " + req.getStructureId());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Email delivered but audit log update failed", e);
        }
        return Response.ok(Map.of("sent", true, "to", address)).build();
    }

    // ─── SMTP settings (Configuration → Email Settings) ───────────────────────

    /** @brief Saved SMTP settings (password never returned: only the has_password flag). */
    @GET
    @Path("/settings")
    public Response getSettings() {
        EmailSettings s = repo.getEmailSettingsOrm();
        if (s == null) s = new EmailSettings();
        s.setHasPassword(s.getPassword() != null && !s.getPassword().isBlank());
        s.setConfigured(isSmtpConfigured());
        s.setPassword("");
        return Response.ok(s).build();
    }

    /** @brief Saves SMTP settings (empty password = keep saved value). Takes effect immediately. */
    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveSettings(EmailSettings s) {
        if (s == null)
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
        if (s.getPort() < 0 || s.getPort() > 65535)
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
        boolean ok = repo.saveEmailSettingsOrm(s);
        return ok ? getSettings() : Response.serverError().build();
    }

    /** @brief Sends a test email to the specified recipient using current settings. */
    @POST
    @Path("/settings/test")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendTest(Map<String, String> body) {
        String to = body != null ? body.get("to") : null;
        if (to == null || !to.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "INVALID_EMAIL")).build();
        if (!isSmtpConfigured())
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "SMTP_NOT_CONFIGURED")).build();
        try {
            deliver(to, "Test SMTP — Gestione Turni",
                "<p>Questa è una email di prova inviata dalla configurazione SMTP di Gestione Turni.</p>",
                null, null);
            return Response.ok(Map.of("sent", true, "to", to)).build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error sending test email to " + to, e);
            return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", classifySendError(e))).build();
        }
    }
}
