package com.aivoice.lead.service.performance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** One deterministic finding that becomes a recommendation. Every number it states is in {@code evidence}. */
public record Finding(
        String category,
        String type,
        String campaignId,
        String adsetId,
        String adId,
        String objectName,
        String title,
        String currentPerformance,
        String reasoning,
        String expectedImpact,
        String confidence,
        String risk,
        String action,
        Map<String, Object> proposedChange,
        List<Evidence> evidence,
        String dedupKey) {

    public record Evidence(String metric, String label, BigDecimal value, String unit, String source, String scope) {
    }

    /** Confidence from conversion volume: ≥30 high, ≥ minimum medium, otherwise low. */
    public static String confidence(long conversions, int minimum) {
        if (conversions >= 30) return "high";
        if (conversions >= minimum) return "medium";
        return "low";
    }
}
