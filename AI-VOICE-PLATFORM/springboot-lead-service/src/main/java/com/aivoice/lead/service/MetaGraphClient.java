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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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

    public record InstagramAccount(String id, String username, String name, String pageId) {
    }

    public static class MetaGraphException extends RuntimeException {
        private final int httpStatus;
        private final int graphCode;
        private final String failureType;
        private final String providerMessage;

        public MetaGraphException(int httpStatus, int graphCode) {
            this(httpStatus, graphCode, "unknown", null);
        }

        public MetaGraphException(int httpStatus, int graphCode, String failureType) {
            this(httpStatus, graphCode, failureType, null);
        }

        public MetaGraphException(int httpStatus, int graphCode, String failureType, String providerMessage) {
            super(providerMessage != null && !providerMessage.isBlank()
                    ? providerMessage
                    : "Meta Graph API request failed (HTTP " + httpStatus + ", code " + graphCode + ")");
            this.httpStatus = httpStatus;
            this.graphCode = graphCode;
            this.failureType = failureType;
            this.providerMessage = providerMessage;
        }

        public int httpStatus() { return httpStatus; }
        public int graphCode() { return graphCode; }
        public String failureType() { return failureType; }
        public String providerMessage() { return providerMessage; }
    }

    public record GeoLocation(String key, String name, String type, String countryCode) {
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
        return list("me/accounts", "id,name,category,access_token,instagram_business_account{id,username,name}", accessToken).stream()
                .map(node -> new Page(
                        node.path("id").asText(""),
                        node.path("name").asText("Facebook Page"),
                        node.path("category").asText(null),
                        node.path("access_token").asText(null)))
                .filter(page -> !page.id().isBlank())
                .toList();
    }

    /** Instagram professional accounts linked to the granted Facebook Pages. */
    public List<InstagramAccount> instagramAccounts(String accessToken) {
        List<InstagramAccount> accounts = new ArrayList<>();
        for (JsonNode node : list("me/accounts", "id,instagram_business_account{id,username,name}", accessToken)) {
            String pageId = node.path("id").asText("");
            JsonNode ig = node.path("instagram_business_account");
            String id = ig.path("id").asText("");
            if (pageId.isBlank() || id.isBlank()) continue;
            String username = ig.path("username").asText("");
            String name = ig.path("name").asText(username.isBlank() ? "Instagram account" : username);
            accounts.add(new InstagramAccount(id, username.isBlank() ? null : username, name, pageId));
        }
        return accounts;
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

    public List<GeoLocation> searchGeoLocations(String query, String accessToken) {
        if (query == null || query.isBlank()) return List.of();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "adgeolocation");
        params.put("location_types", "[\"city\",\"region\",\"country\"]");
        params.put("q", query.trim());
        params.put("limit", "5");
        params.put("access_token", accessToken);
        JsonNode body = request("search", params);
        List<GeoLocation> matches = new ArrayList<>();
        JsonNode data = body.path("data");
        if (!data.isArray()) return matches;
        data.forEach(node -> {
            String key = node.path("key").asText("");
            if (key.isBlank()) return;
            matches.add(new GeoLocation(
                    key,
                    node.path("name").asText(key),
                    node.path("type").asText("city"),
                    node.path("country_code").asText(null)));
        });
        return matches;
    }

    public String createCampaign(String adAccountId, String name, String objective, String accessToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("name", name);
        form.put("objective", objective);
        form.put("status", "PAUSED");
        form.put("special_ad_categories", "[]");
        form.put("access_token", accessToken);
        return requireId(postForm(normalizeAdAccountId(adAccountId) + "/campaigns", form));
    }

    public String createAdSet(
            String adAccountId,
            String campaignId,
            String name,
            int dailyBudgetMinor,
            int ageMin,
            int ageMax,
            String targetingJson,
            String optimizationGoal,
            String accessToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("name", name);
        form.put("campaign_id", campaignId);
        form.put("daily_budget", Integer.toString(Math.max(dailyBudgetMinor, 100)));
        form.put("billing_event", "IMPRESSIONS");
        form.put("optimization_goal", optimizationGoal);
        form.put("bid_strategy", "LOWEST_COST_WITHOUT_CAP");
        form.put("targeting", targetingJson);
        form.put("status", "PAUSED");
        form.put("access_token", accessToken);
        return requireId(postForm(normalizeAdAccountId(adAccountId) + "/adsets", form));
    }

    public String createLinkAd(
            String adAccountId,
            String adSetId,
            String pageId,
            String name,
            String headline,
            String body,
            String ctaType,
            String linkUrl,
            String accessToken) {
        String story = """
                {"page_id":"%s","link_data":{"message":"%s","name":"%s","link":"%s","call_to_action":{"type":"%s","value":{"link":"%s"}}}}
                """.formatted(
                escapeJson(pageId),
                escapeJson(body),
                escapeJson(headline),
                escapeJson(linkUrl),
                escapeJson(ctaType),
                escapeJson(linkUrl)).trim();
        Map<String, String> creativeForm = new LinkedHashMap<>();
        creativeForm.put("name", name + " creative");
        creativeForm.put("object_story_spec", story);
        creativeForm.put("access_token", accessToken);
        String creativeId = requireId(postForm(normalizeAdAccountId(adAccountId) + "/adcreatives", creativeForm));

        Map<String, String> adForm = new LinkedHashMap<>();
        adForm.put("name", name);
        adForm.put("adset_id", adSetId);
        adForm.put("creative", "{\"creative_id\":\"" + escapeJson(creativeId) + "\"}");
        adForm.put("status", "PAUSED");
        adForm.put("access_token", accessToken);
        return requireId(postForm(normalizeAdAccountId(adAccountId) + "/ads", adForm));
    }

    private static String normalizeAdAccountId(String adAccountId) {
        String id = adAccountId == null ? "" : adAccountId.trim();
        if (id.isBlank()) throw new IllegalArgumentException("Ad account id is required");
        return id.startsWith("act_") ? id : "act_" + id;
    }

    private static String requireId(JsonNode body) {
        String id = body.path("id").asText("");
        if (id.isBlank()) throw new MetaGraphException(502, -1, "missing_created_id");
        return id;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private JsonNode postForm(String edge, Map<String, String> form) {
        URI uri = UriComponentsBuilder
                .fromUriString("https://graph.facebook.com/" + properties.graphApiVersion() + "/" + edge)
                .build()
                .encode()
                .toUri();
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        try {
            String responseBody = client.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(String.class);
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
            String providerMessage = null;
            try {
                JsonNode error = mapper.readTree(e.getResponseBodyAsByteArray()).path("error");
                if (error.path("code").canConvertToInt()) graphCode = error.path("code").asInt();
                providerMessage = error.path("error_user_msg").asText(null);
                if (providerMessage == null || providerMessage.isBlank()) {
                    providerMessage = error.path("message").asText(null);
                }
            } catch (IOException ignored) {
                graphCode = -1;
            }
            throw new MetaGraphException(e.getStatusCode().value(), graphCode, "http_response", providerMessage);
        } catch (RestClientException e) {
            throw new MetaGraphException(502, -1, "transport_failure:" + e.getClass().getSimpleName());
        }
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
