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

/** A Meta Pixel configured for one client, plus its (encrypted) Conversions API token. */
@Entity
@Table(name = "tracking_pixels")
@Getter
@Setter
@NoArgsConstructor
public class TrackingPixel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "meta_pixel_id")
    private String metaPixelId;

    @Column(name = "name")
    private String name;

    @Column(name = "allowed_domains", columnDefinition = "text")
    private String allowedDomains = "";

    @Column(name = "enabled")
    private boolean enabled = true;

    @Column(name = "enabled_events", columnDefinition = "text")
    private String enabledEvents;

    @Column(name = "forward_custom_events")
    private boolean forwardCustomEvents = false;

    @Column(name = "consent_mode")
    private String consentMode = "required";

    @Column(name = "test_event_code")
    private String testEventCode;

    @Column(name = "capi_token_ciphertext", columnDefinition = "bytea")
    private byte[] capiTokenCiphertext;

    @Column(name = "capi_token_nonce", columnDefinition = "bytea")
    private byte[] capiTokenNonce;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
