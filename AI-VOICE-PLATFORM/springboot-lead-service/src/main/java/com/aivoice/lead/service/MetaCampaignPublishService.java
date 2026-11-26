package com.aivoice.lead.service;

import com.aivoice.lead.dto.Dtos.MetaCampaignPublishRequest;
import com.aivoice.lead.dto.Dtos.MetaCampaignPublishResponse;
import com.aivoice.lead.service.MetaConnectionStore.AdAccountInfo;
import com.aivoice.lead.service.MetaConnectionStore.ConnectionStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/** Validates ownership and sends explicitly confirmed campaign creation through FastMCP. */
@Service
public class MetaCampaignPublishService {

    private final MetaOAuthService metaOAuth;
    private final MetaConnectionStore store;
    private final VoiceServiceClient voice;

    public MetaCampaignPublishService(MetaOAuthService metaOAuth, MetaConnectionStore store, VoiceServiceClient voice) {
        this.metaOAuth = metaOAuth;
        this.store = store;
        this.voice = voice;
    }

    public MetaCampaignPublishResponse publish(String ownerEmail, MetaCampaignPublishRequest request) {
        if (!request.confirmed()) {
            throw new IllegalArgumentException("Confirm campaign creation before publishing");
        }
        if (!metaOAuth.isConfigured()) {
            throw new IllegalStateException("Meta OAuth is not fully configured on the server");
        }
        if (request.ageMax() < request.ageMin()) {
            throw new IllegalArgumentException("Maximum age must be greater than or equal to minimum age");
        }

        ConnectionStatus status = store.status(ownerEmail);
        if (!status.connected() || status.expired()) {
            throw new IllegalArgumentException("Connect Meta Business under Channels before publishing");
        }
        String accessToken = store.userAccessToken(ownerEmail);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Meta access token is unavailable; reconnect Meta Business");
        }

        AdAccountInfo adAccount = resolveAdAccount(status.adAccounts(), request.adAccountId());
        MetaCampaignPublishRequest ownedRequest = new MetaCampaignPublishRequest(
                request.campaignName(),
                request.objective(),
                request.platforms(),
                request.location(),
                request.ageMin(),
                request.ageMax(),
                request.headline(),
                request.body(),
                request.cta(),
                adAccount.id(),
                request.pageId(),
                request.dailyBudgetMinor(),
                true,
                request.geoKey(),
                request.geoType());
        MetaCampaignPublishResponse response = voice.publishMetaCampaign(ownedRequest, accessToken);
        return response;
    }

    private static AdAccountInfo resolveAdAccount(List<AdAccountInfo> accounts, String requestedId) {
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalArgumentException("No Meta ad accounts are connected. Reconnect Meta Business and grant ad account access.");
        }
        if (requestedId != null && !requestedId.isBlank()) {
            return accounts.stream()
                    .filter(account -> requestedId.equals(account.id()) || requestedId.equals(account.accountId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected ad account is not available on this Meta connection"));
        }
        return accounts.getFirst();
    }

}
