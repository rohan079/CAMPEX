package com.aivoice.lead.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String environment,
        String businessName,
        String businessDescription,
        String businessTimezone,
        int followUpCallDays,
        int callPollSeconds,
        String dashboardApiKey,
        String adPopupToken,
        String internalServiceKey,
        String corsOrigins,
        WhatsApp whatsapp,
        @DefaultValue("http://localhost:8000") String voiceServiceBaseUrl,
        @DefaultValue("30") int voiceServiceRequestTimeoutSeconds,
        @DefaultValue("1") int voiceServiceMaxConcurrentCalls) {

    public VoiceService voiceService() {
        return new VoiceService(voiceServiceBaseUrl, voiceServiceRequestTimeoutSeconds, voiceServiceMaxConcurrentCalls);
    }

    public record WhatsApp(
            String verifyToken,
            String appSecret,
            String accessToken,
            String phoneNumberId,
            String graphApiVersion,
            String graphBaseUrl) {

        public String messagesUrl() {
            String base = graphBaseUrl.endsWith("/") ? graphBaseUrl.substring(0, graphBaseUrl.length() - 1) : graphBaseUrl;
            return base + "/" + graphApiVersion + "/" + phoneNumberId + "/messages";
        }
    }

    public record VoiceService(
            String baseUrl,
            int requestTimeoutSeconds,
            int maxConcurrentCalls) {
        public VoiceService {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://localhost:8000";
            }
            if (requestTimeoutSeconds < 1) {
                requestTimeoutSeconds = 30;
            }
            if (maxConcurrentCalls < 1) {
                maxConcurrentCalls = 1;
            }
        }

        public VoiceService(String baseUrl, int requestTimeoutSeconds) {
            this(baseUrl, requestTimeoutSeconds, 5);
        }
    }

    public boolean isDevelopment() {
        return "development".equalsIgnoreCase(environment);
    }

    public ZoneId zone() {
        return ZoneId.of(businessTimezone);
    }
}
