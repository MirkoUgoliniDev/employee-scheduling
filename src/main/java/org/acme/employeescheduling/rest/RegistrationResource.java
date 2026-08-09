package org.acme.employeescheduling.rest;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.employeescheduling.persistence.AppUserEntity;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * @brief User self-registration with different modes:
 *
 *          <p><b>Standalone mode</b> (local desktop, SQLite — no email server): no OTP
 *          verification. The first user becomes an active ADMIN by entering only username and
 *          password; subsequent users are created as CAPOSALA pending approval (the ADMIN
 *          activates them through {@code /users}). OTP endpoints return
 *          {@code OTP_NOT_REQUIRED}.</p>
 *
 *          <p><b>Server mode</b> (multiuser PostgreSQL): complete OTP flow — email → six-digit
 *          code → one-time token → account creation. The first user is created as an active
 *          ADMIN; others are CAPOSALA pending approval, with an email notification to active
 *          ADMIN users.</p>
 *
 *          <p>The mode is configured through {@code app.registration.mode}
 *          ({@code auto} derives it from the database: sqlite → standalone, postgresql → server).</p>
 *
 *          <p>Abuse protection: per-email/IP rate limits on OTP sends and completion attempts;
 *          random 128-bit registration token; hashed OTP compared in constant time; JVM lock on
 *          first-user creation (including commit).</p>
 */
@Path("/auth/register")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RegistrationResource {

    private static final Logger logger = Logger.getLogger(RegistrationResource.class.getName());

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{1,64}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{3,64}$");
    private static final Pattern OTP = Pattern.compile("^\\d{6}$");
    private static final String OTP_SUBJECT = "Codice di verifica registrazione";
    private static final String APPROVAL_SUBJECT = "Nuova registrazione in attesa di approvazione";

    /** @brief Serializes first-user creation (JVM lock, including commit). */
    private static final Object FIRST_USER_LOCK = new Object();

    private final Mailer mailer;
    private final OtpStore store;

    @ConfigProperty(name = "app.registration.mode", defaultValue = "auto")
    String registrationMode;

    @ConfigProperty(name = "app.database.kind", defaultValue = "sqlite")
    String databaseKind;

    @Inject
    HttpServerRequest serverRequest;

    @Inject
    public RegistrationResource(Mailer mailer, OtpStore store) {
        this.mailer = mailer;
        this.store = store;
    }

    /** @brief true if the mode requires email verification through OTP. */
    boolean isServerMode() {
        return "server".equals(registrationMode)
                || ("auto".equals(registrationMode) && "postgresql".equals(databaseKind));
    }

    /**
     * @brief Registration state: active mode, whether the next account will be the first user
     *        (active ADMIN), and whether OTP is required.
     */
    @PermitAll
    @GET
    @Path("/status")
    public Response status() {
        return Response.ok(Map.of(
                "firstUser", AppUserEntity.count() == 0,
                "mode", isServerMode() ? "server" : "standalone",
                "otpRequired", isServerMode())).build();
    }

    /**
     * @brief Step 1 (server mode only) — issues an OTP for the specified email.
     * @details OTP does not exist in standalone mode: returns {@code OTP_NOT_REQUIRED}. The
     *          per-email and per-IP rate limit is applied BEFORE checking email existence: probing
     *          already-registered addresses also consumes quota, preventing unlimited enumeration.
     *          An already-verified flow (token issued but not completed) is not overwritten:
     *          resending is denied.
     */
    @PermitAll
    @POST
    @Path("/otp")
    public Response requestOtp(Map<String, String> body) {
        if (!isServerMode())
            return ApiErrors.badRequest("OTP_NOT_REQUIRED");

        String email = normalizeEmail(body != null ? body.get("email") : null);
        if (!isValidEmail(email))
            return ApiErrors.badRequest("EMAIL_INVALID");

        // Rate-limit BEFORE any other logic: every request consumes quota.
        if (store.registerEmailSend(email))
            return ApiErrors.tooManyRequests("OTP_TOO_MANY");
        if (store.registerIpSend(clientIp()))
            return ApiErrors.tooManyRequests("OTP_TOO_MANY");

        OtpStore.PendingRegistration pending = store.get(email);
        if (pending != null && pending.registrationToken() != null)
            return ApiErrors.conflict("OTP_ALREADY_USED");

        if (AppUserEntity.findByEmail(email) != null)
            return ApiErrors.conflict("EMAIL_ALREADY_REGISTERED");

        String otp = store.newOtp();
        store.put(email, sha256(otp));

        try {
            mailer.send(Mail.withHtml(email, OTP_SUBJECT,
                    "<p>Usa questo codice per completare la registrazione:</p>"
                        + "<p style=\"font-size:1.5em;letter-spacing:.2em;font-weight:bold\">" + otp
                        + "</p><p>Il codice scade tra 5 minuti.</p>"));
        } catch (Exception e) {
            store.invalidate(email);
            logger.log(Level.WARNING, "Invio OTP a " + email + " fallito", e);
            return ApiErrors.serverError("OTP_SEND_FAILED");
        }

        return Response.ok(Map.of("sent", true, "email", email)).build();
    }

    /**
     * @brief Step 2 (server mode only) — verifies the OTP and issues a one-time token.
     */
    @PermitAll
    @POST
    @Path("/verify")
    public Response verify(Map<String, String> body) {
        if (!isServerMode())
            return ApiErrors.badRequest("OTP_NOT_REQUIRED");

        String email = normalizeEmail(body != null ? body.get("email") : null);
        String otp = body != null ? body.get("otp") : null;
        if (!isValidEmail(email) || otp == null || !OTP.matcher(otp).matches())
            return ApiErrors.badRequest("BAD_REQUEST");

        OtpStore.PendingRegistration reg = store.get(email);
        if (reg == null)
            return ApiErrors.badRequest("OTP_INVALID");

        if (reg.registrationToken() != null)
            return ApiErrors.conflict("OTP_ALREADY_USED");

        if (!constantTimeEquals(sha256(otp), reg.otpHash)) {
            if (reg.attemptsLeft.decrementAndGet() <= 0) {
                store.invalidate(email);
                return ApiErrors.badRequest("OTP_INVALID");
            }
            return ApiErrors.badRequest("OTP_INVALID");
        }

        // 128-bit one-time token (32 hex digits): NOT derived from email and not guessable.
        // Assignment is atomic: if two verifications of the same code arrive together, only one
        // issues the token and the other receives the same rejection as the check above, instead
        // of overwriting it and invalidating the other's registration.
        if (!reg.assignRegistrationToken(store.newRegistrationToken()))
            return ApiErrors.conflict("OTP_ALREADY_USED");
        return Response.ok(Map.of("token", reg.registrationToken())).build();
    }

    /**
     * @brief Step 3 — creates the account.
     *
     * @details <b>Standalone:</b> token not required (ignored). First user → active ADMIN;
     *          subsequent users → CAPOSALA pending approval, no email notification (the ADMIN
     *          approves them through {@code /users}).
     *
     *          <b>Server:</b> token required (issued by {@code /verify}). First user → active ADMIN;
     *          subsequent users → CAPOSALA pending approval with notification to active ADMIN users.
     *
     *          Rate-limited per IP (anti-brute-force), with a JVM lock on the first user.
     */
    @PermitAll
    @POST
    @Path("/complete")
    public Response complete(Map<String, String> body) {
        String token = body != null ? body.get("token") : null;
        String username = body != null ? body.get("username") : null;
        String password = body != null ? body.get("password") : null;

        // In server mode the token is required and must be verified BEFORE profile-field
        // validation: an unknown/expired token must reveal nothing even with a malformed payload.
        String email = null;
        if (isServerMode()) {
            if (token == null || token.isBlank())
                return ApiErrors.badRequest("OTP_INVALID");
            email = store.emailForToken(token);
            if (email == null)
                return ApiErrors.badRequest("OTP_INVALID");
        }
        // Effectively-final copy for the lambda below (email is reassigned above).
        final String verifiedEmail = email;

        if (!USERNAME.matcher(username == null ? "" : username).matches()
                || password == null || password.length() < 8 || password.length() > 100)
            return ApiErrors.badRequest("BAD_REQUEST");

        if (store.registerCompleteAttempt(clientIp()))
            return ApiErrors.tooManyRequests("OTP_TOO_MANY");

        synchronized (FIRST_USER_LOCK) {
            try {
                return QuarkusTransaction.requiringNew().call(() -> createAccount(verifiedEmail, username, password));
            } catch (PersistenceException e) {
                // TOCTOU on UNIQUE constraints (username/email): resolve the race in the DB and
                // turn the constraint exception into a clean 409 instead of a 500.
                if (verifiedEmail != null) store.invalidate(verifiedEmail);
                return ApiErrors.conflict("USER_DUPLICATE");
            }
        }
    }

    /** @brief Runs count+create+persist in a dedicated transaction (commit included in the lock). */
    private Response createAccount(String email, String username, String password) {
        if (AppUserEntity.findByUsername(username) != null)
            return ApiErrors.conflict("USER_DUPLICATE");
        if (email != null && AppUserEntity.findByEmail(email) != null) {
            store.invalidate(email);
            return ApiErrors.conflict("EMAIL_ALREADY_REGISTERED");
        }

        boolean firstUser = AppUserEntity.count() == 0;

        AppUserEntity entity = AppUserEntity.create(
                username, password,
                firstUser ? AppUserEntity.ROLE_ADMIN : AppUserEntity.ROLE_CAPOSALA,
                username);
        entity.email = email;
        // The first user has nobody to approve them, so they are created active.
        entity.active = firstUser;
        entity.persist();

        if (email != null) store.invalidate(email);
        // Email notification only in server mode: in standalone, the ADMIN sees the "Pending"
        // row directly in /users.
        if (!firstUser && isServerMode())
            notifyAdmins(username, email);

        return Response.status(Response.Status.CREATED)
                .entity(Map.of("created", true,
                        "pendingApproval", !firstUser,
                        "admin", firstUser)).build();
    }

    /** @brief Sends approval notification to active ADMIN users with an email. Best effort. */
    private void notifyAdmins(String username, String email) {
        List<AppUserEntity> admins = AppUserEntity.<AppUserEntity>list("role", AppUserEntity.ROLE_ADMIN)
                .stream()
                .filter(u -> u.active && u.email != null && !u.email.isBlank())
                .toList();
        if (admins.isEmpty()) {
            logger.log(Level.INFO,
                    "Registrazione di '" + username + "' in attesa, ma nessun ADMIN attivo con email per la notifica");
            return;
        }
        String body = "<p>Un nuovo CAPOSALA si è registrato e attende la tua approvazione:</p>"
                + "<ul><li><b>Username:</b> " + escapeHtml(username)
                + "</li><li><b>Email:</b> " + escapeHtml(email) + "</li></ul>"
                + "<p>Vai in <b>Utenti</b> e attiva l'account per consentirne l'accesso.</p>";
        for (AppUserEntity admin : admins) {
            try {
                mailer.send(Mail.withHtml(admin.email, APPROVAL_SUBJECT, body));
            } catch (Exception e) {
                logger.log(Level.WARNING, "Notifica approvazione a " + admin.email + " fallita", e);
            }
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }

    private String clientIp() {
        try {
            return serverRequest == null || serverRequest.remoteAddress() == null
                    ? "unknown" : serverRequest.remoteAddress().host();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
