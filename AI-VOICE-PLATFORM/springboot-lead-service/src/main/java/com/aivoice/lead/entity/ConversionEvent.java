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
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One conversion event (browser, server or CRM). Doubles as the Meta CAPI outbox row. */
@Entity
@Table(name = "conversion_events")
@Getter
@Setter
@NoArgsConstructor
public class ConversionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_name")
    private String eventName;

    @Column(name = "event_category")
    private String eventCategory;

    @Column(name = "meta_event_name")
    private String metaEventName;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "visitor_id")
    private String visitorId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "pixel_id")
    private UUID pixelId;

    @Column(name = "campaign_id")
    private String campaignId;

    @Column(name = "adset_id")
    private String adsetId;

    @Column(name = "ad_id")
    private String adId;

    @Column(name = "event_source")
    private String eventSource;

    @Column(name = "event_time")
    private Instant eventTime;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "event_source_url", columnDefinition = "text")
    private String eventSourceUrl;

    @Column(name = "referrer_url", columnDefinition = "text")
    private String referrerUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_data_hashes", columnDefinition = "jsonb")
    private Map<String, Object> userDataHashes;

    @Column(name = "fbc")
    private String fbc;

    @Column(name = "fbp")
    private String fbp;

    @Column(name = "fbclid")
    private String fbclid;

    @Column(name = "client_ip_address")
    private String clientIpAddress;

    @Column(name = "client_user_agent")
    private String clientUserAgent;

    @Column(name = "consent_status")
    private String consentStatus;

    @Column(name = "meta_delivery_status")
    private String metaDeliveryStatus;

    @Column(name = "delivery_attempts")
    private int deliveryAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "duplicate_count")
    private int duplicateCount;

    @Column(name = "is_test")
    private boolean test;

    @Column(name = "test_event_code")
    private String testEventCode;

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
