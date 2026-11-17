package com.aivoice.lead.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AppPropertiesBindingTest {

    @Test
    void nestedVoiceServiceBindsAndReceivesDefaultsForMissingFields() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("app.environment", "development"),
                Map.entry("app.business-name", "Acme"),
                Map.entry("app.business-description", ""),
                Map.entry("app.business-timezone", "Asia/Kolkata"),
                Map.entry("app.follow-up-call-days", "7"),
                Map.entry("app.call-poll-seconds", "3"),
                Map.entry("app.ad-popup-token", ""),
                Map.entry("app.internal-service-key", ""),
                Map.entry("app.cors-origins", ""),
                Map.entry("app.whatsapp.verify-token", ""),
                Map.entry("app.whatsapp.app-secret", ""),
                Map.entry("app.whatsapp.access-token", ""),
                Map.entry("app.whatsapp.phone-number-id", ""),
                Map.entry("app.whatsapp.graph-api-version", "v1.0"),
                Map.entry("app.whatsapp.graph-base-url", "https://graph.example"),
                Map.entry("app.voice-service-base-url", "http://voice.internal:8000"),
                Map.entry("app.voice-service-request-timeout-seconds", "45")));
        AppProperties properties = new Binder(source)
                .bind("app", Bindable.of(AppProperties.class))
                .orElseThrow(() -> new AssertionError("app properties did not bind"));

        assertNotNull(properties.voiceService());
        assertEquals("http://voice.internal:8000", properties.voiceService().baseUrl());
        assertEquals(45, properties.voiceService().requestTimeoutSeconds());
        assertEquals(1, properties.voiceService().maxConcurrentCalls());
    }
}
