package com.aivoice.lead.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.service.AdminSessionService;
import com.aivoice.lead.service.AdminSessionService.IssuedSession;
import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdminAuthControllerTest {

    private final AdminSessionService sessions = mock(AdminSessionService.class);
    private final FirebaseTokenVerifier firebaseTokens = mock(FirebaseTokenVerifier.class);
    private final AppProperties app = new AppProperties("production", "Campex", "", "Asia/Kolkata", 7, 3,
            "", "", "", null, "http://localhost:8000", 30, 1);
    private final AdminAuthController controller = new AdminAuthController(sessions, firebaseTokens, app);

    @Test
    void loginSetsHttpOnlySecureCookieWithoutReturningSessionToken() {
        Instant expiry = Instant.now().plusSeconds(3600);
        when(sessions.login("admin@example.test", "password")).thenReturn(
                new IssuedSession("opaque-session-token", "admin@example.test", expiry));

        var response = controller.login(new AdminAuthController.LoginRequest("admin@example.test", "password"));
        String cookie = response.getHeaders().getFirst("Set-Cookie");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertFalse(response.getBody().toString().contains("opaque-session-token"));
    }

    @Test
    void firebaseLoginExchangesVerifiedEmailForAdminSessionCookie() {
        Instant expiry = Instant.now().plusSeconds(3600);
        when(firebaseTokens.verifyIdToken("firebase-id-token"))
                .thenReturn(new VerifiedFirebaseUser("admin@example.test", "uid-1", true));
        when(sessions.loginWithVerifiedEmail("admin@example.test"))
                .thenReturn(new IssuedSession("opaque-session-token", "admin@example.test", expiry));

        var response = controller.firebaseLogin(new AdminAuthController.FirebaseLoginRequest("firebase-id-token"));
        String cookie = response.getHeaders().getFirst("Set-Cookie");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(cookie.contains("HttpOnly"));
        assertEquals("admin@example.test", response.getBody().get("email"));
        assertFalse(response.getBody().toString().contains("opaque-session-token"));
    }
}
