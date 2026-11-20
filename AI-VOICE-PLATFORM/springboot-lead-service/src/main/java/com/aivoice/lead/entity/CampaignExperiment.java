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
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A controlled A/B experiment between two Meta ads or ad sets. */
@Entity
@Table(name = "campaign_experiments")
@Getter
@Setter
@NoArgsConstructor
public class CampaignExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "campaign_id")
    private String campaignId;

    @Column(name = "name")
    private String name;

    @Column(name = "hypothesis", columnDefinition = "text")
    private String hypothesis;

    @Column(name = "variable")
    private String variable;

    @Column(name = "primary_metric")
    private String primaryMetric;

    @Column(name = "min_sample_size")
    private int minSampleSize;

    @Column(name = "confidence_level")
    private BigDecimal confidenceLevel;

    @Column(name = "status")
    private String status = "draft";

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "winner_variant_id")
    private UUID winnerVariantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "result_summary", columnDefinition = "text")
    private String resultSummary;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

}
