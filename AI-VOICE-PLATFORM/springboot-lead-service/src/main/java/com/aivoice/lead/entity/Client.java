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

/** An advertiser the platform runs marketing for. */
@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
public class Client {

    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String DEFAULT_SLUG = "default";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 80)
    private String slug;

    @Column(name = "description")
    private String description;

    @Column(name = "voice_agent_enabled", nullable = false)
    private boolean voiceAgentEnabled = true;

    @Column(name = "knowledge_base", nullable = false, columnDefinition = "text")
    private String knowledgeBase = "";

    /** Client's Meta Business app id used for Channels Connect (optional). */
    @Column(name = "meta_app_id", length = 64)
    private String metaAppId;

    @Column(name = "meta_app_secret_ciphertext", columnDefinition = "bytea")
    private byte[] metaAppSecretCiphertext;

    @Column(name = "meta_app_secret_nonce", columnDefinition = "bytea")
    private byte[] metaAppSecretNonce;

    /** Login for Business configuration id created inside the client's Meta app. */
    @Column(name = "meta_login_config_id", length = 128)
    private String metaLoginConfigId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isDefault() {
        return DEFAULT_ID.equals(id);
    }
}
