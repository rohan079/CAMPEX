package com.aivoice.lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Phase 3 campaign execution API. Business rules live in CampaignValidator; these only bound sizes. */
public final class CampaignExecutionDtos {

    private CampaignExecutionDtos() {
    }

    /** The full campaign configuration. Stored as JSONB and hashed; an approval binds to that hash. */
    public record CampaignConfig(
            @NotBlank @Size(max = 64) String adAccountId,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 20) String objective,
            @Size(max = 5) List<@Size(max = 20) String> platforms,
            @Size(max = 200) String location,
            Integer ageMin,
            Integer ageMax,
            Long dailyBudgetMinor,
            @Size(max = 300) String headline,
            @Size(max = 3000) String primaryText,
            @Size(max = 500) String description,
            @Size(max = 40) String cta,
            @Size(max = 2048) String destinationUrl,
            @Size(max = 64) String pageId,
            @Size(max = 64) String creativeAssetId,
            Integer creativeVersion,
            // Left out of the stored JSON when absent, so configs saved before these existed keep their hash.
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Size(max = 64) String locationKey,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Size(max = 16) String locationType,
            // More ads in the same ad set (up to 5 besides the main creative). Left out of the JSON when empty.
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            @Size(max = 5) List<@jakarta.validation.Valid CreativeRef> extraCreatives) {
    }

    /** An approved creative version used as one ad. */
    public record CreativeRef(@Size(max = 64) String assetId, Integer version) {
    }

    /** One ad Campex creates in the campaign's ad set. */
    public record AdDto(int position, String creativeAssetId, int creativeVersion, String status, String error,
                        String metaAdId) {
    }

    /** A place Meta can target, from Meta's location search. */
    public record MetaLocation(String key, String type, String name, String region, String countryName, String label) {
    }

    public record ValidationIssue(String field, String code, String message) {
    }

    public record ValidationReport(boolean valid, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
    }

    public record DecisionRequest(@Size(max = 1000) String note) {
    }

    public record PublishRequest(boolean confirm) {
    }

    public record ExecutionSummary(String id, String clientId, String name, String objective, String adAccountId,
                                   String status, String metaStatus, String metaEffectiveStatus,
                                   String metaCampaignId, String publishOutcome, String lastError,
                                   int configVersion, String createdBy, Instant createdAt, Instant updatedAt,
                                   Instant publishedAt) {
    }

    public record VersionDto(int version, String configHash, Map<String, Object> config, String createdBy,
                             Instant createdAt) {
    }

    public record ApprovalDto(String id, int configVersion, String decision, String decidedBy, Instant decidedAt,
                              String note, Long approvedDailyBudgetMinor, boolean valid) {
    }

    public record StepDto(String step, String outcome, String detail, Map<String, Object> metaIds, String actor,
                          Instant at) {
    }

    public record ExecutionDetail(ExecutionSummary execution, CampaignConfig config, String configHash,
                                  ValidationReport lastValidation, String publishTag, int publishAttempts,
                                  String metaAdsetId, Instant metaCheckedAt, List<VersionDto> versions,
                                  List<ApprovalDto> approvals, List<StepDto> steps,
                                  List<String> allowedActions, String adStatus, String adError, String metaAdId,
                                  String metaVideoId, String metaCreativeId, List<AdDto> ads) {
    }

    public record NotificationDto(String id, String clientId, String type, String severity, String title,
                                  String body, String entityType, String entityId, Instant createdAt,
                                  Instant readAt) {
    }
}
