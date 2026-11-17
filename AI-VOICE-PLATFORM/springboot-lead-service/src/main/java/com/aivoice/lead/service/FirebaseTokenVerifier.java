package com.aivoice.lead.service;

import com.aivoice.lead.config.FirebaseAuthProperties;
import com.aivoice.lead.service.AdminSessionService.AuthException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URI;
import java.text.ParseException;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Verifies Firebase ID tokens with Google's JWKS. Does not use the browser API key, so
 * HTTP-referrer key restrictions cannot break server-side auth.
 */
@Service
public class FirebaseTokenVerifier {

    private static final String JWKS_URL =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    private static final long JWKS_TTL_SECONDS = 3_600;

    private final FirebaseAuthProperties properties;
    private final RestClient client;

    private volatile Instant jwksFetchedAt = Instant.EPOCH;
    private volatile JWKSource<SecurityContext> jwkSource;

    public FirebaseTokenVerifier(FirebaseAuthProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.build();
    }

    public record VerifiedFirebaseUser(String email, String uid, boolean emailVerified) {
    }

    public VerifiedFirebaseUser verifyIdToken(String idToken) {
        if (!properties.isConfigured()) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase login is not configured");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase ID token is required");
        }

        try {
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            JWSKeySelector<SecurityContext> keySelector =
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource());
            processor.setJWSKeySelector(keySelector);

            JWTClaimsSet claims = processor.process(idToken.trim(), null);
            String projectId = properties.projectId().trim();
            String expectedIssuer = "https://securetoken.google.com/" + projectId;
            if (!projectId.equals(claims.getAudience().stream().findFirst().orElse(null))) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase token audience mismatch");
            }
            if (!expectedIssuer.equals(claims.getIssuer())) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase token issuer mismatch");
            }
            if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase token expired");
            }

            String uid = claims.getSubject();
            String email = claims.getStringClaim("email");
            Boolean emailVerified = claims.getBooleanClaim("email_verified");
            if (uid == null || uid.isBlank()) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase token is missing subject");
            }
            if (email == null || email.isBlank()) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase account is missing an email address");
            }
            if (!Boolean.TRUE.equals(emailVerified)) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Verify the email on this Google or Facebook account first");
            }
            return new VerifiedFirebaseUser(email.trim().toLowerCase(Locale.ROOT), uid, true);
        } catch (AuthException e) {
            throw e;
        } catch (ParseException | com.nimbusds.jose.JOSEException | com.nimbusds.jose.proc.BadJOSEException e) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired Firebase ID token");
        } catch (RuntimeException e) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired Firebase ID token");
        }
    }

    private JWKSource<SecurityContext> jwkSource() {
        Instant now = Instant.now();
        JWKSource<SecurityContext> cached = jwkSource;
        if (cached != null && jwksFetchedAt.plusSeconds(JWKS_TTL_SECONDS).isAfter(now)) {
            return cached;
        }
        synchronized (this) {
            if (jwkSource != null && jwksFetchedAt.plusSeconds(JWKS_TTL_SECONDS).isAfter(Instant.now())) {
                return jwkSource;
            }
            try {
                String body = client.get().uri(URI.create(JWKS_URL)).retrieve().body(String.class);
                if (body == null || body.isBlank()) {
                    throw new AuthException(HttpStatus.BAD_GATEWAY, "Could not load Firebase signing keys");
                }
                JWKSet set = JWKSet.parse(body);
                // Touch RSA material early so misconfigured responses fail here.
                for (var key : set.getKeys()) {
                    if (key instanceof RSAKey rsaKey) rsaKey.toRSAPublicKey();
                }
                jwkSource = new ImmutableJWKSet<>(set);
                jwksFetchedAt = Instant.now();
                return jwkSource;
            } catch (AuthException e) {
                throw e;
            } catch (ParseException | com.nimbusds.jose.JOSEException | RestClientException e) {
                throw new AuthException(HttpStatus.BAD_GATEWAY, "Could not load Firebase signing keys");
            }
        }
    }
}
