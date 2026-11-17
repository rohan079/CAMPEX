package com.aivoice.lead.service;

import com.aivoice.lead.config.AdminAuthProperties;
import com.aivoice.lead.entity.CampexAdminSession;
import com.aivoice.lead.repository.CampexAdminSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSessionService {

    public static final String COOKIE_NAME = "CAMPEX_ADMIN_SESSION";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminAuthProperties properties;
    private final CampexAdminSessionRepository sessions;

    public AdminSessionService(AdminAuthProperties properties, CampexAdminSessionRepository sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    public record IssuedSession(String token, String ownerEmail, Instant expiresAt) {
    }

    public record AdminIdentity(String email, Instant expiresAt) {
    }

    public static class AuthException extends RuntimeException {
        private final HttpStatus status;

        public AuthException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Transactional
    public IssuedSession login(String email, String password) {
        if (!isConfigured()) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "CAMPEX admin login is not configured");
        }
        boolean emailMatches = email != null && sameEmail(email, properties.email());
        boolean passwordMatches = verifyPassword(password == null ? "" : password, properties.passwordHash());
        if (!emailMatches || !passwordMatches) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        return issueSession();
    }

    @Transactional
    public IssuedSession loginWithVerifiedEmail(String email) {
        if (!isConfigured()) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "CAMPEX admin login is not configured");
        }
        if (email == null || !sameEmail(email, properties.email())) {
            throw new AuthException(HttpStatus.FORBIDDEN, "This Google or Facebook account is not authorized for CAMPEX admin");
        }
        return issueSession();
    }

    private IssuedSession issueSession() {
        Instant now = Instant.now();
        sessions.deleteByExpiresAtBefore(now);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiresAt = now.plusSeconds(properties.sessionTtlSeconds());
        CampexAdminSession session = new CampexAdminSession();
        session.setTokenHash(sha256(token));
        session.setOwnerEmail(properties.email().trim().toLowerCase(Locale.ROOT));
        session.setExpiresAt(expiresAt);
        sessions.save(session);
        return new IssuedSession(token, session.getOwnerEmail(), expiresAt);
    }

    @Transactional(readOnly = true)
    public Optional<AdminIdentity> authenticate(HttpServletRequest request) {
        String token = cookieToken(request);
        if (token == null || token.length() != 43) return Optional.empty();
        return sessions.findByTokenHashAndExpiresAtAfter(sha256(token), Instant.now())
                .map(session -> new AdminIdentity(session.getOwnerEmail(), session.getExpiresAt()));
    }

    public String requireOwnerEmail(HttpServletRequest request) {
        return authenticate(request)
                .map(AdminIdentity::email)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "CAMPEX admin login required"));
    }

    @Transactional
    public void logout(HttpServletRequest request) {
        String token = cookieToken(request);
        if (token != null && !token.isBlank()) sessions.deleteByTokenHash(sha256(token));
    }

    private static String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private static boolean sameEmail(String supplied, String expected) {
        byte[] a = supplied.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static boolean verifyPassword(String password, String encoded) {
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (iterations < 100_000 || iterations > 2_000_000 || salt.length < SALT_BYTES
                    || expected.length != HASH_BYTES) return false;
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8);
            try {
                byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                return MessageDigest.isEqual(actual, expected);
            } finally {
                spec.clearPassword();
            }
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
