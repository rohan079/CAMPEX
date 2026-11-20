package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sales")
@Getter
@Setter
@NoArgsConstructor
public class Sale {

    public static final String CHANNEL_AD = "ad";
    public static final String CHANNEL_AGENT = "calling_agent";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "ad_id", length = 128)
    private String adId;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "sold_at", nullable = false)
    private Instant soldAt;

    /** Lifecycle status; see {@link SaleStatus}. Defaults keep the legacy record-a-sale endpoint working. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = SaleStatus.BOOKED.name();

    /** Agreed booking/sale value. The legacy {@link #amount} column mirrors it. */
    @Column(name = "booking_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal bookingValue;

    @Column(name = "amount_collected", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountCollected = BigDecimal.ZERO;

    @Column(name = "refunded_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal outstandingAmount = BigDecimal.ZERO;

    /** Collected minus refunded on a confirmed sale. The only figure used as revenue in ROAS. */
    @Column(name = "confirmed_revenue", nullable = false, precision = 14, scale = 2)
    private BigDecimal confirmedRevenue = BigDecimal.ZERO;

    @Column(name = "campaign_id", length = 128)
    private String campaignId;

    @Column(name = "adset_id", length = 128)
    private String adsetId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "booking_date")
    private LocalDate bookingDate;

    @Column(name = "sale_confirmed_at")
    private Instant saleConfirmedAt;

    @Column(name = "confirmed_by", length = 320)
    private String confirmedBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "legacy_record", nullable = false)
    private boolean legacyRecord;

    @Column(name = "created_by", length = 320)
    private String createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (soldAt == null) {
            soldAt = createdAt;
        }
        if (bookingValue == null) {
            bookingValue = amount;
        }
        if (amount == null) {
            amount = bookingValue;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        if (outstandingAmount == null || (outstandingAmount.signum() == 0 && amountCollected.signum() == 0
                && !SaleStatus.parse(status).isClosed())) {
            outstandingAmount = bookingValue;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
