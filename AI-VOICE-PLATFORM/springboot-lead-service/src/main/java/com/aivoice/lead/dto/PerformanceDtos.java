package com.aivoice.lead.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Performance intelligence read models. Every metric's provenance is listed in {@code metric_sources}. */
public final class PerformanceDtos {

    private PerformanceDtos() {
    }

    public record DataNote(String code, String severity, String message) {
    }

    public record PerfRow(
            String level,
            String id,
            String name,
            String campaignId,
            String campaignName,
            String adsetId,
            String objective,
            String status,
            BigDecimal spend,
            long impressions,
            Long reach,
            BigDecimal frequency,
            long linkClicks,
            BigDecimal ctr,
            BigDecimal cpc,
            BigDecimal cpm,
            long metaLeads,
            BigDecimal metaCostPerLead,
            BigDecimal conversionRate,
            long crmLeads,
            long qualifiedLeads,
            BigDecimal qualifiedRate,
            BigDecimal costPerQualifiedLead,
            long siteVisits,
            BigDecimal costPerSiteVisit,
            long bookings,
            BigDecimal costPerBooking,
            BigDecimal bookingValue,
            BigDecimal confirmedRevenue,
            BigDecimal roas,
            List<String> flags) {
    }

    public record PerformanceReport(
            String clientId,
            String level,
            LocalDate from,
            LocalDate to,
            String currency,
            String attributionWindows,
            boolean metaAvailable,
            List<DataNote> dataNotes,
            Map<String, String> metricSources,
            PerfRow totals,
            PerfRow unattributedCrm,
            List<PerfRow> rows) {
    }

    public record Overview(PerformanceReport current, PerfRow previousTotals, LocalDate previousFrom,
                           LocalDate previousTo, Map<String, BigDecimal> pctChange) {
    }

    public record TrendPoint(LocalDate day, BigDecimal spend, long impressions, long linkClicks, BigDecimal ctr,
                             BigDecimal cpc, BigDecimal cpm, long metaLeads, long crmLeads, long qualifiedLeads,
                             boolean provisional) {
    }

    public record Trends(String clientId, String campaignId, LocalDate from, LocalDate to, boolean metaAvailable,
                         List<DataNote> dataNotes, List<TrendPoint> points) {
    }

    public record SyncResult(int snapshots, List<String> adAccounts, List<DataNote> dataNotes) {
    }
}
