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

/** First-touch (never overwritten) and last-touch attribution pointers for a lead. */
@Entity
@Table(name = "lead_attributions")
@Getter
@Setter
@NoArgsConstructor
public class LeadAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "first_touch_id")
    private UUID firstTouchId;

    @Column(name = "last_touch_id")
    private UUID lastTouchId;

    @Column(name = "first_touch_at")
    private Instant firstTouchAt;

    @Column(name = "last_touch_at")
    private Instant lastTouchAt;

    @Column(name = "touch_count")
    private int touchCount = 1;

    @Column(name = "attribution_source")
    private String attributionSource;

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
