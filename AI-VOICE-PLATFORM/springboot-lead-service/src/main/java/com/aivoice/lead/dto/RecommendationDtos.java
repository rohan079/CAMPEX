package com.aivoice.lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class RecommendationDtos {

    private RecommendationDtos() {
    }

    public record EvidenceDto(String metric, String label, BigDecimal value, String unit, String source, String scope,
                              LocalDate periodFrom, LocalDate periodTo) {
    }

    public record RecommendationDto(
            String id,
            String clientId,
            String category,
            String recommendationType,
            String campaignId,
            String adsetId,
            String adId,
            String objectName,
            String title,
            String currentPerformance,
            String reasoning,
            String expectedImpact,
            boolean impactIsEstimate,
            String confidence,
            String riskLevel,
            String recommendedAction,
            Map<String, Object> proposedChange,
            String aiExplanation,
            String aiProvider,
            String status,
            LocalDate analysisFrom,
            LocalDate analysisTo,
            Instant calculatedAt,
            Instant createdAt,
            Instant expiresAt,
            String decidedBy,
            Instant decidedAt,
            String decisionNote,
            List<EvidenceDto> evidence) {
    }

    public record GenerateRequest(LocalDate from, LocalDate to, Boolean explain) {
    }

    public record GenerateResult(int findings, int created, int skippedDuplicates, boolean aiExplained,
                                 List<PerformanceDtos.DataNote> dataNotes, List<RecommendationDto> recommendations) {
    }

    public record StatusUpdate(@NotBlank @Pattern(regexp = "accepted|rejected|reviewed") String status,
                               @Size(max = 1000) String note) {
    }

    public record StatusResult(RecommendationDto recommendation, String message) {
    }

    public record BudgetPolicyDto(BigDecimal dailySpendLimit, BigDecimal monthlySpendLimit, int maxChangePct,
                                  int minQualifiedLeads, BigDecimal targetCostPerQualifiedLead, BigDecimal targetRoas,
                                  int recommendationTtlHours, boolean configured) {
    }

    public record BudgetPolicyRequest(
            @jakarta.validation.constraints.DecimalMin("1") BigDecimal dailySpendLimit,
            @jakarta.validation.constraints.DecimalMin("1") BigDecimal monthlySpendLimit,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) Integer maxChangePct,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) Integer minQualifiedLeads,
            @jakarta.validation.constraints.DecimalMin("0.01") BigDecimal targetCostPerQualifiedLead,
            @jakarta.validation.constraints.DecimalMin("0.01") BigDecimal targetRoas,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(720) Integer recommendationTtlHours) {
    }

    public record BudgetAnalysis(String clientId, LocalDate from, LocalDate to, String currency, boolean metaAvailable,
                                 BudgetPolicyDto policy, BigDecimal totalDailyBudget, BigDecimal projectedDailyBudget,
                                 List<com.aivoice.lead.service.performance.BudgetEngine.BudgetLine> campaigns,
                                 List<String> notes, List<PerformanceDtos.DataNote> dataNotes) {
    }
}
