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
import org.hibernate.annotations.DynamicUpdate;

/**
 * DynamicUpdate keeps a late voice-service event from overwriting columns the
 * dispatcher wrote at almost the same moment.
 */
@Entity
@Table(name = "voice_calls")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class VoiceCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "openai_session_id", unique = true, length = 128)
    private String openaiSessionId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = CallStatus.QUEUED;

    @Column(name = "language", nullable = false, length = 8)
    private String language = "en";

    @Column(name = "outcome", length = 80)
    private String outcome;

    @Column(name = "summary")
    private String summary;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
