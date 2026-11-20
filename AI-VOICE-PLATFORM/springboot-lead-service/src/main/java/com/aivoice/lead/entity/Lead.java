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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId = Client.DEFAULT_ID;

    @Column(name = "phone_number", nullable = false, length = 24)
    private String phoneNumber;

    @Column(name = "whatsapp_user_id", length = 64)
    private String whatsappUserId;

    @Column(name = "first_name", length = 120)
    private String firstName;

    @Column(name = "initial_message")
    private String initialMessage;

    @Column(name = "campaign_id", length = 128)
    private String campaignId;

    @Column(name = "ad_id", length = 128)
    private String adId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "referral", columnDefinition = "jsonb")
    private Map<String, Object> referral;

    @Column(name = "ad_headline")
    private String adHeadline;

    @Column(name = "ad_body")
    private String adBody;

    @Column(name = "source", nullable = false, length = 32)
    private String source = LeadStatus.SOURCE_WHATSAPP;

    @Column(name = "status", nullable = false, length = 32)
    private String status = LeadStatus.AWAITING_CONSENT;

    @Column(name = "review_status", nullable = false, length = 16)
    private String reviewStatus = "pending";

    @Column(name = "preferred_language", nullable = false, length = 8)
    private String preferredLanguage = "en";

    @Column(name = "outreach_sent_at")
    private Instant outreachSentAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "follow_up_start")
    private LocalDate followUpStart;

    @Column(name = "follow_up_end")
    private LocalDate followUpEnd;

    /** Operational CRM status (NEW … WON/LOST); independent of {@link #status} and {@link #reviewStatus}. */
    @Column(name = "pipeline_status", nullable = false, length = 32)
    private String pipelineStatus = PipelineStatus.NEW.name();

    @Column(name = "pipeline_updated_at")
    private Instant pipelineUpdatedAt;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    @Column(name = "project_id")
    private UUID projectId;

    /** First time anyone from the sales team reached out; drives response-time reporting. */
    @Column(name = "first_contacted_at")
    private Instant firstContactedAt;

    @Column(name = "lost_reason", length = 500)
    private String lostReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isAdLead() {
        return LeadStatus.AD_SOURCES.contains(source);
    }
}
