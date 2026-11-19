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

@Entity
@Table(name = "meta_connections")
@Getter
@Setter
@NoArgsConstructor
public class MetaConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_email", nullable = false, unique = true, length = 320)
    private String ownerEmail;

    /** Workspace client whose Meta app credentials were used for this connection, if any. */
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "meta_app_id", length = 64)
    private String metaAppId;

    @Column(name = "meta_user_id", nullable = false, length = 128)
    private String metaUserId;

    @Column(name = "meta_user_name", nullable = false, length = 255)
    private String metaUserName;

    @Column(name = "access_token_ciphertext", nullable = false, columnDefinition = "bytea")
    private byte[] accessTokenCiphertext;

    @Column(name = "access_token_nonce", nullable = false, columnDefinition = "bytea")
    private byte[] accessTokenNonce;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "data_access_expires_at")
    private Instant dataAccessExpiresAt;

    @Column(name = "granted_scopes", nullable = false, columnDefinition = "text")
    private String grantedScopes;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (connectedAt == null) connectedAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
