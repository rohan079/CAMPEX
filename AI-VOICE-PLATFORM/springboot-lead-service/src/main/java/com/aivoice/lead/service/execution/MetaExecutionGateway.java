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
                          String primaryText, String cta, String pageId, String geoKey, String geoType) {
    }

    record PublishResult(String campaignId, String adsetId, String adId, String status, String warning) {
    }

    record FoundCampaign(String id, String name, String status, String effectiveStatus, List<String> adsetIds) {
    }

    record CampaignState(String status, String effectiveStatus) {
    }

    PublishResult publish(String accessToken, PublishCommand command);

    /** Read-only: places Meta can target for a search text. */
    List<com.aivoice.lead.dto.CampaignExecutionDtos.MetaLocation> searchLocations(String accessToken, String query);

    List<FoundCampaign> findByNameFragment(String accessToken, String adAccountId, String fragment);

    CampaignState status(String accessToken, String adAccountId, String campaignId);

    /** Read-only: status and effective status of the campaigns in one ad account (delivery-issue rules). */
    List<com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus> campaignStatuses(String accessToken, String adAccountId);

    /** Read-only state of a campaign, ad set or ad: id, name, account_id, status, effective_status, budgets. */
    java.util.Map<String, Object> objectState(String accessToken, String level, String objectId);

    /** Applies one approved status and/or daily budget change. Idempotent: it sets values, it does not add to them. */
    java.util.Map<String, Object> applyChange(String accessToken, String level, String objectId, String status,
                                             Long dailyBudgetMinor);

    record CreativeSpec(String adAccountId, String pageId, String kind, String name, String linkUrl, String message,
                        String headline, String description, String cta, String imageHash, String videoId) {
    }

    String uploadImage(String accessToken, String adAccountId, String name, byte[] data);

    String uploadVideo(String accessToken, String adAccountId, String name, java.nio.file.Path file, String mime);

    /** Meta's processing state for an uploaded video: "ready", "processing" or "error". */
    String videoStatus(String accessToken, String videoId);

    String createCreative(String accessToken, CreativeSpec spec);

    /** IDs of ads in the ad set whose name contains {@code fragment} (reconciliation before creating). */
    List<String> findAds(String accessToken, String adsetId, String fragment);

    /** Creates a PAUSED ad. */
    String createAd(String accessToken, String adAccountId, String adsetId, String creativeId, String name);

    class GatewayException extends RuntimeException {
        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
