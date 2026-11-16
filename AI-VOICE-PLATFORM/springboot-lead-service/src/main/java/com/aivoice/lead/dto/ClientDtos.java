package com.aivoice.lead.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Clients, ads, sales and the reach import. */
public final class ClientDtos {

    private ClientDtos() {
    }

    public record ClientDto(
            String id,
            String name,
            String slug,
            String description,
            long leads,
            boolean voiceAgentEnabled,
            String knowledgeBase,
            Instant createdAt) {
    }

    public record CreateClientRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$|^$", message = "slug: lowercase letters, digits and dashes") String slug,
            @Size(max = 2000) String description,
            boolean voiceAgentEnabled,
            @Size(max = 12000) String knowledgeBase) {
    }

    public record UpdateClientVoiceSettingsRequest(
            boolean voiceAgentEnabled,
            @Size(max = 12000) String knowledgeBase) {
    }

    public record AdDto(String id, String clientId, String externalId, String name) {
    }

    public record RegisterAdRequest(
            @NotBlank @Size(max = 128) String externalId,
            @Size(max = 200) String name) {
    }

    public record SaleDto(
            String id,
            String clientId,
            String leadId,
            String leadName,
            String adId,
            String channel,
            BigDecimal amount,
            String note,
            Instant soldAt) {
    }

    public record RecordSaleRequest(
            String clientId,
            String leadId,
            @Size(max = 128) String adId,
            @NotBlank @Pattern(regexp = "ad|calling_agent", message = "channel must be ad or calling_agent") String channel,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") BigDecimal amount,
            @Size(max = 500) String note,
            Instant soldAt) {
    }

    public record SalePage(List<SaleDto> sales, long total) {
    }

    public record ReachRow(
            @NotBlank @Size(max = 128) String adId,
            @Size(max = 200) String adName,
            @NotBlank @Size(max = 120) String location,
            @NotNull @Min(0) Integer reach,
            @Min(0) Integer impressions,
            @NotNull LocalDate day) {
    }

    public record ReachImportRequest(@NotEmpty @Size(max = 5000) List<@Valid ReachRow> rows) {
    }

    public record ReachImportResult(int imported) {
    }
}
