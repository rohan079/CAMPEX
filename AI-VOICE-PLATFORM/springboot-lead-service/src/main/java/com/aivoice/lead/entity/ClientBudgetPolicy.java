package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Client-defined budget guardrails used by budget recommendations. */
@Entity
@Table(name = "client_budget_policies")
@Getter
@Setter
@NoArgsConstructor
public class ClientBudgetPolicy {

    @Id
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "daily_spend_limit")
    private BigDecimal dailySpendLimit;

    @Column(name = "monthly_spend_limit")
    private BigDecimal monthlySpendLimit;

    @Column(name = "max_change_pct")
    private int maxChangePct = 20;

    @Column(name = "min_qualified_leads")
    private int minQualifiedLeads = 5;

    @Column(name = "target_cost_per_qualified_lead")
    private BigDecimal targetCostPerQualifiedLead;

    @Column(name = "target_roas")
    private BigDecimal targetRoas;

    @Column(name = "recommendation_ttl_hours")
    private int recommendationTtlHours = 72;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

}
