package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One supporting metric for a recommendation, with provenance. */
@Entity
@Table(name = "recommendation_evidence")
@Getter
@Setter
@NoArgsConstructor
public class RecommendationEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recommendation_id")
    private UUID recommendationId;

    @Column(name = "metric")
    private String metric;

    @Column(name = "label")
    private String label;

    @Column(name = "value")
    private BigDecimal value;

    @Column(name = "unit")
    private String unit;

    @Column(name = "source")
    private String source;

    @Column(name = "scope")
    private String scope;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

}
