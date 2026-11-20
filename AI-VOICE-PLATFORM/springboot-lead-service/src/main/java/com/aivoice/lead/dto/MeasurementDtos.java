package com.aivoice.lead.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Dashboard KPIs. Every metric states where its number comes from. */
public final class MeasurementDtos {

    private MeasurementDtos() {
    }

    /**
     * {@code source}: meta_reported (Meta Insights), campex_tracked (Campex conversion events), crm_verified
     * (pipeline, visits and bookings recorded in the CRM), manual_financial (amounts typed in by the team), derived
     * (computed from the others). {@code value} is null when it cannot be computed (e.g. no Meta spend).
     */
    public record Metric(String key, String label, BigDecimal value, String unit, String source, String note) {
    }

    public record KpiReport(
            String clientId,
            Instant from,
            Instant to,
            String currency,
            boolean metaSpendAvailable,
            String metaSpendNote,
            List<String> adAccountIds,
            List<Metric> metrics,
            List<FunnelStep> funnel,
            List<TrackedEventCount> trackedEvents) {
    }

    public record FunnelStep(String stage, long count, String source) {
    }

    public record TrackedEventCount(String eventName, long count) {
    }
}
