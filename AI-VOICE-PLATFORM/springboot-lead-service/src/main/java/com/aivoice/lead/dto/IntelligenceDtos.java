package com.aivoice.lead.dto;

import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Creative and audience intelligence shapes. */
public final class IntelligenceDtos {

    private IntelligenceDtos() {
    }

    public record Fatigue(String status, String reason, BigDecimal ctrRecent, BigDecimal ctrPrevious,
                          BigDecimal frequencyRecent, BigDecimal frequencyPrevious) {
    }

    public record CreativeRow(String adId, String adName, String campaignId, String adsetId, String status,
                              String creativeId, String headline, String primaryText, String cta, String imageUrl,
                              String format, PerfRow metrics, Fatigue fatigue, String rank) {
    }

    public record GroupRow(String dimension, String value, int ads, PerfRow metrics) {
    }

    public record CreativeAnalysisDto(String id, String adId, String campaignId, String observation,
                                      Map<String, Object> inputMetrics, Map<String, Object> suggestions, String status,
                                      String aiProvider, String aiModel, Instant createdAt) {
    }

    public record CreativeReport(String clientId, LocalDate from, LocalDate to, String currency, boolean metaAvailable,
                                 String rankingMetric, List<CreativeRow> creatives, List<GroupRow> byHeadline,
                                 List<GroupRow> byCta, List<GroupRow> byImage, List<CreativeAnalysisDto> drafts,
                                 List<DataNote> dataNotes) {
    }

    public record CreativeAnalysisRequest(@NotBlank @Size(max = 64) String adId, LocalDate from, LocalDate to,
                                          @Size(max = 500) String audienceNotes) {
    }

    public record SegmentRow(Map<String, String> segment, String label, BigDecimal spend, long impressions,
                             long linkClicks, BigDecimal ctr, BigDecimal cpc, BigDecimal cpm, long metaLeads,
                             BigDecimal metaCostPerLead, BigDecimal spendShare, BigDecimal leadShare, List<String> flags) {
    }

    public record CrmSegment(String dimension, String segment, long leads, long qualifiedLeads, long siteVisits,
                             long bookings, BigDecimal qualifiedRate, BigDecimal siteVisitRate, BigDecimal bookingRate) {
    }

    public record Insight(String type, String message, List<String> evidence) {
    }

    public record AudienceReport(String clientId, LocalDate from, LocalDate to, String breakdown, String currency,
                                 boolean metaAvailable, List<SegmentRow> segments, List<CrmSegment> crmSegments,
                                 Map<String, Map<String, Long>> locationFit, List<Insight> insights,
                                 List<DataNote> dataNotes) {
    }
}
