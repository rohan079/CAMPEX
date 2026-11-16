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

@Entity
@Table(name = "lead_consents")
@Getter
@Setter
@NoArgsConstructor
public class LeadConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "consent_type", nullable = false, length = 40)
    private String consentType = "marketing_phone_call";

    @Column(name = "purpose", nullable = false, length = 200)
    private String purpose = "AI-assisted sales follow-up call";

    @Column(name = "evidence_text", nullable = false)
    private String evidenceText;

    @Column(name = "source_message_id", nullable = false, unique = true, length = 128)
    private String sourceMessageId;

    @Column(name = "disclosure_version", nullable = false, length = 40)
    private String disclosureVersion = "v1";

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    void onCreate() {
        if (consentedAt == null) {
            consentedAt = Instant.now();
        }
    }
}
