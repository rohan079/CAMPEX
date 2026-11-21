package com.aivoice.lead.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Phase 3 optimization actions: preview → approve → execute → verify. */
public final class CampaignActionDtos {

    private CampaignActionDtos() {
    }

    /** Either a recommendation id (the action is derived from it) or an explicit action. */
    public record PreviewRequest(
            @Size(max = 64) String recommendationId,
            @Pattern(regexp = "pause|resume|set_daily_budget") String actionType,
            @Pattern(regexp = "campaign|adset|ad") String level,
            @Pattern(regexp = "^[0-9]{1,32}$") String objectId,
            Long dailyBudgetMinor,
            @Size(max = 2000) String reason) {
    }

    public record ExecuteRequest(boolean confirm) {
    }

    public record GuardrailCheck(String code, String severity, String message) {
    }

    public record EventDto(String event, String outcome, String detail, String actor, Instant at) {
    }

    public record ActionDto(String id, String clientId, String recommendationId, String source, String actionType,
                            String level, String objectId, String objectName, String adAccountId, String status,
                            Map<String, Object> beforeState, Map<String, Object> proposedAfter,
                            Map<String, Object> actualAfter, String verification, List<GuardrailCheck> guardrails,
                            String reason, int attempts, String lastError, String createdBy, String approvedBy,
                            Instant approvedAt, String approvalNote, String executedBy, Instant executedAt,
                            Instant verifiedAt, Instant expiresAt, Instant createdAt, List<EventDto> events,
                            List<String> allowedActions) {
    }
}
