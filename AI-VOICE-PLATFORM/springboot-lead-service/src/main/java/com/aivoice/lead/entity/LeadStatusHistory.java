package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A change of a lead's operational pipeline status. */
@Entity
@Table(name = "lead_status_history")
@Getter
@Setter
@NoArgsConstructor
public class LeadStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(name = "reason")
    private String reason;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "changed_at")
    private Instant changedAt;
}
