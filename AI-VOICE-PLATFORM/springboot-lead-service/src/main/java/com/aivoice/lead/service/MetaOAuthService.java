package com.aivoice.lead.service;

import com.aivoice.lead.config.MetaOAuthProperties;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MetaOAuthService {

    private final MetaOAuthProperties properties;
    private final MetaGraphClient graph;
    private final MetaConnectionStore store;
    private final MetaAppCredentialsResolver credentialsResolver;

    public MetaOAuthService(
            MetaOAuthProperties properties,
            MetaGraphClient graph,
            MetaConnectionStore store,
            MetaAppCredentialsResolver credentialsResolver) {
        this.properties = properties;
        this.graph = graph;
        this.store = store;
        this.credentialsResolver = credentialsResolver;
    }

    public static class MissingPermissionsException extends RuntimeException {
        public MissingPermissionsException(List<String> missing) {
            super("Meta permissions were not granted: " + String.join(", ", missing));
        }
    }

    public boolean isConfigured() {
        if (!properties.credentialsConfigured() || !store.encryptionConfigured()) return false;
        try {
            URI redirect = URI.create(properties.oauthRedirectUri());
            URI frontend = URI.create(properties.frontendRedirectUrl());
            return "https".equalsIgnoreCase(redirect.getScheme()) && redirect.getHost() != null
                    && redirect.getPath() != null && redirect.getPath().equals("/oauth/meta/callback")
                    && "https".equalsIgnoreCase(frontend.getScheme()) && frontend.getHost() != null
                    && properties.graphApiVersion().matches("v\\d+\\.\\d+");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public URI authorizationUri(String state, UUID clientId) {
        requireConfigured();
        MetaAppCredentials credentials = credentialsResolver.resolve(clientId);
        // Channels connector uses Meta Business Login. When a login config id is set,
        // permissions come from that configuration (required for Login for Business).
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://www.facebook.com/" + credentials.graphApiVersion() + "/dialog/oauth")
                .queryParam("client_id", credentials.appId())
                .queryParam("redirect_uri", credentials.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state);
        if (credentials.hasLoginConfigId()) {
            builder.queryParam("config_id", credentials.loginConfigId());
        } else {
            builder.queryParam("scope", String.join(",", properties.oauthScopes()));
        }
        return builder.build().encode().toUri();
    }

    public void complete(String ownerEmail, String code, UUID clientId) {
        requireConfigured();
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Meta authorization code is missing");

        MetaAppCredentials credentials = credentialsResolver.resolve(clientId);
        MetaGraphClient.Token shortLived = graph.exchangeCode(
                code, credentials.appId(), credentials.appSecret(), credentials.redirectUri());
        MetaGraphClient.Token longLived = graph.exchangeForLongLivedToken(
                shortLived.value(), credentials.appId(), credentials.appSecret());
        List<MetaGraphClient.Permission> permissions = graph.permissions(longLived.value());
        Set<String> granted = permissions.stream()
                .filter(permission -> "granted".equalsIgnoreCase(permission.status()))
                .map(permission -> permission.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (credentials.hasLoginConfigId()) {
            // Business Login configs define permissions; require at least one ads/pages scope.
            boolean hasBusinessAccess = granted.stream().anyMatch(scope ->
                    scope.startsWith("ads_")
                            || scope.startsWith("pages_")
                            || scope.equals("business_management")
                            || scope.startsWith("instagram_"));
            if (!hasBusinessAccess) {
                throw new MissingPermissionsException(List.of("ads_management|pages_show_list|business_management"));
            }
        } else {
            List<String> missing = properties.oauthScopes().stream()
                    .filter(scope -> !granted.contains(scope.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!missing.isEmpty()) throw new MissingPermissionsException(missing);
        }

        MetaGraphClient.Profile profile = graph.profile(longLived.value());
        if (profile.id().isBlank()) throw new MetaGraphClient.MetaGraphException(502, -1);
        List<MetaGraphClient.Page> pages = graph.pages(longLived.value());
        List<MetaGraphClient.AdAccount> adAccounts = graph.adAccounts(longLived.value());
        List<MetaGraphClient.InstagramAccount> instagramAccounts = graph.instagramAccounts(longLived.value());

        store.save(
                ownerEmail,
                credentials.clientId(),
                credentials.appId(),
                profile,
                longLived,
                granted.stream().sorted().toList(),
                pages,
                adAccounts,
                instagramAccounts);
    }

    public MetaConnectionStore.ConnectionStatus status(String ownerEmail) {
        return store.status(ownerEmail);
    }

    public void disconnect(String ownerEmail) {
        String token = store.userAccessToken(ownerEmail);
        if (token == null) return;
        try {
            graph.revokeUserToken(token);
        } catch (MetaGraphClient.MetaGraphException e) {
            if (e.graphCode() != 190) throw e;
        }
        store.disconnect(ownerEmail);
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new IllegalStateException("Meta OAuth is not fully configured on the server");
    }

}
