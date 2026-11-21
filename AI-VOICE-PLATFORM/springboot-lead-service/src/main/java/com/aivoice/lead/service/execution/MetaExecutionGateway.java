package com.aivoice.lead.service.execution;

import java.util.List;

/**
 * The only path from the execution engine to Meta. Implementations must never log or return the access token.
 * Every method may throw {@link GatewayException}; for {@link #publish} that means "outcome unknown" and the caller
 * reconciles before deciding anything.
 */
public interface MetaExecutionGateway {

    record PublishCommand(String adAccountId, String campaignName, String objective, List<String> platforms,
                          String location, int ageMin, int ageMax, long dailyBudgetMinor, String headline,
                          String primaryText, String cta, String pageId) {
    }

    record PublishResult(String campaignId, String adsetId, String adId, String status, String warning) {
    }

    record FoundCampaign(String id, String name, String status, String effectiveStatus, List<String> adsetIds) {
    }

    record CampaignState(String status, String effectiveStatus) {
    }

    PublishResult publish(String accessToken, PublishCommand command);

    List<FoundCampaign> findByNameFragment(String accessToken, String adAccountId, String fragment);

    CampaignState status(String accessToken, String adAccountId, String campaignId);

    class GatewayException extends RuntimeException {
        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
