package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A deterministically detected performance anomaly. */
@Entity
@Table(name = "campaign_anomalies")
@Getter
@Setter
@NoArgsConstructor
public class CampaignAnomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "campaign_id")
    private String campaignId;

    @Column(name = "campaign_name")
    private String campaignName;

    @Column(name = "metric")
    private String metric;

    @Column(name = "severity")
    private String severity;

    @Column(name = "previous_value")
    private BigDecimal previousValue;

    @Column(name = "current_value")
    private BigDecimal currentValue;

    @Column(name = "pct_change")
    private BigDecimal pctChange;

    @Column(name = "z_score")
    private BigDecimal zScore;

    @Column(name = "baseline_from")
    private LocalDate baselineFrom;

    @Column(name = "baseline_to")
    private LocalDate baselineTo;

    @Column(name = "current_from")
    private LocalDate currentFrom;

    @Column(name = "current_to")
    private LocalDate currentTo;

    @Column(name = "detection_reason", columnDefinition = "text")
    private String detectionReason;

    @Column(name = "suggested_action", columnDefinition = "text")
    private String suggestedAction;

    @Column(name = "ai_explanation", columnDefinition = "text")
    private String aiExplanation;

    @Column(name = "status")
    private String status = "open";

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "occurrences")
    private int occurrences = 1;

    @Column(name = "detected_at")
    private Instant detectedAt;

    @Column(name = "last_detected_at")
    private Instant lastDetectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

}
