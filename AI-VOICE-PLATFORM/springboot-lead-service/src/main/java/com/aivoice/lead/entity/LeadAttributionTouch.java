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

/** One attribution signal seen for a lead (attribution history). Immutable once written. */
@Entity
@Table(name = "lead_attribution_touches")
@Getter
@Setter
@NoArgsConstructor
public class LeadAttributionTouch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "source")
    private String source;

    @Column(name = "resolution_method")
    private String resolutionMethod;

    @Column(name = "campaign_id")
    private String campaignId;

    @Column(name = "campaign_name")
    private String campaignName;

    @Column(name = "adset_id")
    private String adsetId;

    @Column(name = "adset_name")
    private String adsetName;

    @Column(name = "ad_id")
    private String adId;

    @Column(name = "ad_name")
    private String adName;

    @Column(name = "meta_form_id")
    private String metaFormId;

    @Column(name = "meta_lead_id")
    private String metaLeadId;

    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "utm_medium")
    private String utmMedium;

    @Column(name = "utm_campaign")
    private String utmCampaign;

    @Column(name = "utm_content")
    private String utmContent;

    @Column(name = "utm_term")
    private String utmTerm;

    @Column(name = "fbclid")
    private String fbclid;

    @Column(name = "fbc")
    private String fbc;

    @Column(name = "fbp")
    private String fbp;

    @Column(name = "landing_page", columnDefinition = "text")
    private String landingPage;

    @Column(name = "referrer", columnDefinition = "text")
    private String referrer;

    @Column(name = "conversion_event_id")
    private UUID conversionEventId;

    @Column(name = "touched_at")
    private Instant touchedAt;

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
