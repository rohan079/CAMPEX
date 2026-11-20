package com.aivoice.lead.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** CRM pipeline, agents, projects, qualification, follow-ups and members. */
public final class CrmDtos {

    private CrmDtos() {
    }

    // ---------------------------------------------------------------- workspace setup

    public record AgentDto(String id, String clientId, String name, String email, String phoneNumber, boolean active,
                           long openLeads) {
    }

    public record AgentRequest(@NotBlank @Size(max = 160) String name, @Email @Size(max = 320) String email,
                               @Size(max = 24) String phoneNumber, Boolean active) {
    }

    public record ProjectDto(String id, String clientId, String name, String location, String propertyTypes,
                             BigDecimal minPrice, BigDecimal maxPrice, boolean active) {
    }

    public record ProjectRequest(@NotBlank @Size(max = 200) String name, @Size(max = 200) String location,
                                 @Size(max = 200) String propertyTypes,
                                 @DecimalMin("0") @DecimalMax("100000000000") BigDecimal minPrice,
                                 @DecimalMin("0") @DecimalMax("100000000000") BigDecimal maxPrice, Boolean active) {
    }

    public record MemberDto(String id, String userEmail, String role, Instant createdAt) {
    }

    public record MemberRequest(@NotBlank @Email @Size(max = 320) String userEmail,
                                @NotBlank @Pattern(regexp = "owner|manager|agent|viewer") String role) {
    }

    public record AdAccountMapping(@NotNull @Size(max = 20) List<@Pattern(regexp = "^(act_)?[0-9]{3,32}$") String> adAccountIds) {
    }

    // ---------------------------------------------------------------- pipeline

    public record LeadCard(
            String id,
            String name,
            String phoneNumber,
            String pipelineStatus,
            String reviewStatus,
            String source,
            String attributionSource,
            String campaignLabel,
            String assignedAgentId,
            String assignedAgentName,
            String projectId,
            Integer qualificationScore,
            Instant nextFollowUpAt,
            Instant createdAt,
            Instant pipelineUpdatedAt) {
    }

    public record PipelineColumn(String status, long count, List<LeadCard> leads) {
    }

    public record Pipeline(List<PipelineColumn> columns, long total) {
    }

    public record ManualLeadRequest(
            @NotBlank @Size(min = 8, max = 24) String phoneNumber,
            @Size(max = 120) String name,
            @Email @Size(max = 320) String email,
            @Size(max = 64) String projectId,
            @Size(max = 64) String agentId,
            @Size(max = 2000) String note,
            @Pattern(regexp = "en|te") String language,
            @Valid AttributionDtos.AttributionInput attribution) {
    }

    public record AssignRequest(@NotBlank @Size(max = 64) String agentId, @Size(max = 500) String reason) {
    }

    public record StatusChangeRequest(
            @NotBlank @Pattern(regexp = "NEW|CONTACTED|QUALIFIED|SITE_VISIT_SCHEDULED|SITE_VISIT_COMPLETED|NEGOTIATION|WON|LOST")
            String status,
            @Size(max = 500) String reason,
            @Size(max = 500) String lostReason) {
    }

    public record ActivityRequest(
            @NotBlank @Pattern(regexp = "note|call|whatsapp|email|meeting") String activityType,
            @NotBlank @Size(max = 5000) String body,
            Instant occurredAt) {
    }

    public record ActivityDto(String id, String activityType, String body, Map<String, Object> metadata,
                              String agentId, String actorEmail, Instant occurredAt) {
    }

    public record FollowUpRequest(
            @NotNull Instant dueAt,
            @Pattern(regexp = "call|whatsapp|email|meeting|other") String channel,
            @Size(max = 1000) String note,
            @Size(max = 64) String agentId) {
    }

    public record FollowUpUpdate(@NotBlank @Pattern(regexp = "pending|done|cancelled") String status,
                                 @Size(max = 1000) String note) {
    }

    public record FollowUpDto(String id, String leadId, String agentId, Instant dueAt, String channel, String note,
                              String status, Instant completedAt, boolean overdue) {
    }

    // ---------------------------------------------------------------- qualification & scoring

    public record QualificationRequest(
            @DecimalMin("0") @DecimalMax("100000000000") BigDecimal budgetMin,
            @DecimalMin("0") @DecimalMax("100000000000") BigDecimal budgetMax,
            @Size(max = 200) String preferredLocation,
            @Size(max = 80) String propertyType,
            @Pattern(regexp = "IMMEDIATE|WITHIN_3_MONTHS|WITHIN_6_MONTHS|WITHIN_12_MONTHS|LATER|UNKNOWN") String purchaseTimeline,
            @Min(0) @Max(20) Integer bedrooms,
            @Pattern(regexp = "SELF_FUNDED|HOME_LOAN|LOAN_PREAPPROVED|UNDECIDED") String financingPreference,
            @Pattern(regexp = "INVESTMENT|RESIDENTIAL|BOTH|UNKNOWN") String purpose,
            Boolean genuineInterest,
            @Size(max = 1000) String qualificationReason,
            @Size(max = 64) String projectId) {
    }

    public record ScoreFactor(String factor, String label, int weight, int points, boolean matched, String reason) {
    }

    public record QualificationDto(
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            String preferredLocation,
            String propertyType,
            String purchaseTimeline,
            Integer bedrooms,
            String financingPreference,
            String purpose,
            Boolean genuineInterest,
            int qualificationScore,
            List<ScoreFactor> scoreBreakdown,
            String qualificationReason,
            String qualifiedBy,
            Instant updatedAt) {
    }

    public record ScoringRule(@NotBlank @Pattern(regexp = "budget_matches_project|location_matches_project|timeline_immediate|contact_valid|genuine_interest|financing_ready") String factor,
                              @Min(0) @Max(100) int weight) {
    }

    public record ScoringConfig(@NotNull @Size(max = 10) List<@Valid ScoringRule> rules) {
    }

    // ---------------------------------------------------------------- lead detail & performance

    public record StatusHistoryDto(String fromStatus, String toStatus, String reason, String changedBy, Instant changedAt) {
    }

    public record AssignmentDto(String agentId, String agentName, String previousAgentId, String reason,
                                String assignedBy, Instant assignedAt, Instant unassignedAt) {
    }

    public record DuplicateDto(String id, String name, String phoneNumber, String pipelineStatus, Instant createdAt) {
    }

    public record CrmLeadDetail(
            String id,
            String clientId,
            String name,
            String phoneNumber,
            String email,
            String source,
            String status,
            String reviewStatus,
            String pipelineStatus,
            List<String> allowedNextStatuses,
            String lostReason,
            String assignedAgentId,
            String assignedAgentName,
            String projectId,
            Instant createdAt,
            Instant firstContactedAt,
            Long responseTimeMinutes,
            QualificationDto qualification,
            List<FollowUpDto> followUps,
            List<StatusHistoryDto> statusHistory,
            List<AssignmentDto> assignments,
            List<DuplicateDto> duplicates,
            String trackingConsent) {
    }

    public record LeadTrackingConsentRequest(boolean adDataGranted, @Size(max = 40) String policyVersion) {
    }

    public record AgentPerformance(
            String agentId,
            String agentName,
            long assignedLeads,
            long contactedLeads,
            long qualifiedLeads,
            long siteVisitsScheduled,
            long siteVisitsCompleted,
            long wonLeads,
            long bookings,
            BigDecimal confirmedRevenue,
            Double avgFirstResponseMinutes,
            long overdueFollowUps) {
    }

    public record PerformanceReport(Instant from, Instant to, String currency, List<AgentPerformance> agents) {
    }
}
