package com.aivoice.lead.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Phase 3 automation rules. */
public final class AutomationDtos {

    private AutomationDtos() {
    }

    public record RuleInput(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "spend_over_limit|ctr_decline|cpl_above_threshold|delivery_issue") String ruleType,
            @Pattern(regexp = "^[0-9]{1,32}$") String campaignId,
            BigDecimal threshold,
            @Min(1) @Max(28) Integer observationDays,
            @Min(0) @Max(10_000_000) Integer minSample,
            @Pattern(regexp = "hourly|daily") String frequency,
            @Pattern(regexp = "info|warning|critical") String severity,
            Boolean notifyTeam,
            @Pattern(regexp = "MONITOR|RECOMMEND|APPROVAL_REQUIRED|PREAPPROVED") String executionLevel,
            @Pattern(regexp = "pause") String proposedAction,
            @Min(1) @Max(5) Integer maxAutoActionsPerDay,
            @Min(1) @Max(168) Integer cooldownHours,
            Boolean enabled) {
    }

    public record RuleDto(String id, String clientId, String name, String ruleType, String campaignId, BigDecimal threshold,
                         int observationDays, int minSample, String frequency, String severity, boolean notifyTeam,
                         String executionLevel, String proposedAction, int maxAutoActionsPerDay, int cooldownHours,
                         boolean enabled, Instant lastEvaluatedAt, String lastOutcome, String createdBy, Instant updatedAt) {
    }

    public record ExecutionDto(String id, String ruleId, String ruleName, String objectId, String objectName, String outcome,
                               String result, Map<String, Object> observed, String message, String actionId,
                               String triggeredBy, Instant evaluatedAt) {
    }

    public record Settings(boolean schedulerEnabled, boolean preapprovedActionsEnabled) {
    }
}
