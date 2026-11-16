package com.aivoice.lead.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private ApiKeyFilter filter(String environment, String dashboard, String popup, String service) {
        return new ApiKeyFilter(new AppProperties(environment, "Acme", "", "Asia/Kolkata", 7, 3,
                dashboard, popup, service, "", null, "http://localhost:8000", 30, 1));
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
    void eachCallerNeedsItsOwnKey() throws Exception {
        ApiKeyFilter filter = filter("production", "dash", "popup", "svc");
        assertEquals(401, status(filter, "GET", "/api/leads", null, null));
        assertEquals(200, status(filter, "GET", "/api/leads", "X-Dashboard-Key", "dash"));
        assertEquals(401, status(filter, "POST", "/api/ad-leads", "X-Dashboard-Key", "dash"));
        assertEquals(200, status(filter, "POST", "/api/ad-leads", "X-Ad-Popup-Token", "popup"));
        assertEquals(401, status(filter, "POST", "/internal/call-events", "X-Dashboard-Key", "dash"));
        assertEquals(200, status(filter, "POST", "/internal/call-events", "X-Service-Key", "svc"));
    }

    @Test
    void webhookAndHealthAreNotKeyed() throws Exception {
        ApiKeyFilter filter = filter("production", "dash", "popup", "svc");
        assertEquals(200, status(filter, "POST", "/webhooks/whatsapp", null, null));
        assertEquals(200, status(filter, "GET", "/health", null, null));
    }

    @Test
    void preflightPassesThrough() throws Exception {
        assertEquals(200, status(filter("production", "dash", "popup", "svc"), "OPTIONS", "/api/leads", null, null));
    }

    @Test
    void missingKeysAreOpenInDevelopmentAndRefusedInProduction() throws Exception {
        assertEquals(200, status(filter("development", "", "", ""), "GET", "/api/leads", null, null));
        assertEquals(503, status(filter("production", "", "", ""), "GET", "/api/leads", null, null));
        assertEquals(503, status(filter("production", "", "", ""), "POST", "/internal/call-events", null, null));
    }
}
