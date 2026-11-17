package com.aivoice.lead.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class MetaOAuthPropertiesBindingTest {

    @Test
    void bindsMetaEnvironmentSettingsAndCommaSeparatedScopes() {
        var source = new MapConfigurationPropertySource(Map.of(
                "meta.app-id", "app-id",
                "meta.app-secret", "server-only-test-secret",
                "meta.oauth-redirect-uri", "https://campex.example/oauth/meta/callback",
                "meta.graph-api-version", "v27.0",
                "meta.token-encryption-key", "base64-test-value",
                "meta.frontend-redirect-url", "https://campex.example",
                "meta.oauth-scopes", "ads_management,ads_read,business_management,pages_show_list,pages_read_engagement"));

        MetaOAuthProperties properties = new Binder(source)
                .bind("meta", Bindable.of(MetaOAuthProperties.class))
                .orElseThrow(() -> new AssertionError("meta properties did not bind"));

        assertEquals("app-id", properties.appId());
        assertEquals("v27.0", properties.graphApiVersion());
        assertEquals(List.of("ads_management", "ads_read", "business_management", "pages_show_list", "pages_read_engagement"),
                properties.oauthScopes());
    }
}
