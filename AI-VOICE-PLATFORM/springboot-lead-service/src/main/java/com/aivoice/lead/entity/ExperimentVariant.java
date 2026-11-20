package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Control or test arm of an experiment. */
@Entity
@Table(name = "experiment_variants")
@Getter
@Setter
@NoArgsConstructor
public class ExperimentVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "experiment_id")
    private UUID experimentId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "role")
    private String role;

    @Column(name = "name")
    private String name;

    @Column(name = "ad_id")
    private String adId;

    @Column(name = "adset_id")
    private String adsetId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "creative_analysis_id")
    private UUID creativeAnalysisId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

}
