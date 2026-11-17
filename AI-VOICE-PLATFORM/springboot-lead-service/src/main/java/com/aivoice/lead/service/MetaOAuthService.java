package com.aivoice.lead.service;

import com.aivoice.lead.config.MetaOAuthProperties;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MetaOAuthService {

    private final MetaOAuthProperties properties;
    private final MetaGraphClient graph;
    private final MetaConnectionStore store;

    public MetaOAuthService(MetaOAuthProperties properties, MetaGraphClient graph, MetaConnectionStore store) {
        this.properties = properties;
        this.graph = graph;
        this.store = store;
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

    public URI authorizationUri(String state) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString("https://www.facebook.com/" + properties.graphApiVersion() + "/dialog/oauth")
                .queryParam("client_id", properties.appId())
                .queryParam("redirect_uri", properties.oauthRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .queryParam("scope", String.join(",", properties.oauthScopes()))
                .build()
                .encode()
                .toUri();
    }

    public void complete(String ownerEmail, String code) {
        requireConfigured();
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Meta authorization code is missing");

        MetaGraphClient.Token shortLived = graph.exchangeCode(code);
        MetaGraphClient.Token longLived = graph.exchangeForLongLivedToken(shortLived.value());
        List<MetaGraphClient.Permission> permissions = graph.permissions(longLived.value());
        Set<String> granted = permissions.stream()
                .filter(permission -> "granted".equalsIgnoreCase(permission.status()))
                .map(permission -> permission.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<String> missing = properties.oauthScopes().stream()
                .filter(scope -> !granted.contains(scope.toLowerCase(Locale.ROOT)))
                .toList();
        if (!missing.isEmpty()) throw new MissingPermissionsException(missing);

        MetaGraphClient.Profile profile = graph.profile(longLived.value());
        if (profile.id().isBlank()) throw new MetaGraphClient.MetaGraphException(502, -1);
        List<MetaGraphClient.Page> pages = graph.pages(longLived.value());
        List<MetaGraphClient.AdAccount> adAccounts = graph.adAccounts(longLived.value());

        store.save(ownerEmail, profile, longLived, granted.stream().sorted().toList(), pages, adAccounts);
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
