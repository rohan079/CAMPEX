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
import java.util.Map;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Qualification answers and the explainable score derived from them. */
@Entity
@Table(name = "lead_qualifications")
@Getter
@Setter
@NoArgsConstructor
public class LeadQualification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "budget_min")
    private BigDecimal budgetMin;

    @Column(name = "budget_max")
    private BigDecimal budgetMax;

    @Column(name = "preferred_location")
    private String preferredLocation;

    @Column(name = "property_type")
    private String propertyType;

    @Column(name = "purchase_timeline")
    private String purchaseTimeline;

    @Column(name = "bedrooms")
    private Short bedrooms;

    @Column(name = "financing_preference")
    private String financingPreference;

    @Column(name = "purpose")
    private String purpose;

    @Column(name = "genuine_interest")
    private Boolean genuineInterest;

    @Column(name = "qualification_score")
    private int qualificationScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_breakdown", columnDefinition = "jsonb")
    private List<Map<String, Object>> scoreBreakdown;

    @Column(name = "qualification_reason")
    private String qualificationReason;

    @Column(name = "qualified_by")
    private String qualifiedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
