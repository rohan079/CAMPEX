package com.aivoice.lead.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Phase 3 experiment execution: readiness, launch, progress and follow-up. */
public final class ExperimentExecutionDtos {

    private ExperimentExecutionDtos() {
    }

    public record Check(String code, String severity, String message, String variantRole) {
    }

    public record VariantState(String variantId, String role, String name, String level, String objectId,
                               String objectName, String status, String effectiveStatus, String campaignId,
                               String creativeAssetId, Integer creativeVersion, String creativeName) {
    }

    public record LinkedAction(String actionId, String purpose, String variantRole, String actionType, String status,
                               String verification, Instant createdAt) {
    }

    public record Progress(LocalDate startDate, LocalDate plannedEndDate, Long daysElapsed, Long daysPlanned,
                           Integer minSampleSize, Map<String, Object> interim, boolean sampleReached, boolean durationReached,
                           String guidance) {
    }

    public record Execution(String experimentId, String experimentStatus, String outcome, String phase, boolean ready,
                            List<Check> checks, List<VariantState> variants, List<LinkedAction> actions, Progress progress,
                            List<String> allowedActions) {
    }

    public record CreativeLink(String creativeAssetId) {
    }
}
