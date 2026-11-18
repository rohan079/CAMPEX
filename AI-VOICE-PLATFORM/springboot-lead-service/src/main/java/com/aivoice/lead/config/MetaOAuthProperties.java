package com.aivoice.lead.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meta")
public record MetaOAuthProperties(
        String appId,
        String appSecret,
        String oauthRedirectUri,
        String graphApiVersion,
        String tokenEncryptionKey,
        String frontendRedirectUrl,
        List<String> oauthScopes) {

    private static final List<String> DEFAULT_SCOPES = List.of(
            "ads_management",
            "ads_read",
            "business_management",
            "pages_show_list",
            "pages_read_engagement",
            "instagram_basic",
            "instagram_manage_insights");

    public MetaOAuthProperties {
        graphApiVersion = graphApiVersion == null || graphApiVersion.isBlank() ? "v27.0" : graphApiVersion;
        List<String> configuredScopes = oauthScopes == null ? List.of() : oauthScopes.stream()
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .distinct()
                .toList();
        oauthScopes = configuredScopes.isEmpty() ? DEFAULT_SCOPES : configuredScopes;
    }

    public boolean credentialsConfigured() {
        return notBlank(appId) && notBlank(appSecret) && notBlank(oauthRedirectUri)
                && notBlank(tokenEncryptionKey) && notBlank(frontendRedirectUrl);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
