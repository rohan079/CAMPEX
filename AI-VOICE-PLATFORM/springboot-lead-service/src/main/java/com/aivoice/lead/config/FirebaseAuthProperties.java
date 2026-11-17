package com.aivoice.lead.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campex.firebase")
public record FirebaseAuthProperties(String projectId, String apiKey) {

    public boolean isConfigured() {
        return notBlank(projectId) && notBlank(apiKey);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
