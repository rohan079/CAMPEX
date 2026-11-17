package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MetaGraphClientTest {

    @Test
    void exchangesCodeServerToServerAgainstConfiguredGraphVersion() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://graph.facebook.com/v27.0/oauth/access_token?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("client_id", "test-app-id"))
                .andExpect(queryParam("redirect_uri", "https://campex.example/oauth/meta/callback"))
                .andExpect(queryParam("client_secret", "test-app-secret"))
                .andExpect(queryParam("code", "one-time-code"))
                .andRespond(withSuccess("""
                        {"access_token":"test-user-token","token_type":"bearer","expires_in":3600}
                        """, MediaType.APPLICATION_JSON));

        MetaGraphClient client = new MetaGraphClient(properties(), builder, new ObjectMapper());
        MetaGraphClient.Token token = client.exchangeCode("one-time-code");

        assertEquals("test-user-token", token.value());
        assertEquals("bearer", token.tokenType());
        assertTrue(token.expiresAt().isAfter(Instant.now()));
        server.verify();
    }

    @Test
    void revokesMetaLoginGrantServerSide() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://graph.facebook.com/v27.0/me/permissions?")))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(queryParam("access_token", "test-user-token"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        new MetaGraphClient(properties(), builder, new ObjectMapper()).revokeUserToken("test-user-token");
        server.verify();
    }

    @Test
    void preservesMetaInvalidTokenCodeForExpiredConnectionHandling() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://graph.facebook.com/v27.0/me/permissions?")))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withBadRequest().body("{\"error\":{\"code\":190}}")
                        .contentType(MediaType.APPLICATION_JSON));

        MetaGraphClient.MetaGraphException error = org.junit.jupiter.api.Assertions.assertThrows(
                MetaGraphClient.MetaGraphException.class,
                () -> new MetaGraphClient(properties(), builder, new ObjectMapper()).revokeUserToken("expired-token"));
        assertEquals(190, error.graphCode());
        server.verify();
    }

    @Test
    void disconnectRevokesLoginPermissionsServerSide() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://graph.facebook.com/v27.0/me/permissions?")))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(queryParam("access_token", "test-user-token"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        MetaGraphClient client = new MetaGraphClient(properties(), builder, new ObjectMapper());
        client.revokeUserToken("test-user-token");
        server.verify();
    }

    private static MetaOAuthProperties properties() {
        return new MetaOAuthProperties("test-app-id", "test-app-secret",
                "https://campex.example/oauth/meta/callback", "v27.0",
                Base64.getEncoder().encodeToString(new byte[32]), "https://campex.example", List.of("ads_read"));
    }
}
