package com.aivoice.lead.service;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MetaGraphClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private final MetaOAuthProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper;

    public MetaGraphClient(MetaOAuthProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        this.properties = properties;
        this.client = builder.build();
        this.mapper = mapper;
    }

    public record Token(String value, String tokenType, Instant expiresAt, Instant dataAccessExpiresAt) {
    }

    public record Profile(String id, String name) {
    }

    public record Permission(String name, String status) {
    }

    public record Page(String id, String name, String category, String accessToken) {
    }

    public record AdAccount(String id, String name, String accountId, Integer status, String currency) {
    }

    public static class MetaGraphException extends RuntimeException {
        private final int httpStatus;
        private final int graphCode;
        private final String failureType;

        public MetaGraphException(int httpStatus, int graphCode) {
            this(httpStatus, graphCode, "unknown");
        }

        public MetaGraphException(int httpStatus, int graphCode, String failureType) {
            super("Meta Graph API request failed (HTTP " + httpStatus + ", code " + graphCode + ")");
            this.httpStatus = httpStatus;
            this.graphCode = graphCode;
            this.failureType = failureType;
        }

        public int httpStatus() { return httpStatus; }
        public int graphCode() { return graphCode; }
        public String failureType() { return failureType; }
    }

    public Token exchangeCode(String code) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", properties.appId());
        query.put("redirect_uri", properties.oauthRedirectUri());
        query.put("client_secret", properties.appSecret());
        query.put("code", code);
        return token(request("oauth/access_token", query));
    }

    public Token exchangeForLongLivedToken(String shortLivedToken) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("grant_type", "fb_exchange_token");
        query.put("client_id", properties.appId());
        query.put("client_secret", properties.appSecret());
        query.put("fb_exchange_token", shortLivedToken);
        return token(request("oauth/access_token", query));
    }

    public Profile profile(String accessToken) {
        JsonNode body = request("me", Map.of("fields", "id,name", "access_token", accessToken));
        return new Profile(body.path("id").asText(""), body.path("name").asText("Facebook user"));
    }

    public List<Permission> permissions(String accessToken) {
        return list("me/permissions", "permission,status", accessToken).stream()
                .map(node -> new Permission(node.path("permission").asText(""), node.path("status").asText("")))
                .toList();
    }

    public List<Page> pages(String accessToken) {
        return list("me/accounts", "id,name,category,access_token", accessToken).stream()
                .map(node -> new Page(
                        node.path("id").asText(""),
                        node.path("name").asText("Facebook Page"),
                        node.path("category").asText(null),
                        node.path("access_token").asText(null)))
                .filter(page -> !page.id().isBlank())
                .toList();
    }

    public List<AdAccount> adAccounts(String accessToken) {
        return list("me/adaccounts", "id,name,account_id,account_status,currency", accessToken).stream()
                .map(node -> new AdAccount(
                        node.path("id").asText(""),
                        node.path("name").asText("Ad account"),
                        node.path("account_id").asText(null),
                        node.path("account_status").isNumber() ? node.path("account_status").asInt() : null,
                        node.path("currency").asText(null)))
                .filter(account -> !account.id().isBlank())
                .toList();
    }

    public void revokeUserToken(String accessToken) {
        JsonNode response = request(HttpMethod.DELETE, "me/permissions", Map.of("access_token", accessToken));
        if (!response.path("success").asBoolean(false)) throw new MetaGraphException(502, -1);
    }

    private List<JsonNode> list(String edge, String fields, String accessToken) {
        List<JsonNode> result = new ArrayList<>();
        String after = null;
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("fields", fields);
            query.put("limit", Integer.toString(PAGE_SIZE));
            query.put("access_token", accessToken);
            if (after != null) query.put("after", after);
            JsonNode body = request(edge, query);
            JsonNode data = body.path("data");
            if (data.isArray()) data.forEach(result::add);
            JsonNode paging = body.path("paging");
            String next = paging.path("cursors").path("after").asText(null);
            if (!paging.hasNonNull("next") || next == null || next.isBlank()) return result;
            if (next.equals(after)) throw new IllegalStateException("Meta Graph API pagination cursor did not advance");
            after = next;
        }
        throw new MetaGraphException(502, -1);
    }

    private JsonNode request(String edge, Map<String, String> parameters) {
        return request(HttpMethod.GET, edge, parameters);
    }

    private JsonNode request(HttpMethod method, String edge, Map<String, String> parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://graph.facebook.com/" + properties.graphApiVersion() + "/" + edge);
        parameters.forEach(builder::queryParam);
        URI uri = builder.build().encode().toUri();
        try {
            String responseBody = client.method(method).uri(uri).retrieve().body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw new MetaGraphException(502, -1, "empty_response");
            }
            try {
                return mapper.readTree(responseBody);
            } catch (IOException e) {
                throw new MetaGraphException(502, -1, "invalid_json_response");
            }
        } catch (RestClientResponseException e) {
            int graphCode = -1;
            try {
                JsonNode error = mapper.readTree(e.getResponseBodyAsByteArray()).path("error");
                if (error.path("code").canConvertToInt()) graphCode = error.path("code").asInt();
            } catch (IOException ignored) {
                graphCode = -1;
            }
            throw new MetaGraphException(e.getStatusCode().value(), graphCode, "http_response");
        } catch (RestClientException e) {
            throw new MetaGraphException(502, -1, "transport_failure:" + e.getClass().getSimpleName());
        }
    }

    private static Token token(JsonNode body) {
        String value = body.path("access_token").asText("");
        if (value.isBlank()) throw new MetaGraphException(502, -1, "missing_access_token");
        long lifetime = body.path("expires_in").asLong(0);
        long dataLifetime = body.path("data_access_expiration_time").asLong(0);
        Instant now = Instant.now();
        return new Token(
                value,
                body.path("token_type").asText("bearer"),
                lifetime > 0 ? now.plusSeconds(lifetime) : null,
                dataLifetime > 0 ? Instant.ofEpochSecond(dataLifetime) : null);
    }
}
