package com.aivoice.lead.service;

import com.aivoice.lead.config.AdminAuthProperties;
import com.aivoice.lead.config.FirebaseAuthProperties;
import com.aivoice.lead.service.AdminSessionService.AdminIdentity;
import com.aivoice.lead.service.AdminSessionService.AuthException;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Dashboard routes authenticate with a Firebase ID token (Bearer or id_token query). */
@Service
public class DashboardAuthService {

    private final FirebaseAuthProperties firebaseProperties;
    private final AdminAuthProperties adminProperties;
    private final FirebaseTokenVerifier firebaseTokens;

    public DashboardAuthService(
            FirebaseAuthProperties firebaseProperties,
            AdminAuthProperties adminProperties,
            FirebaseTokenVerifier firebaseTokens) {
        this.firebaseProperties = firebaseProperties;
        this.adminProperties = adminProperties;
        this.firebaseTokens = firebaseTokens;
    }

    public boolean isConfigured() {
        return firebaseProperties.isConfigured() && hasAdminEmail();
    }

    public Optional<AdminIdentity> authenticate(HttpServletRequest request) {
        String idToken = bearerToken(request);
        if (idToken == null || idToken.isBlank()) {
            idToken = request.getParameter("id_token");
        }
        if (idToken == null || idToken.isBlank()) {
            return Optional.empty();
        }
        try {
            VerifiedFirebaseUser user = firebaseTokens.verifyIdToken(idToken);
            if (!sameEmail(user.email(), adminProperties.email())) {
                return Optional.empty();
            }
            return Optional.of(new AdminIdentity(user.email(), Instant.now().plusSeconds(3600)));
        } catch (AuthException e) {
            return Optional.empty();
        }
    }

    public String requireOwnerEmail(HttpServletRequest request) {
        return authenticate(request)
                .map(AdminIdentity::email)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "Firebase authentication required"));
    }

    private boolean hasAdminEmail() {
        String email = adminProperties.email();
        return email != null && !email.isBlank();
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) return null;
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    private static boolean sameEmail(String supplied, String expected) {
        if (supplied == null || expected == null) return false;
        byte[] a = supplied.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
