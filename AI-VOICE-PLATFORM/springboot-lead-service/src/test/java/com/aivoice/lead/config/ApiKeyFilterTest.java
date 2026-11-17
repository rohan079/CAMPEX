package com.aivoice.lead.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aivoice.lead.service.AdminSessionService;
import com.aivoice.lead.service.DashboardAuthService;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private ApiKeyFilter filter(String environment, String popup, String service,
                                boolean authConfigured, boolean authenticated) {
        AppProperties properties = new AppProperties(environment, "Acme", "", "Asia/Kolkata", 7, 3,
                popup, service, "", null, "http://localhost:8000", 30, 1);
        DashboardAuthService dashboardAuth = mock(DashboardAuthService.class);
        when(dashboardAuth.isConfigured()).thenReturn(authConfigured);
        when(dashboardAuth.authenticate(any())).thenReturn(authenticated
                ? Optional.of(new AdminSessionService.AdminIdentity("admin@example.test", Instant.MAX))
                : Optional.empty());
        return new ApiKeyFilter(properties, dashboardAuth);
    }

    private int status(ApiKeyFilter filter, String method, String path, String header, String value) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (header != null) {
            request.addHeader(header, value);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    @Test
    void dashboardNeedsFirebaseAuthAndOtherCallersNeedTheirOwnKeys() throws Exception {
        ApiKeyFilter filter = filter("production", "popup", "svc", true, true);
        ApiKeyFilter unauthenticated = filter("production", "popup", "svc", true, false);
        assertEquals(401, status(unauthenticated, "GET", "/api/leads", null, null));
        assertEquals(401, status(unauthenticated, "GET", "/api/leads", "X-Dashboard-Key", "old-shared-key"));
        assertEquals(200, status(filter, "GET", "/api/leads", null, null));
        assertEquals(401, status(filter, "POST", "/api/ad-leads", "X-Dashboard-Key", "dash"));
        assertEquals(200, status(filter, "POST", "/api/ad-leads", "X-Ad-Popup-Token", "popup"));
        assertEquals(401, status(filter, "POST", "/internal/call-events", "X-Dashboard-Key", "dash"));
        assertEquals(200, status(filter, "POST", "/internal/call-events", "X-Service-Key", "svc"));
    }

    @Test
    void webhookAndHealthAreNotKeyed() throws Exception {
        ApiKeyFilter filter = filter("production", "popup", "svc", true, false);
        assertEquals(200, status(filter, "POST", "/webhooks/whatsapp", null, null));
        assertEquals(200, status(filter, "GET", "/health", null, null));
    }

    @Test
    void preflightPassesThrough() throws Exception {
        assertEquals(200, status(filter("production", "popup", "svc", true, false), "OPTIONS", "/api/leads", null, null));
    }

    @Test
    void missingKeysAreOpenInDevelopmentAndRefusedInProduction() throws Exception {
        assertEquals(200, status(filter("development", "", "", false, false), "GET", "/api/leads", null, null));
        assertEquals(503, status(filter("production", "", "", false, false), "GET", "/api/leads", null, null));
        assertEquals(503, status(filter("production", "", "", false, false), "POST", "/internal/call-events", null, null));
    }
}
