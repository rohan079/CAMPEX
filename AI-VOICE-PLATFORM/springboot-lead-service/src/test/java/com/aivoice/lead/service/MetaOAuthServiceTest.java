package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.MetaOAuthProperties;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

class MetaOAuthServiceTest {

    private static final String OWNER = "operator@example.test";
    private static final List<String> SCOPES = List.of(
            "ads_management",
            "ads_read",
            "business_management",
            "pages_show_list",
            "pages_read_engagement",
            "instagram_basic",
            "instagram_manage_insights");

    @Test
    void authorizationUrlUsesCurrentGraphVersionAndRequiredPermissions() {
        MetaOAuthProperties properties = properties();
        MetaConnectionStore store = configuredStore();
        MetaOAuthService service = new MetaOAuthService(properties, mock(MetaGraphClient.class), store);

        URI uri = service.authorizationUri("state-value");
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        assertEquals("https", uri.getScheme());
        assertEquals("www.facebook.com", uri.getHost());
        assertEquals("/v27.0/dialog/oauth", uri.getPath());
        assertEquals("https://campex-production.up.railway.app/oauth/meta/callback", query.getFirst("redirect_uri"));
        assertEquals(String.join(",", SCOPES), query.getFirst("scope"));
        assertEquals("state-value", query.getFirst("state"));
    }

    @Test
    void exchangesTokensAndPersistsOnlyAfterRequiredPermissionsAreGranted() {
        MetaOAuthProperties properties = properties();
        MetaGraphClient graph = mock(MetaGraphClient.class);
        MetaConnectionStore store = configuredStore();
        Instant expiry = Instant.now().plusSeconds(3600);
        MetaGraphClient.Token shortToken = new MetaGraphClient.Token("short-user-token", "bearer", expiry, null);
        MetaGraphClient.Token longToken = new MetaGraphClient.Token("long-user-token", "bearer", expiry, null);
        MetaGraphClient.Profile profile = new MetaGraphClient.Profile("meta-user-1", "Campex Admin");
        List<MetaGraphClient.Page> pages = List.of(
                new MetaGraphClient.Page("page-1", "Campex Page", "Business", "page-token"));
        List<MetaGraphClient.AdAccount> accounts = List.of(
                new MetaGraphClient.AdAccount("act_123", "Campex Ads", "123", 1, "INR"));
        List<MetaGraphClient.InstagramAccount> instagramAccounts = List.of(
                new MetaGraphClient.InstagramAccount("ig-1", "campex.ig", "Campex IG", "page-1"));

        when(graph.exchangeCode("code")).thenReturn(shortToken);
        when(graph.exchangeForLongLivedToken(shortToken.value())).thenReturn(longToken);
        when(graph.permissions(longToken.value())).thenReturn(SCOPES.stream()
                .map(scope -> new MetaGraphClient.Permission(scope, "granted")).toList());
        when(graph.profile(longToken.value())).thenReturn(profile);
        when(graph.pages(longToken.value())).thenReturn(pages);
        when(graph.adAccounts(longToken.value())).thenReturn(accounts);
        when(graph.instagramAccounts(longToken.value())).thenReturn(instagramAccounts);

        new MetaOAuthService(properties, graph, store).complete(OWNER, "code");

        verify(store).save(
                OWNER,
                profile,
                longToken,
                SCOPES.stream().sorted().toList(),
                pages,
                accounts,
                instagramAccounts);
    }

    @Test
    void refusesConnectionWhenAnyRequiredPermissionWasDenied() {
        MetaGraphClient graph = mock(MetaGraphClient.class);
        MetaConnectionStore store = configuredStore();
        when(graph.exchangeCode("code")).thenReturn(new MetaGraphClient.Token("short", "bearer", null, null));
        when(graph.exchangeForLongLivedToken("short")).thenReturn(new MetaGraphClient.Token("long", "bearer", null, null));
        when(graph.permissions("long")).thenReturn(List.of(new MetaGraphClient.Permission("ads_read", "granted")));

        MetaOAuthService service = new MetaOAuthService(properties(), graph, store);
        assertThrows(MetaOAuthService.MissingPermissionsException.class, () -> service.complete(OWNER, "code"));
        verify(store, never()).save(
                eq(OWNER),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anyList(),
                anyList(),
                anyList(),
                anyList());
    }

    @Test
    void disconnectRevokesMetaConsentBeforeDeletingStoredConnection() {
        MetaGraphClient graph = mock(MetaGraphClient.class);
        MetaConnectionStore store = mock(MetaConnectionStore.class);
        when(store.userAccessToken(OWNER)).thenReturn("server-side-token");
        MetaOAuthService service = new MetaOAuthService(properties(), graph, store);

        service.disconnect(OWNER);

        verify(graph).revokeUserToken("server-side-token");
        verify(store).disconnect(OWNER);
    }

    @Test
    void disconnectRemovesLocallyWhenMetaTokenIsAlreadyExpired() {
        MetaGraphClient graph = mock(MetaGraphClient.class);
        MetaConnectionStore store = mock(MetaConnectionStore.class);
        when(store.userAccessToken(OWNER)).thenReturn("expired-token");
        doThrow(new MetaGraphClient.MetaGraphException(400, 190)).when(graph).revokeUserToken("expired-token");
        MetaOAuthService service = new MetaOAuthService(properties(), graph, store);

        service.disconnect(OWNER);

        verify(store).disconnect(OWNER);
    }

    private static MetaConnectionStore configuredStore() {
        MetaConnectionStore store = mock(MetaConnectionStore.class);
        when(store.encryptionConfigured()).thenReturn(true);
        return store;
    }

    @Test
    void authorizationUrlUsesBusinessLoginConfigIdWhenConfigured() {
        MetaOAuthProperties properties = new MetaOAuthProperties("app-id", "app-secret",
                "https://campex-production.up.railway.app/oauth/meta/callback", "v27.0",
                Base64.getEncoder().encodeToString(new byte[32]), "https://campexdashboard.vercel.app",
                "business-config-123", SCOPES);
        MetaOAuthService service = new MetaOAuthService(properties, mock(MetaGraphClient.class), configuredStore());

        URI uri = service.authorizationUri("state-value");
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        assertEquals("business-config-123", query.getFirst("config_id"));
        assertEquals(null, query.getFirst("scope"));
    }

    private static MetaOAuthProperties properties() {
        return new MetaOAuthProperties("app-id", "app-secret",
                "https://campex-production.up.railway.app/oauth/meta/callback", "v27.0",
                Base64.getEncoder().encodeToString(new byte[32]), "https://campexdashboard.vercel.app",
                "", SCOPES);
    }
}
