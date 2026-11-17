package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.entity.MetaConnectedAsset;
import com.aivoice.lead.entity.MetaConnection;
import com.aivoice.lead.repository.MetaConnectedAssetRepository;
import com.aivoice.lead.repository.MetaConnectionRepository;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetaConnectionStoreTest {

    @Test
    void persistsEncryptedTokensButReturnsOnlySafeMetadata() {
        String owner = "admin@example.test";
        String userToken = "opaque-user-token";
        String pageToken = "opaque-page-token";
        MetaOAuthProperties properties = new MetaOAuthProperties("id", "secret",
                "https://campex.example/oauth/meta/callback", "v27.0",
                Base64.getEncoder().encodeToString(new byte[32]), "https://campex.example", List.of("ads_read"));
        MetaTokenCipher cipher = new MetaTokenCipher(properties);
        MetaConnectionRepository connections = mock(MetaConnectionRepository.class);
        MetaConnectedAssetRepository assets = mock(MetaConnectedAssetRepository.class);
        when(connections.findByOwnerEmail(owner)).thenReturn(Optional.empty());
        when(connections.save(any())).thenAnswer(invocation -> {
            MetaConnection connection = invocation.getArgument(0);
            connection.setId(UUID.randomUUID());
            return connection;
        });
        when(assets.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MetaConnectionStore store = new MetaConnectionStore(cipher, connections, assets);
        MetaGraphClient.Profile profile = new MetaGraphClient.Profile("meta-user", "Admin");
        MetaGraphClient.Token token = new MetaGraphClient.Token(
                userToken, "bearer", Instant.now().plusSeconds(3600), null);
        List<MetaGraphClient.Page> pages = List.of(new MetaGraphClient.Page("page-id", "Page", "Category", pageToken));
        List<MetaGraphClient.AdAccount> adAccounts = List.of(
                new MetaGraphClient.AdAccount("act_1", "Account", "1", 1, "INR"));

        store.save(owner, profile, token, List.of("ads_read"), pages, adAccounts);

        var connectionCaptor = org.mockito.ArgumentCaptor.forClass(MetaConnection.class);
        org.mockito.Mockito.verify(connections).save(connectionCaptor.capture());
        MetaConnection connection = connectionCaptor.getValue();
        assertEquals(userToken, cipher.decrypt(
                connection.getAccessTokenCiphertext(), connection.getAccessTokenNonce(), "user:" + owner));

        var assetsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(assets).saveAll(assetsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<MetaConnectedAsset> storedAssets = assetsCaptor.getValue();
        MetaConnectedAsset storedPage = storedAssets.stream()
                .filter(asset -> "page".equals(asset.getAssetType())).findFirst().orElseThrow();
        assertEquals(pageToken, cipher.decrypt(storedPage.getPageTokenCiphertext(), storedPage.getPageTokenNonce(),
                "page:" + owner + ":page-id"));

        when(connections.findByOwnerEmail(owner)).thenReturn(Optional.of(connection));
        when(assets.findAllByConnectionIdOrderByAssetTypeAscNameAsc(connection.getId())).thenReturn(storedAssets);
        MetaConnectionStore.ConnectionStatus status = store.status(owner);
        assertTrue(status.connected());
        assertEquals("Page", status.pages().get(0).name());
        assertEquals("1", status.adAccounts().get(0).accountId());
        assertFalse(status.toString().contains(userToken));
        assertFalse(status.toString().contains(pageToken));
    }
}
