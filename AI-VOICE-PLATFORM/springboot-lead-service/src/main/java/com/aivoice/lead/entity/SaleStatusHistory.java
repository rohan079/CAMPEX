package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Audit trail entry for a sale: lifecycle transitions and financial edits. */
@Entity
@Table(name = "sale_status_history")
@Getter
@Setter
@NoArgsConstructor
public class SaleStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sale_id")
    private UUID saleId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "action")
    private String action;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", columnDefinition = "jsonb")
    private Map<String, Object> changes;

    @Column(name = "reason")
    private String reason;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "changed_at")
    private Instant changedAt;
}
