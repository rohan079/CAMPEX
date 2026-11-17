package com.aivoice.lead.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.service.AdminSessionService;
import com.aivoice.lead.service.AdminSessionService.IssuedSession;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AdminAuthControllerTest {

    @Test
    void loginSetsHttpOnlySecureCookieWithoutReturningSessionToken() {
        AdminSessionService sessions = mock(AdminSessionService.class);
        Instant expiry = Instant.now().plusSeconds(3600);
        when(sessions.login("admin@example.test", "password")).thenReturn(
                new IssuedSession("opaque-session-token", "admin@example.test", expiry));
        AppProperties app = new AppProperties("production", "Campex", "", "Asia/Kolkata", 7, 3,
                "", "", "", null, "http://localhost:8000", 30, 1);
        AdminAuthController controller = new AdminAuthController(sessions, app);

        var response = controller.login(new AdminAuthController.LoginRequest("admin@example.test", "password"));
        String cookie = response.getHeaders().getFirst("Set-Cookie");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertFalse(response.getBody().toString().contains("opaque-session-token"));
    }
}
