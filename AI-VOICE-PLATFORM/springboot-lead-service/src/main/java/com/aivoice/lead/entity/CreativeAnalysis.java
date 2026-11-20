package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** AI creative suggestions saved as drafts; never published automatically. */
@Entity
@Table(name = "creative_analyses")
@Getter
@Setter
@NoArgsConstructor
public class CreativeAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "ad_id")
    private String adId;

    @Column(name = "campaign_id")
    private String campaignId;

    @Column(name = "observation", columnDefinition = "text")
    private String observation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_metrics", columnDefinition = "jsonb")
    private Map<String, Object> inputMetrics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggestions", columnDefinition = "jsonb")
    private Map<String, Object> suggestions;

    @Column(name = "status")
    private String status = "draft";

    @Column(name = "ai_provider")
    private String aiProvider;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

}
