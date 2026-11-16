package com.aivoice.lead.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.cors.CorsConfiguration;

class AppConfigCorsTest {

    @Test
    void allowsConfiguredVercelOriginForApiAndHealth() {
        String origin = "https://campex.vercel.app";
        AppProperties properties = new AppProperties(
                "production",
                "Campex",
                "Business description",
                "Asia/Kolkata",
                7,
                3,
                "dashboard-key",
                "popup-token",
                "service-key",
                origin + ",https://custom.campex.example",
                new AppProperties.WhatsApp("verify", "app", "access", "phone", "vXX.X", "https://graph.facebook.com"),
                "http://voice.railway.internal:8000",
                30,
                1);
        TestCorsRegistry registry = new TestCorsRegistry();
        new AppConfig(properties).addCorsMappings(registry);
        Map<String, CorsConfiguration> cors = registry.configurations();

        CorsConfiguration api = cors.get("/api/**");
        assertEquals(origin, api.checkOrigin(origin));
        assertTrue(api.getAllowedMethods().contains("DELETE"));

        CorsConfiguration health = cors.get("/health");
        assertEquals(origin, health.checkOrigin(origin));
        assertTrue(health.getAllowedMethods().contains("GET"));
        assertNull(health.checkOrigin("https://unlisted.example"));
    }

    private static final class TestCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
