package com.aivoice.lead.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Lead-to-sale lifecycle shapes. All money is manually entered financial information. */
public final class SaleDtos {

    private SaleDtos() {
    }

    private static final String STATUSES = "NEW|BOOKING_PENDING|BOOKED|PAYMENT_PENDING|PARTIALLY_PAID|FULLY_PAID|CANCELLED|REFUNDED";
    private static final String CHANNELS = "ad|calling_agent|sales_team|referral|walk_in|other";
    private static final String META_ID = "^[A-Za-z0-9_-]{0,128}$";

    public record SaleDetail(
            String id,
            String clientId,
            String leadId,
            String leadName,
            String campaignId,
            String adsetId,
            String adId,
            String projectId,
            String projectName,
            String agentId,
            String agentName,
            String channel,
            String status,
            BigDecimal bookingValue,
            BigDecimal amountCollected,
            BigDecimal outstandingAmount,
            BigDecimal refundedAmount,
            BigDecimal confirmedRevenue,
            LocalDate bookingDate,
            Instant saleConfirmedAt,
            String confirmedBy,
            String cancellationReason,
            String note,
            boolean legacyRecord,
            Instant soldAt,
            Instant createdAt,
            Instant updatedAt,
            List<SaleHistoryDto> history) {
    }

    public record SaleHistoryDto(String action, String fromStatus, String toStatus, Map<String, Object> changes,
                                 String reason, String actorEmail, Instant changedAt) {
    }

    public record CreateSale(
            @Size(max = 64) String leadId,
            @Size(max = 128) @Pattern(regexp = META_ID) String campaignId,
            @Size(max = 128) @Pattern(regexp = META_ID) String adsetId,
            @Size(max = 128) @Pattern(regexp = META_ID) String adId,
            @Size(max = 64) String projectId,
            @Size(max = 64) String agentId,
            @Pattern(regexp = CHANNELS) String channel,
            @NotNull @DecimalMin("0.01") @DecimalMax("100000000000") BigDecimal bookingValue,
            @DecimalMin("0") @DecimalMax("100000000000") BigDecimal amountCollected,
            @Pattern(regexp = STATUSES) String status,
            LocalDate bookingDate,
            @Size(max = 500) String note) {
    }

    public record UpdateSale(
            @Size(max = 128) @Pattern(regexp = META_ID) String campaignId,
            @Size(max = 128) @Pattern(regexp = META_ID) String adsetId,
            @Size(max = 128) @Pattern(regexp = META_ID) String adId,
            @Size(max = 64) String projectId,
            @Size(max = 64) String agentId,
            @Pattern(regexp = CHANNELS) String channel,
            @DecimalMin("0.01") @DecimalMax("100000000000") BigDecimal bookingValue,
            @DecimalMin("0") @DecimalMax("100000000000") BigDecimal amountCollected,
            @DecimalMin("0") @DecimalMax("100000000000") BigDecimal refundedAmount,
            @Pattern(regexp = STATUSES) String status,
            LocalDate bookingDate,
            @Size(max = 500) String note,
            @Size(max = 500) String reason) {
    }

    public record ConfirmSale(@DecimalMin("0") @DecimalMax("100000000000") BigDecimal amountCollected,
                              @Size(max = 500) String note) {
    }

    public record CancelSale(@NotBlank @Size(max = 500) String reason) {
    }

    public record SaleListPage(List<SaleDetail> sales, long total, Totals totals) {
    }

    public record Totals(long count, long bookings, BigDecimal bookingValue, BigDecimal amountCollected,
                         BigDecimal outstanding, BigDecimal refunded, BigDecimal confirmedRevenue) {
    }
}
