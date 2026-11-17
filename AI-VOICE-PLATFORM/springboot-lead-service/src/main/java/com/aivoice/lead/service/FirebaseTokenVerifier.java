package com.aivoice.lead.service;

import com.aivoice.lead.config.FirebaseAuthProperties;
import com.aivoice.lead.service.AdminSessionService.AuthException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class FirebaseTokenVerifier {

    private final FirebaseAuthProperties properties;
    private final RestClient client;

    public FirebaseTokenVerifier(FirebaseAuthProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl("https://identitytoolkit.googleapis.com").build();
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
            JsonNode body = client.post()
                    .uri("/v1/accounts:lookup?key={key}", properties.apiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("idToken", idToken.trim()))
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !body.has("users") || !body.get("users").isArray() || body.get("users").isEmpty()) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid Firebase ID token");
            }

            JsonNode user = body.get("users").get(0);
            String email = text(user, "email");
            String uid = text(user, "localId");
            boolean emailVerified = user.path("emailVerified").asBoolean(false);
            if (email == null || email.isBlank() || uid == null || uid.isBlank()) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Firebase account is missing an email address");
            }
            if (!emailVerified) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Verify the email on this Google or Facebook account first");
            }
            return new VerifiedFirebaseUser(email.trim().toLowerCase(Locale.ROOT), uid, true);
        } catch (AuthException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid or expired Firebase ID token");
        } catch (RestClientException e) {
            throw new AuthException(HttpStatus.BAD_GATEWAY, "Could not verify Firebase ID token");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
