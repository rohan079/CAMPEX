package com.aivoice.lead.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campex.firebase")
public record FirebaseAuthProperties(String projectId, String apiKey) {

    /** Server-side verification only needs the Firebase project id. */
    public boolean isConfigured() {
        return projectId != null && !projectId.isBlank();
    }
}
