package com.aivoice.lead.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Performance intelligence settings ({@code campex.performance.*}). */
@ConfigurationProperties(prefix = "campex.performance")
public record PerformanceProperties(
        @DefaultValue("https://graph.facebook.com") String graphBaseUrl,
        @DefaultValue("20") int graphTimeoutSeconds,
        /** Meta attribution windows used for every Meta-reported conversion, so comparisons are consistent. */
        @DefaultValue("7d_click,1d_view") String attributionWindows,
        /** Days of daily snapshots kept fresh by the scheduled sync. */
        @DefaultValue("28") int syncLookbackDays,
        @DefaultValue("true") boolean scheduledSyncEnabled,
        /** Recent days Meta may still revise; shown as provisional and excluded from anomaly windows. */
        @DefaultValue("1") int metaDelayDays,
        @DefaultValue("1000") int minImpressions,
        @DefaultValue("50") int minClicks,
        @DefaultValue("10") int minConversions,
        @DefaultValue("24") int anomalyCooldownHours) {

    public static PerformanceProperties defaults() {
        return new PerformanceProperties("https://graph.facebook.com", 20, "7d_click,1d_view", 28, true, 1, 1000, 50, 10, 24);
    }
}
