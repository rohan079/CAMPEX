package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Daily Meta-reported delivery for one campaign, ad set or ad. */
@Entity
@Table(name = "performance_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class PerformanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "ad_account_id")
    private String adAccountId;

    @Column(name = "level")
    private String level;

    @Column(name = "object_id")
    private String objectId;

    @Column(name = "campaign_id")
    private String campaignId;

    @Column(name = "adset_id")
    private String adsetId;

    @Column(name = "name")
    private String name;

    @Column(name = "day")
    private LocalDate day;

    @Column(name = "spend")
    private BigDecimal spend;

    @Column(name = "impressions")
    private long impressions;

    @Column(name = "reach")
    private Long reach;

    @Column(name = "frequency")
    private BigDecimal frequency;

    @Column(name = "clicks")
    private long clicks;

    @Column(name = "link_clicks")
    private long linkClicks;

    @Column(name = "meta_leads")
    private long metaLeads;

    @Column(name = "currency")
    private String currency;

    @Column(name = "attribution_setting")
    private String attributionSetting;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

}
