package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "meta_connected_assets")
@Getter
@Setter
@NoArgsConstructor
public class MetaConnectedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "asset_type", nullable = false, length = 24)
    private String assetType;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "account_id", length = 128)
    private String accountId;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "account_status")
    private Integer accountStatus;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "page_token_ciphertext", columnDefinition = "bytea")
    private byte[] pageTokenCiphertext;

    @Column(name = "page_token_nonce", columnDefinition = "bytea")
    private byte[] pageTokenNonce;
}
