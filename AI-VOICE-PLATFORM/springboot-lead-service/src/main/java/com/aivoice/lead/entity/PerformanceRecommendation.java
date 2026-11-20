package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An evidence-based, human-approved recommendation. Never applied automatically. */
@Entity
@Table(name = "performance_recommendations")
@Getter
@Setter
@NoArgsConstructor
public class PerformanceRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "category")
    private String category;

    @Column(name = "recommendation_type")
    private String recommendationType;

    @Column(name = "campaign_id")
    private String campaignId;

    @Column(name = "adset_id")
    private String adsetId;

    @Column(name = "ad_id")
    private String adId;

    @Column(name = "object_name")
    private String objectName;

    @Column(name = "title")
    private String title;

    @Column(name = "current_performance", columnDefinition = "text")
    private String currentPerformance;

    @Column(name = "reasoning", columnDefinition = "text")
    private String reasoning;

    @Column(name = "expected_impact", columnDefinition = "text")
    private String expectedImpact;

    @Column(name = "impact_is_estimate")
    private boolean impactIsEstimate = true;

    @Column(name = "confidence")
    private String confidence;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "recommended_action", columnDefinition = "text")
    private String recommendedAction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_change", columnDefinition = "jsonb")
    private Map<String, Object> proposedChange;

    @Column(name = "ai_explanation", columnDefinition = "text")
    private String aiExplanation;

    @Column(name = "ai_provider")
    private String aiProvider;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "status")
    private String status = "pending";

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "analysis_from")
    private LocalDate analysisFrom;

    @Column(name = "analysis_to")
    private LocalDate analysisTo;

    @Column(name = "calculated_at")
    private Instant calculatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

}
