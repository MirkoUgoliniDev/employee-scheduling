package org.acme.employeescheduling.persistence;

import java.time.LocalDateTime;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.jpa.Password;
import io.quarkus.security.jpa.Roles;
import io.quarkus.security.jpa.UserDefinition;
import io.quarkus.security.jpa.Username;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * @brief Application user who can authenticate.
 *
 * @details The {@code @UserDefinition} annotations make this table the identity source for
 *          Quarkus Security: username, password hash, and roles are read from here, without
 *          manually implementing credential comparison — where homegrown authentication most
 *          often goes wrong.
 *
 *          The password is stored only as a **bcrypt hash**: the cost is deliberately high so a
 *          dictionary attack on a stolen database is expensive as well.
 *
 *          Only two roles, as established by the project decision:
 *          <ul>
 *            <li>{@link #ROLE_ADMIN} — configuration, backups, users;</li>
 *            <li>{@link #ROLE_CAPOSALA} — management of shifts, employees, and records.</li>
 *          </ul>
 *          The role is deliberately not called "operator": in this domain an *Operator* is the
 *          person being scheduled, and using the same word for an application user would make
 *          every interface sentence ambiguous.
 */
@Entity
@Table(name = "app_users")
@UserDefinition
public class AppUserEntity extends PanacheEntityBase {

    private static final java.time.format.DateTimeFormatter TIMESTAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Configures the application, administers backups, and manages users. */
    public static final String ROLE_ADMIN = "ADMIN";
    /** Manages shifts, employees, and records; does not manage backups or users. */
    public static final String ROLE_CAPOSALA = "CAPOSALA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Username
    @Column(name = "username", nullable = false, unique = true)
    public String username;

    @Password
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    /** @brief Comma-separated roles: always one here; this is the format expected by Quarkus. */
    @Roles
    @Column(name = "role", nullable = false)
    public String role;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Text dates in "yyyy-MM-dd HH:mm:ss" format, as in the rest of the schema: the columns are
     * TEXT, and {@code LocalDateTime} causes SQLite reads to fail with "Error parsing time stamp".
     */
    @Column(name = "created_at", nullable = false)
    public String createdAt = now();

    @Column(name = "last_login_at")
    public String lastLoginAt;

    private static String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    /** @brief Display name; if absent, the UI falls back to the username. */
    @Column(name = "display_name")
    public String displayName;

    /** @brief User email (used for OTP registration and password recovery). */
    @Column(name = "email")
    public String email;

    public static AppUserEntity findByUsername(String username) {
        return find("username", username).firstResult();
    }

    public static AppUserEntity findByEmail(String email) {
        if (email == null || email.isBlank()) return null;
        // Case-insensitive: an address must not be registered twice merely because it uses
        // different capitalization (Mario@X.it vs mario@x.it).
        return find("lower(email) = lower(?1)", email).firstResult();
    }

    /** @brief Creates a user with an already-encrypted password; plaintext is not retained. */
    public static AppUserEntity create(String username, String plainPassword, String role, String displayName) {
        AppUserEntity user = new AppUserEntity();
        user.username = username;
        user.passwordHash = BcryptUtil.bcryptHash(plainPassword);
        user.role = role;
        user.displayName = displayName;
        user.active = true;
        user.createdAt = now();
        return user;
    }
    public void changePassword(String plainPassword) {
        this.passwordHash = BcryptUtil.bcryptHash(plainPassword);
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
