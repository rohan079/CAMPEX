package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per inbound WhatsApp message id, so Meta's retries are processed once. */
@Entity
@Table(name = "whatsapp_events")
@Getter
@Setter
@NoArgsConstructor
public class WhatsAppEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 128)
    private String messageId;

    @Column(name = "sender", nullable = false, length = 24)
    private String sender;

    @Column(name = "message_text")
    private String messageText;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public WhatsAppEvent(String messageId, String sender, String messageText) {
        this.messageId = messageId;
        this.sender = sender;
        this.messageText = messageText;
    }

    @PrePersist
    void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }
}
