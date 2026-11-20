package com.aivoice.lead.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ExperimentDtos {

    private ExperimentDtos() {
    }

    private static final String META_ID = "^[0-9]{1,64}$";

    public record VariantInput(@NotBlank @Size(max = 160) String name,
                               @Size(max = 64) @Pattern(regexp = META_ID + "|^$") String adId,
                               @Size(max = 64) @Pattern(regexp = META_ID + "|^$") String adsetId,
                               @Size(max = 2000) String description,
                               @Size(max = 64) String creativeAnalysisId) {
    }

    public record CreateExperiment(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 2000) String hypothesis,
            @Size(max = 64) String campaignId,
            @NotBlank @Pattern(regexp = "image|headline|primary_text|cta|landing_page|audience") String variable,
            @NotBlank @Pattern(regexp = "ctr|cpc|cost_per_lead|cost_per_qualified_lead|conversion_rate|cost_per_site_visit|cost_per_booking") String primaryMetric,
            @NotNull @Min(1) @Max(1_000_000) Integer minSampleSize,
            BigDecimal confidenceLevel,
            LocalDate startDate,
            LocalDate endDate,
            @NotNull @Valid VariantInput control,
            @NotNull @Valid VariantInput test) {
    }

    public record VariantDto(String id, String role, String name, String adId, String adsetId, String description,
                             String creativeAnalysisId, Map<String, Object> metrics) {
    }

    public record ExperimentDto(String id, String clientId, String campaignId, String name, String hypothesis,
                                String variable, String primaryMetric, int minSampleSize, BigDecimal confidenceLevel,
                                String status, LocalDate startDate, LocalDate endDate, String outcome,
                                String winnerVariantId, Map<String, Object> result, String resultSummary,
                                Instant createdAt, Instant startedAt, Instant completedAt, List<VariantDto> variants,
                                List<PerformanceDtos.DataNote> dataNotes) {
    }
}
