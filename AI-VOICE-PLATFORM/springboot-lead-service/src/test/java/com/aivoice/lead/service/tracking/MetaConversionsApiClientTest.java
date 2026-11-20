package com.aivoice.lead.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.config.TrackingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The Graph endpoint is a MockRestServiceServer; no real Meta call is made. */
class MetaConversionsApiClientTest {

    private final MetaOAuthProperties meta = new MetaOAuthProperties("app", "secret", "x", "v27.0", "k", "f", "", List.of());

    @Test
    void postsToVersionedPixelEdgeWithTokenInBodyNotUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.example.test/v27.0/123456789/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("access_token=EAA-token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("test_event_code=TEST1")))
                .andRespond(withSuccess("{\"events_received\":1,\"fbtrace_id\":\"F1\"}", MediaType.APPLICATION_JSON));
        TrackingProperties props = new TrackingProperties(true, "https://graph.example.test/", 10, 50, 8, 30, 21600,
                120, 7, 300, 120, 3000, 16384, 395, 30);
        MetaConversionsApiClient client = new MetaConversionsApiClient(builder.build(), new ObjectMapper(), props, meta);
        var result = client.send("123456789", "EAA-token", List.of(Map.of("event_name", "Lead")), "TEST1");
        server.verify();
        assertTrue(result.success());
        assertEquals("F1", result.fbtraceId());
    }

    @Test
    void classifiesErrorsAndRedactsTokens() {
        TrackingProperties props = TrackingProperties.defaults();
        MetaConversionsApiClient client = new MetaConversionsApiClient(RestClient.builder(), new ObjectMapper(), props, meta);
        var throttled = client.fromError(400, "{\"error\":{\"code\":17,\"message\":\"rate\"}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(throttled.retryable());
        var invalid = client.fromError(400, "{\"error\":{\"code\":100,\"message\":\"Invalid parameter EAAABBBBCCCCDDDDEEEEFFFFGGGG\"}}".getBytes(StandardCharsets.UTF_8));
        assertFalse(invalid.retryable());
        assertFalse(invalid.errorMessage().contains("EAAABBBB"));
        assertTrue(client.fromError(503, new byte[0]).retryable());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), 429);
        assertTrue(client.fromError(429, new byte[0]).retryable());
    }
}
