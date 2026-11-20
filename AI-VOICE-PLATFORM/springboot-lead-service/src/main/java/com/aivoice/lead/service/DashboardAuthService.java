package com.aivoice.lead.service;

import com.aivoice.lead.config.FirebaseAuthProperties;
import com.aivoice.lead.service.AdminSessionService.AdminIdentity;
import com.aivoice.lead.service.AdminSessionService.AuthException;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Dashboard routes authenticate with a Firebase ID token (Bearer or id_token query). */
@Service
public class DashboardAuthService {

    /** Request attribute holding the {@link AdminIdentity} verified by {@code ApiKeyFilter}. */
    public static final String IDENTITY_ATTRIBUTE = "campex.identity";

    private final FirebaseAuthProperties firebaseProperties;
    private final FirebaseTokenVerifier firebaseTokens;

    public DashboardAuthService(FirebaseAuthProperties firebaseProperties, FirebaseTokenVerifier firebaseTokens) {
        this.firebaseProperties = firebaseProperties;
        this.firebaseTokens = firebaseTokens;
    }

    public boolean isConfigured() {
        return firebaseProperties.isConfigured();
    }

    public Optional<AdminIdentity> authenticate(HttpServletRequest request) {
        try {
            return Optional.of(requireAuthenticated(request));
        } catch (AuthException e) {
            return Optional.empty();
        }
    }

    public AdminIdentity requireAuthenticated(HttpServletRequest request) {
        if (!isConfigured()) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication is not configured");
        }
        String idToken = bearerToken(request);
        if (idToken == null || idToken.isBlank()) {
            idToken = request.getParameter("id_token");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase authentication required");
        }

        VerifiedFirebaseUser user = firebaseTokens.verifyIdToken(idToken);
        return new AdminIdentity(user.email(), Instant.now().plusSeconds(3600));
    }

    /** The identity already verified for this request by the filter, or a fresh verification. */
    public AdminIdentity currentIdentity(HttpServletRequest request) {
        Object cached = request.getAttribute(IDENTITY_ATTRIBUTE);
        if (cached instanceof AdminIdentity identity) {
            return identity;
        }
        AdminIdentity identity = requireAuthenticated(request);
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return identity;
    }

    public String requireOwnerEmail(HttpServletRequest request) {
        return requireAuthenticated(request).email();
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) return null;
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }
}
