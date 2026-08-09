package org.acme.employeescheduling.rest;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @brief Transient store for registrations in progress (OTP flow).
 *
 * @details OTP requests and their confirmation live here in memory: OTPs never travel to the DB
 *          or logs and expire automatically. The {code: in-memory} limitation is deliberate for
 *          the single-instance desktop: restarting an installation simply invalidates ongoing
 *          registrations (the user harmlessly requests a new OTP).
 *
 *          The OTP is stored only as a hash (SHA-256): it cannot be read back. Each entry has a
 *          send rate-limit window and a counter of failed verification attempts.
 *
 *          Thread safety: window counters use {@link AtomicInteger}/{@link AtomicLong}, and
 *          mutation is synchronized by key, so an attacker issuing concurrent requests cannot
 *          bypass the limit through lost updates. Memory is bounded by an opportunistic sweep
 *          when maps exceed a threshold.
 */
@ApplicationScoped
public class OtpStore {

    /** @brief Validity duration of the OTP and rate-limit window. */
    private static final long TTL_MILLIS = 5 * 60 * 1000L; // 5 minutes
    /** @brief Limit on allowed OTP sends per email within a window. */
    private static final int MAX_SENDS_PER_WINDOW = 5;
    /** @brief Limit on allowed OTP sends per IP within a window. */
    private static final int MAX_SENDS_PER_IP = 10;
    /** @brief Limit on registration-completion attempts per IP within a window. */
    private static final int MAX_COMPLETE_ATTEMPTS_PER_IP = 30;
    /** @brief Limit on failed verification attempts before invalidating the request. */
    private static final int MAX_ATTEMPTS = 5;
    /** @brief The OTP is a six-digit number. */
    private static final int OTP_DIGITS = 6;
    /** @brief Above this size, expired entries are swept from the maps. */
    private static final int SWEEP_THRESHOLD = 1024;

    private final ConcurrentHashMap<String, PendingRegistration> byEmail = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SendWindow> sendsPerEmail = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SendWindow> sendsPerIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SendWindow> completesPerIp = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** @brief Send counter within a {@link #TTL_MILLIS} window (thread-safe). */
    private static final class SendWindow {
        final AtomicInteger count = new AtomicInteger();
        final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

        /** @brief Resets an expired window, then increments. Returns the current count. */
        int touchAndIncrement() {
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (now - windowStart.get() >= TTL_MILLIS) {
                    windowStart.set(now);
                    count.set(0);
                }
                return count.incrementAndGet();
            }
        }
    }

    /** @brief An in-progress registration record. */
    public static class PendingRegistration {
        public final String email;
        public final String otpHash;
        public final long expiresAt;
        public AtomicInteger attemptsLeft = new AtomicInteger(MAX_ATTEMPTS);
        /**
         * @brief One-time token issued after successful OTP verification.
         * @details Atomic reference rather than a volatile field: implementing "assign if null"
         *          in two steps allowed two simultaneous verifications of the same code (double
         *          click or client retry), and the second overwrote the first token — the holder
         *          of the valid token then had their registration rejected.
         */
        private final AtomicReference<String> registrationToken = new AtomicReference<>();

        /** @return the issued token, or {@code null} if verification has not occurred yet. */
        public String registrationToken() {
            return registrationToken.get();
        }

        /**
         * @brief Assigns the token only if one does not already exist.
         * @return {@code true} if this call issued it.
         */
        public boolean assignRegistrationToken(String token) {
            return registrationToken.compareAndSet(null, token);
        }

        PendingRegistration(String email, String otpHash, long expiresAt) {
            this.email = email;
            this.otpHash = otpHash;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * @brief Retrieves registration for an email, removing expired entries.
     * @return null if absent or expired.
     */
    public PendingRegistration get(String email) {
        PendingRegistration reg = byEmail.get(email);
        if (reg == null) return null;
        if (reg.expiresAt < System.currentTimeMillis()) {
            byEmail.remove(email);
            return null;
        }
        return reg;
    }

    /**
     * @brief Stores a new OTP request for the email.
     * @return true if a previous entry for the same email was replaced.
     */
    public boolean put(String email, String otpHash) {
        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        boolean replaced = byEmail.put(email, new PendingRegistration(email, otpHash, expiresAt)) != null;
        sweepIfNeeded(byEmail);
        return replaced;
    }

    public void invalidate(String email) {
        byEmail.remove(email);
    }

    /**
     * @brief Finds the email associated with a registration token by scanning live entries.
     * @return null if no entry has that token.
     */
    public String emailForToken(String token) {
        if (token == null) return null;
        for (Map.Entry<String, PendingRegistration> e : byEmail.entrySet()) {
            PendingRegistration reg = e.getValue();
            if (reg.expiresAt < System.currentTimeMillis()) {
                byEmail.remove(e.getKey(), reg);
                continue;
            }
            if (token.equals(reg.registrationToken())) return reg.email;
        }
        return null;
    }

    /**
     * @brief Records an OTP send per email and reports whether the window limit was exceeded.
     * @return true if the newly recorded send exceeds the limit (rate limit active).
     */
    public boolean registerEmailSend(String email) {
        return registerSend(sendsPerEmail, email == null ? "unknown" : email, MAX_SENDS_PER_WINDOW);
    }

    /**
     * @brief Records an OTP send per IP and reports whether the window limit was exceeded.
     * @return true if the newly recorded send exceeds the limit (rate limit active).
     */
    public boolean registerIpSend(String ip) {
        return registerSend(sendsPerIp, ip == null ? "unknown" : ip, MAX_SENDS_PER_IP);
    }

    /**
     * @brief Records a registration-completion attempt per IP and reports whether it exceeds the limit.
     * @return true if the attempt exceeds the limit (token brute force).
     */
    public boolean registerCompleteAttempt(String ip) {
        return registerSend(completesPerIp, ip == null ? "unknown" : ip, MAX_COMPLETE_ATTEMPTS_PER_IP);
    }

    /** @brief true if the email has a pending OTP request (to prevent consecutive spam sends). */
    public boolean hasPending(String email) {
        return get(email) != null;
    }

    /**
     * @brief Increments the key's counter, resetting it if the window expired.
     * @return true if the new count exceeds the limit.
     */
    private boolean registerSend(ConcurrentHashMap<String, SendWindow> map, String key, int max) {
        SendWindow window = map.computeIfAbsent(key, k -> new SendWindow());
        boolean limited = window.touchAndIncrement() > max;
        sweepIfNeeded(map);
        return limited;
    }

    /** @brief Removes expired entries if the map has grown beyond the threshold (memory bound). */
    private void sweepIfNeeded(ConcurrentHashMap<String, ?> map) {
        if (map.size() <= SWEEP_THRESHOLD) return;
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue() instanceof SendWindow w
                ? now - w.windowStart.get() >= TTL_MILLIS
                : e.getValue() instanceof PendingRegistration r && r.expiresAt < now);
    }

    /**
     * @brief Generates a numeric OTP of {@value #OTP_DIGITS} digits.
     */
    public String newOtp() {
        int bound = (int) Math.pow(10, OTP_DIGITS);
        return String.format("%0" + OTP_DIGITS + "d", random.nextInt(bound));
    }

    /**
     * @brief Generates a random 128-bit registration token (hex, 32 characters).
     * @details NOT derived from email: real entropy, not guessable by brute force.
     */
    public String newRegistrationToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
