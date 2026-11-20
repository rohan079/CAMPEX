package com.aivoice.lead.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Campaign-to-lead attribution shapes. */
public final class AttributionDtos {

    private AttributionDtos() {
    }

    private static final String META_ID = "^[A-Za-z0-9_-]{0,128}$";

    /** Attribution evidence supplied with a lead (popup, website form, manual entry). Everything optional. */
    public record AttributionInput(
            @Pattern(regexp = "website|landing_page|ad_popup|manual|meta_lead_form|backend_api") String source,
            @Size(max = 128) @Pattern(regexp = META_ID) String campaignId,
            @Size(max = 255) String campaignName,
            @Size(max = 128) @Pattern(regexp = META_ID) String adsetId,
            @Size(max = 255) String adsetName,
            @Size(max = 128) @Pattern(regexp = META_ID) String adId,
            @Size(max = 255) String adName,
            @Size(max = 128) @Pattern(regexp = META_ID) String metaFormId,
            @Size(max = 128) @Pattern(regexp = META_ID) String metaLeadId,
            @Size(max = 255) String utmSource,
            @Size(max = 255) String utmMedium,
            @Size(max = 255) String utmCampaign,
            @Size(max = 255) String utmContent,
            @Size(max = 255) String utmTerm,
            @Size(max = 255) String fbclid,
            @Size(max = 255) String fbc,
            @Size(max = 255) String fbp,
            @Size(max = 2048) String landingPage,
            @Size(max = 2048) String referrer,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]*$") String visitorId,
            @Size(max = 64) String sessionId) {

        public static AttributionInput empty() {
            return new AttributionInput(null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
        }
    }

    public record TouchDto(
            String id,
            String source,
            String resolutionMethod,
            String campaignId,
            String campaignName,
            String adsetId,
            String adsetName,
            String adId,
            String adName,
            String metaFormId,
            String metaLeadId,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmContent,
            String utmTerm,
            boolean fbclidPresent,
            boolean fbcPresent,
            boolean fbpPresent,
            String landingPage,
            String referrer,
            Instant touchedAt) {
    }

    public record LeadAttributionDto(
            String leadId,
            String clientId,
            String attributionSource,
            int touchCount,
            TouchDto firstTouch,
            TouchDto lastTouch,
            List<TouchDto> history) {
    }

    public record NameCount(String name, long count) {
    }

    public record AttributionSummary(
            long totalLeads,
            long attributedLeads,
            long unknownLeads,
            double unknownPercentage,
            long firstLastDiffer,
            List<NameCount> bySource,
            List<NameCount> byMethod) {
    }

    /** One campaign (or ad set / ad) row for the chosen attribution model. Money fields keep their provenance. */
    public record CampaignRow(
            String key,
            String campaignId,
            String label,
            long leads,
            long qualifiedLeads,
            long siteVisitsScheduled,
            long siteVisitsCompleted,
            long bookings,
            BigDecimal bookingValue,
            BigDecimal confirmedRevenue,
            BigDecimal amountCollected,
            BigDecimal metaSpend,
            Long metaImpressions,
            BigDecimal costPerLead,
            BigDecimal costPerQualifiedLead,
            BigDecimal costPerSiteVisit,
            BigDecimal costPerBooking,
            Double roas,
            Double roi) {
    }

    public record CampaignReport(
            String model,
            String groupBy,
            String currency,
            Instant from,
            Instant to,
            boolean metaSpendAvailable,
            String metaSpendNote,
            List<CampaignRow> rows) {
    }

    public record AttributedLeadRow(
            String leadId,
            String name,
            String pipelineStatus,
            String source,
            String resolutionMethod,
            String firstCampaignId,
            String firstCampaignLabel,
            String firstAdId,
            String lastCampaignId,
            String lastCampaignLabel,
            String lastAdId,
            int touchCount,
            Instant firstTouchAt,
            Instant lastTouchAt) {
    }

    public record AttributedLeadPage(List<AttributedLeadRow> leads, long total, int limit, long offset) {
    }
}
