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

/** A visitor's consent decision as captured by the website SDK or recorded by staff. */
@Entity
@Table(name = "tracking_consents")
@Getter
@Setter
@NoArgsConstructor
public class TrackingConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "pixel_id")
    private UUID pixelId;

    @Column(name = "visitor_id")
    private String visitorId;

    @Column(name = "analytics_storage")
    private String analyticsStorage;

    @Column(name = "ad_storage")
    private String adStorage;

    @Column(name = "ad_user_data")
    private String adUserData;

    @Column(name = "source")
    private String source;

    @Column(name = "policy_version")
    private String policyVersion;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
