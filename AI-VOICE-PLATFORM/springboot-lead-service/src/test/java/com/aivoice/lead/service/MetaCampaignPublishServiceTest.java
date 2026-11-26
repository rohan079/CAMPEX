package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.dto.Dtos.MetaCampaignPublishRequest;
import com.aivoice.lead.service.MetaConnectionStore.AdAccountInfo;
import com.aivoice.lead.service.MetaConnectionStore.ConnectionStatus;
import com.aivoice.lead.service.MetaConnectionStore.PageInfo;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetaCampaignPublishServiceTest {

    @Test
    void refusesToCreateCampaignWithoutExplicitConfirmation() {
        MetaOAuthService meta = mock(MetaOAuthService.class);
        MetaConnectionStore connections = mock(MetaConnectionStore.class);
        VoiceServiceClient voice = mock(VoiceServiceClient.class);
        MetaCampaignPublishService service = new MetaCampaignPublishService(meta, connections, voice);

        assertThrows(IllegalArgumentException.class, () -> service.publish("user@example.test", request(false, "act_1")));
        verify(meta, never()).isConfigured();
        verify(voice, never()).publishMetaCampaign(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesAdAccountsNotOwnedByTheAuthenticatedMetaConnection() {
        MetaOAuthService meta = mock(MetaOAuthService.class);
        MetaConnectionStore connections = mock(MetaConnectionStore.class);
        VoiceServiceClient voice = mock(VoiceServiceClient.class);
        MetaCampaignPublishService service = new MetaCampaignPublishService(meta, connections, voice);
        when(meta.isConfigured()).thenReturn(true);
        when(connections.status("user@example.test")).thenReturn(new ConnectionStatus(
                true,
                false,
                "Meta user",
                Instant.now(),
                null,
                List.of(new PageInfo("page-1", "Page", "Category")),
                List.of(new AdAccountInfo("act_1", "Account", "1", 1, "INR")),
                List.of(),
                null,
                "app-id",
                false));
        when(connections.userAccessToken("user@example.test")).thenReturn("server-only-token");

        assertThrows(IllegalArgumentException.class,
                () -> service.publish("user@example.test", request(true, "act_999")));
        verify(voice, never()).publishMetaCampaign(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static MetaCampaignPublishRequest request(boolean confirmed, String adAccountId) {
        return new MetaCampaignPublishRequest(
                "Spring homes", "leads", List.of("facebook"), "Vijayawada", 18, 65,
                "New homes", "Verified listing details", "Learn more", adAccountId, "page-1", 50_000, confirmed,
                null, null);
    }
}
