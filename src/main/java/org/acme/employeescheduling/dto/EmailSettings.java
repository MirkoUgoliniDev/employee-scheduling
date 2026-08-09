package org.acme.employeescheduling.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Global SMTP settings, editable under Configuration → Email Settings.
 *
 * @details Single row (id=1) in the email_settings table. The password is WRITE-ONLY: GET always
 *          clears it and replaces it with the {@link #hasPassword} flag; in PUT, an empty password
 *          means "do not change it". If host is empty, the backend uses the .env fallback
 *          (quarkus-mailer).
 */
public class EmailSettings {

    @JsonProperty("host")
    private String host = "";

    @JsonProperty("port")
    private int port = 587;

    /** @brief true = STARTTLS required. */
    @JsonProperty("start_tls")
    private boolean startTls = true;

    @JsonProperty("username")
    private String username = "";

    /** @brief Write-only: empty means keep the saved value. Never returned by GET. */
    @JsonProperty("password")
    private String password = "";

    /** @brief Sender, for example "SMTP Scheduler <mario@example.com>". */
    @JsonProperty("mail_from")
    private String mailFrom = "";

    /** @brief Read-only: true if a password has already been saved. */
    @JsonProperty("has_password")
    private boolean hasPassword = false;

    /**
     * @brief Read-only: true if email sending is available.
     * @details Complete DB settings or active .env fallback (host present and mock disabled).
     *          If false, the frontend disables email sending.
     */
    @JsonProperty("configured")
    private boolean configured = false;

    public EmailSettings() {}

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isStartTls() { return startTls; }
    public void setStartTls(boolean startTls) { this.startTls = startTls; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }

    public boolean isHasPassword() { return hasPassword; }
    public void setHasPassword(boolean hasPassword) { this.hasPassword = hasPassword; }

    public boolean isConfigured() { return configured; }
    public void setConfigured(boolean configured) { this.configured = configured; }
}
