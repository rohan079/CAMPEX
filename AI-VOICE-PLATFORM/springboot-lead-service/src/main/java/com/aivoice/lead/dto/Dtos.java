package com.aivoice.lead.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Wire shapes. Jackson renders these as snake_case, which the dashboard and voice service expect. */
public final class Dtos {

    private Dtos() {
    }

    public record LatestCallDto(String id, String status, String language, String outcome, Instant startedAt) {
    }

    public record CallDto(
            String id,
            String leadId,
            String leadName,
            String phoneNumber,
            String status,
            String language,
            String outcome,
            String summary,
            int attempts,
            String openaiSessionId,
            Instant startedAt,
            Instant endedAt,
            Instant createdAt) {
    }

    public record LeadDto(
            String id,
            String clientId,
            String phoneNumber,
            String firstName,
            String status,
            String reviewStatus,
            String preferredLanguage,
            String campaignId,
            String adId,
            String adHeadline,
            String adBody,
            String source,
            Instant outreachSentAt,
            Instant repliedAt,
            LocalDate followUpStart,
            LocalDate followUpEnd,
            String initialMessage,
            Instant createdAt,
            Instant updatedAt,
            LatestCallDto latestCall,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<CallDto> calls,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean languageLocked,
            String pipelineStatus,
            String assignedAgentId) {
    }

    public record LeadPage(List<LeadDto> leads, long total, int limit, long offset) {
    }

    public record LeadReviewStats(long pending, long accepted, long rejected) {
    }

    public record CallPage(List<CallDto> calls, long total, int limit, long offset) {
    }

    public record LanguageDto(String code, String label, String nativeName) {
    }

    public record LeadStats(long total, Map<String, Long> byStatus, Map<String, Long> byLanguage) {
    }

    public record CallStats(long total, Map<String, Long> byStatus) {
    }

    public record DashboardDto(
            String businessName,
            List<LanguageDto> languages,
            LeadStats leads,
            CallStats calls,
            List<LeadDto> recentLeads,
            List<CallDto> recentCalls) {
    }

    public record LanguageUpdate(@NotBlank @Size(max = 8) String language) {
    }

    public record LeadReviewUpdate(@NotBlank @Pattern(regexp = "pending|accepted|rejected") String reviewStatus) {
    }

    public record AdLeadRequest(
            @NotBlank @Size(min = 8, max = 24) String phoneNumber,
            @Size(max = 120) String name,
            @Size(max = 128) String adId,
            @Size(max = 128) String campaignId,
            @Size(max = 300) String adHeadline,
            @Size(max = 2000) String adBody,
            @Size(max = 8) String language,
            @Size(max = 80) String client,
            /** Optional, Phase 1: lets the CRM match and hash the lead for Conversions API events. */
            @jakarta.validation.constraints.Email @Size(max = 320) String email,
            /** Optional, Phase 1: campaign/click/UTM evidence, e.g. the result of Campex.getAttribution(). */
            @jakarta.validation.Valid AttributionDtos.AttributionInput attribution) {
    }

    public record AdLeadResponse(String id, String status, String phoneNumber,
                                 LocalDate followUpStart, LocalDate followUpEnd) {
    }

    /** What the voice service tells us about a call. */
    public record CallEventRequest(
            @NotBlank String callId,
            String sessionId,
            @NotBlank String type,
            String detail,
            String eventId) {
    }

    /** Concise locally generated summary; the raw audio transcript is not persisted by default. */
    public record CallSummaryRequest(
            @NotBlank String callId,
            @NotBlank @Size(max = 10000) String summary) {
    }

    public record AssistImage(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "image/(jpeg|png|webp)") String mimeType,
            @NotBlank @Size(max = 3500000) String dataBase64) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampaignAssistRequest(
            @NotBlank @Size(max = 160) String campaignName,
            @NotBlank @Pattern(regexp = "leads|traffic|sales|awareness") String objective,
            @NotEmpty @Size(max = 5) List<@Pattern(regexp = "facebook|instagram|whatsapp_business|google_ads|whatsapp_ads") String> platforms,
            @NotBlank @Pattern(regexp = "en|te") String language,
            @NotBlank @Size(max = 2000) String prompt,
            @Size(max = 5000) String content,
            @Size(max = 64) String adAccountId,
            @Size(max = 64) String clientId,
            @Size(max = 160) String clientName,
            @Size(max = 120) String skillId,
            @Size(max = 3) List<AssistImage> images,
            @Pattern(regexp = "assist|design") String mode,
            Boolean generateImage) {
    }

    public record GeneratedAssistImage(
            String mimeType,
            String dataBase64,
            String prompt) {
    }

    public record CampaignAssistResponse(
            String reply,
            String provider,
            String model,
            String suggestedCopy,
            String suggestedHeadline,
            String suggestedCta,
            String designNotes,
            String skillId,
            List<String> toolsUsed,
            List<GeneratedAssistImage> generatedImages,
            String imageError) {
    }

    public record SkillSummary(
            String id,
            String name,
            String description,
            String source,
            boolean featured) {
    }

    public record SkillUploadRequest(
            @NotBlank @Size(max = 80000) String markdown,
            @Size(max = 120) String name,
            @Size(max = 400) String description) {
    }

    /** Publish a paused Meta Ads campaign from Campaign Studio. */
    public record MetaCampaignPublishRequest(
            @NotBlank @Size(max = 160) String campaignName,
            @NotBlank @Pattern(regexp = "leads|traffic|sales|awareness") String objective,
            @NotEmpty @Size(max = 5) List<@Pattern(regexp = "facebook|instagram|whatsapp_ads") String> platforms,
            @NotBlank @Size(max = 160) String location,
            @jakarta.validation.constraints.Min(18) @jakarta.validation.constraints.Max(65) int ageMin,
            @jakarta.validation.constraints.Min(18) @jakarta.validation.constraints.Max(65) int ageMax,
            @NotBlank @Size(max = 80) String headline,
            @NotBlank @Size(max = 1500) String body,
            @NotBlank @Size(max = 40) String cta,
            @Size(max = 64) String adAccountId,
            @Size(max = 64) String pageId,
            @jakarta.validation.constraints.Min(100) Integer dailyBudgetMinor,
            @jakarta.validation.constraints.AssertTrue boolean confirmed,
            @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String geoKey,
            @Pattern(regexp = "city|region|country") String geoType) {
    }

    public record MetaCampaignPublishResponse(
            String campaignId,
            String adSetId,
            String adId,
            String adAccountId,
            String status,
            String adsManagerUrl,
            String warning) {
    }

    /** What we ask the voice service to start. */
    public record StartCallRequest(
            String callId,
            String leadId,
            String destination,
            String language,
            String firstName,
            String businessName,
            String businessDescription,
            String adHeadline,
            String adBody,
            boolean followUp,
            @Size(max = 12000) String knowledgeBase) {
    }

    public record StartCallResponse(String sessionId, String status) {
    }
}
