package com.aivoice.lead.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Website tracking, consent, retention and Meta Conversions API dispatch settings ({@code campex.tracking.*}). */
@ConfigurationProperties(prefix = "campex.tracking")
public record TrackingProperties(
        /** Master switch for sending events to Meta. Off by default: events are stored but not sent. */
        @DefaultValue("false") boolean capiEnabled,
        @DefaultValue("https://graph.facebook.com") String capiBaseUrl,
        @DefaultValue("10") int capiTimeoutSeconds,
        @DefaultValue("50") int dispatchBatchSize,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("30") int baseBackoffSeconds,
        @DefaultValue("21600") int maxBackoffSeconds,
        @DefaultValue("120") int dispatchLockSeconds,
        /** Meta rejects events older than 7 days. */
        @DefaultValue("7") int maxEventAgeDays,
        @DefaultValue("300") int maxFutureSkewSeconds,
        @DefaultValue("120") int rateLimitPerMinutePerIp,
        @DefaultValue("3000") int rateLimitPerMinutePerPixel,
        @DefaultValue("16384") int maxPayloadBytes,
        /** Conversion events and consent records are deleted after this many days. */
        @DefaultValue("395") int retentionDays,
        /** IP address and user agent are cleared from stored events after this many days. */
        @DefaultValue("30") int piiRetentionDays) {

    public TrackingProperties {
        maxAttempts = Math.max(1, maxAttempts);
        dispatchBatchSize = Math.min(Math.max(1, dispatchBatchSize), 1000);
        baseBackoffSeconds = Math.max(1, baseBackoffSeconds);
        maxBackoffSeconds = Math.max(baseBackoffSeconds, maxBackoffSeconds);
        retentionDays = Math.max(30, retentionDays);
        piiRetentionDays = Math.max(1, Math.min(piiRetentionDays, retentionDays));
    }

    public static TrackingProperties defaults() {
        return new TrackingProperties(false, "https://graph.facebook.com", 10, 50, 8, 30, 21600, 120, 7, 300,
                120, 3000, 16384, 395, 30);
    }
}
