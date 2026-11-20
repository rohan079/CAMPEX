package com.aivoice.lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AnomalyDtos {

    private AnomalyDtos() {
    }

    public record AnomalyDto(String id, String clientId, String campaignId, String campaignName, String metric,
                             String severity, BigDecimal previousValue, BigDecimal currentValue, BigDecimal pctChange,
                             BigDecimal zScore, LocalDate baselineFrom, LocalDate baselineTo, LocalDate currentFrom,
                             LocalDate currentTo, String detectionReason, String suggestedAction, String aiExplanation,
                             String status, int occurrences, Instant detectedAt, Instant lastDetectedAt,
                             Instant resolvedAt, String resolvedBy) {
    }

    public record DetectionResult(int detected, int created, int updated, int suppressedByCooldown,
                                  List<PerformanceDtos.DataNote> dataNotes) {
    }

    public record StatusUpdate(@NotBlank @Pattern(regexp = "acknowledged|resolved|dismissed") String status,
                               @Size(max = 1000) String note) {
    }
}
