package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A scheduled property site visit for a lead. */
@Entity
@Table(name = "site_visits")
@Getter
@Setter
@NoArgsConstructor
public class SiteVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "duration_minutes")
    private int durationMinutes = 60;

    @Column(name = "status")
    private String status;

    @Column(name = "customer_confirmed")
    private boolean customerConfirmed;

    @Column(name = "customer_confirmed_at")
    private Instant customerConfirmedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "follow_up_notes", columnDefinition = "text")
    private String followUpNotes;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "rescheduled_from_id")
    private UUID rescheduledFromId;

    @Column(name = "reminder_at")
    private Instant reminderAt;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "created_by")
    private String createdBy;

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
