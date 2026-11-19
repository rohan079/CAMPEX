package com.aivoice.lead.service;

import com.aivoice.lead.entity.MetaConnectedAsset;
import com.aivoice.lead.entity.MetaConnection;
import com.aivoice.lead.repository.MetaConnectedAssetRepository;
import com.aivoice.lead.repository.MetaConnectionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetaConnectionStore {

    private final MetaTokenCipher cipher;
    private final MetaConnectionRepository connections;
    private final MetaConnectedAssetRepository assets;

    public MetaConnectionStore(
            MetaTokenCipher cipher,
            MetaConnectionRepository connections,
            MetaConnectedAssetRepository assets) {
        this.cipher = cipher;
        this.connections = connections;
        this.assets = assets;
    }

    public record PageInfo(String id, String name, String category) {
    }

    public record AdAccountInfo(String id, String name, String accountId, Integer accountStatus, String currency) {
    }

    public record InstagramAccountInfo(String id, String name, String username, String pageId) {
    }

    public record ConnectionStatus(
            boolean connected,
            boolean expired,
            String metaUserName,
            Instant connectedAt,
            Instant tokenExpiresAt,
            List<PageInfo> pages,
            List<AdAccountInfo> adAccounts,
            List<InstagramAccountInfo> instagramAccounts,
            String clientId,
            String metaAppId,
            boolean usingClientApp) {
    }

    public boolean encryptionConfigured() {
        return cipher.isConfigured();
    }

    @Transactional(readOnly = true)
    public String userAccessToken(String ownerEmail) {
        String owner = ownerEmail.toLowerCase(Locale.ROOT);
        return connections.findByOwnerEmail(owner)
                .map(connection -> cipher.decrypt(connection.getAccessTokenCiphertext(),
                        connection.getAccessTokenNonce(), "user:" + owner))
                .orElse(null);
    }

    @Transactional
    public void save(
            String ownerEmail,
            UUID clientId,
            String metaAppId,
            MetaGraphClient.Profile profile,
            MetaGraphClient.Token token,
            List<String> grantedScopes,
            List<MetaGraphClient.Page> pages,
            List<MetaGraphClient.AdAccount> adAccounts,
            List<MetaGraphClient.InstagramAccount> instagramAccounts) {
        String normalizedOwner = ownerEmail.toLowerCase(Locale.ROOT);
        MetaConnection connection = connections.findByOwnerEmail(normalizedOwner).orElseGet(MetaConnection::new);
        connection.setOwnerEmail(normalizedOwner);
        connection.setClientId(clientId);
        connection.setMetaAppId(metaAppId);
        connection.setMetaUserId(profile.id());
        connection.setMetaUserName(profile.name());
        MetaTokenCipher.EncryptedValue encryptedUserToken = cipher.encrypt(token.value(), "user:" + normalizedOwner);
        connection.setAccessTokenCiphertext(encryptedUserToken.ciphertext());
        connection.setAccessTokenNonce(encryptedUserToken.nonce());
        connection.setTokenExpiresAt(token.expiresAt());
        connection.setDataAccessExpiresAt(token.dataAccessExpiresAt());
        connection.setGrantedScopes(String.join(",", grantedScopes.stream().sorted().toList()));
        connection = connections.save(connection);

        UUID connectionId = connection.getId();
        assets.deleteAllByConnectionId(connectionId);
        List<MetaConnectedAsset> stored = new ArrayList<>(
                pages.size() + adAccounts.size() + instagramAccounts.size());
        for (MetaGraphClient.Page page : pages) {
            MetaConnectedAsset asset = new MetaConnectedAsset();
            asset.setConnectionId(connectionId);
            asset.setAssetType("page");
            asset.setExternalId(page.id());
            asset.setName(page.name());
            asset.setCategory(page.category());
            if (page.accessToken() != null && !page.accessToken().isBlank()) {
                MetaTokenCipher.EncryptedValue pageToken = cipher.encrypt(page.accessToken(),
                        "page:" + normalizedOwner + ":" + page.id());
                asset.setPageTokenCiphertext(pageToken.ciphertext());
                asset.setPageTokenNonce(pageToken.nonce());
            }
            stored.add(asset);
        }
        for (MetaGraphClient.AdAccount account : adAccounts) {
            MetaConnectedAsset asset = new MetaConnectedAsset();
            asset.setConnectionId(connectionId);
            asset.setAssetType("ad_account");
            asset.setExternalId(account.id());
            asset.setName(account.name());
            asset.setAccountId(account.accountId());
            asset.setAccountStatus(account.status());
            asset.setCurrency(account.currency());
            stored.add(asset);
        }
        for (MetaGraphClient.InstagramAccount account : instagramAccounts) {
            MetaConnectedAsset asset = new MetaConnectedAsset();
            asset.setConnectionId(connectionId);
            asset.setAssetType("instagram");
            asset.setExternalId(account.id());
            asset.setName(account.name());
            asset.setAccountId(account.username());
            asset.setCategory(account.pageId());
            stored.add(asset);
        }
        if (!stored.isEmpty()) assets.saveAll(stored);
    }

    @Transactional(readOnly = true)
    public ConnectionStatus status(String ownerEmail) {
        MetaConnection connection = connections.findByOwnerEmail(ownerEmail.toLowerCase(Locale.ROOT)).orElse(null);
        if (connection == null) {
            return new ConnectionStatus(false, false, null, null, null, List.of(), List.of(), List.of(), null, null, false);
        }
        List<MetaConnectedAsset> currentAssets = assets.findAllByConnectionIdOrderByAssetTypeAscNameAsc(connection.getId());
        List<PageInfo> pages = currentAssets.stream()
                .filter(asset -> "page".equals(asset.getAssetType()))
                .map(asset -> new PageInfo(asset.getExternalId(), asset.getName(), asset.getCategory()))
                .toList();
        List<AdAccountInfo> adAccounts = currentAssets.stream()
                .filter(asset -> "ad_account".equals(asset.getAssetType()))
                .map(asset -> new AdAccountInfo(asset.getExternalId(), asset.getName(), asset.getAccountId(),
                        asset.getAccountStatus(), asset.getCurrency()))
                .toList();
        List<InstagramAccountInfo> instagramAccounts = currentAssets.stream()
                .filter(asset -> "instagram".equals(asset.getAssetType()))
                .map(asset -> new InstagramAccountInfo(
                        asset.getExternalId(),
                        asset.getName(),
                        asset.getAccountId(),
                        asset.getCategory()))
                .toList();
        Instant now = Instant.now();
        boolean expired = isExpired(connection.getTokenExpiresAt(), now)
                || isExpired(connection.getDataAccessExpiresAt(), now);
        return new ConnectionStatus(
                true,
                expired,
                connection.getMetaUserName(),
                connection.getConnectedAt(),
                connection.getTokenExpiresAt(),
                pages,
                adAccounts,
                instagramAccounts,
                connection.getClientId() == null ? null : connection.getClientId().toString(),
                connection.getMetaAppId(),
                connection.getClientId() != null);
    }

    @Transactional
    public void disconnect(String ownerEmail) {
        connections.findByOwnerEmail(ownerEmail.toLowerCase(Locale.ROOT)).ifPresent(connection -> {
            assets.deleteAllByConnectionId(connection.getId());
            connections.delete(connection);
        });
    }

    private static boolean isExpired(Instant value, Instant now) {
        return value != null && !value.isAfter(now);
    }
}
