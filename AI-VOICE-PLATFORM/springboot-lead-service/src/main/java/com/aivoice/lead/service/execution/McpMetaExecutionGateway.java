package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.Dtos.MetaCampaignPublishRequest;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.aivoice.lead.service.VoiceServiceClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Executes through the FastAPI Meta routes, which call the Meta Ads MCP tools. */
@Component
public class McpMetaExecutionGateway implements MetaExecutionGateway {

    private final VoiceServiceClient voice;

    public McpMetaExecutionGateway(VoiceServiceClient voice) {
        this.voice = voice;
    }

    @Override
    public PublishResult publish(String accessToken, PublishCommand c) {
        MetaCampaignPublishRequest body = new MetaCampaignPublishRequest(c.campaignName(), c.objective(),
                c.platforms(), c.location(), c.ageMin(), c.ageMax(), c.headline(), c.primaryText(), c.cta(),
                c.adAccountId(), c.pageId(), Math.toIntExact(c.dailyBudgetMinor()), true, c.geoKey(), c.geoType());
        Map<String, Object> r = call("/api/meta/publish", body, accessToken);
        return new PublishResult(text(r.get("campaign_id")), text(r.get("ad_set_id")), text(r.get("ad_id")),
                text(r.get("status")), text(r.get("warning")));
    }

    @Override
    public List<com.aivoice.lead.dto.CampaignExecutionDtos.MetaLocation> searchLocations(String accessToken, String query) {
        Map<String, Object> r = call("/api/meta/locations", Map.of("query", query), accessToken);
        List<com.aivoice.lead.dto.CampaignExecutionDtos.MetaLocation> out = new ArrayList<>();
        if (r.get("locations") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> m && text(m.get("key")) != null) {
                    out.add(new com.aivoice.lead.dto.CampaignExecutionDtos.MetaLocation(text(m.get("key")),
                            text(m.get("type")), text(m.get("name")), text(m.get("region")),
                            text(m.get("country_name")), text(m.get("label"))));
                }
            }
        }
        return out;
    }

    @Override
    public List<FoundCampaign> findByNameFragment(String accessToken, String adAccountId, String fragment) {
        Map<String, Object> r = call("/api/meta/campaigns/lookup",
                Map.of("ad_account_id", adAccountId, "name_contains", fragment), accessToken);
        List<FoundCampaign> out = new ArrayList<>();
        if (r.get("campaigns") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> m) {
                    List<String> adsets = new ArrayList<>();
                    if (m.get("adset_ids") instanceof List<?> ids) {
                        ids.forEach(id -> adsets.add(String.valueOf(id)));
                    }
                    out.add(new FoundCampaign(text(m.get("id")), text(m.get("name")), text(m.get("status")),
                            text(m.get("effective_status")), adsets));
                }
            }
        }
        return out;
    }

    @Override
    public CampaignState status(String accessToken, String adAccountId, String campaignId) {
        Map<String, Object> r = call("/api/meta/campaigns/status",
                Map.of("ad_account_id", adAccountId, "campaign_id", campaignId), accessToken);
        return new CampaignState(text(r.get("status")), text(r.get("effective_status")));
    }

    @Override
    public List<com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus> campaignStatuses(String accessToken, String adAccountId) {
        Map<String, Object> r = call("/api/meta/campaigns/statuses", Map.of("ad_account_id", adAccountId), accessToken);
        List<com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus> out = new ArrayList<>();
        if (r.get("campaigns") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> m) {
                    out.add(new com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus(text(m.get("id")),
                            text(m.get("name")), text(m.get("status")), text(m.get("effective_status"))));
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> objectState(String accessToken, String level, String objectId) {
        return call("/api/meta/objects/state", Map.of("level", level, "object_id", objectId), accessToken);
    }

    @Override
    public Map<String, Object> applyChange(String accessToken, String level, String objectId, String status,
                                           Long dailyBudgetMinor) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("level", level);
        body.put("object_id", objectId);
        body.put("confirmed", true);
        if (status != null) body.put("status", status);
        if (dailyBudgetMinor != null) body.put("daily_budget_minor", dailyBudgetMinor);
        return call("/api/meta/objects/apply", body, accessToken);
    }

    @Override
    public String uploadImage(String accessToken, String adAccountId, String name, byte[] data) {
        return text(call("/api/meta/ads-builder/image", Map.of("ad_account_id", adAccountId, "name", name,
                "data_base64", java.util.Base64.getEncoder().encodeToString(data)), accessToken).get("image_hash"));
    }

    @Override
    public String uploadVideo(String accessToken, String adAccountId, String name, java.nio.file.Path file, String mime) {
        try {
            return text(voice.metaVideoUpload(file, mime, name, adAccountId, accessToken).get("video_id"));
        } catch (MetaGraphException e) {
            throw new GatewayException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new GatewayException("Meta could not be reached for the video upload", e);
        }
    }

    @Override
    public String videoStatus(String accessToken, String videoId) {
        return text(call("/api/meta/ads-builder/video-status", Map.of("video_id", videoId), accessToken).get("video_status"));
    }

    @Override
    public String createCreative(String accessToken, CreativeSpec s) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ad_account_id", s.adAccountId());
        body.put("page_id", s.pageId());
        body.put("kind", s.kind());
        body.put("name", s.name());
        body.put("link_url", s.linkUrl());
        body.put("message", s.message());
        body.put("headline", s.headline());
        if (s.description() != null) body.put("description", s.description());
        body.put("cta", s.cta());
        body.put("image_hash", s.imageHash());
        if (s.videoId() != null) body.put("video_id", s.videoId());
        return text(call("/api/meta/ads-builder/creative", body, accessToken).get("creative_id"));
    }

    @Override
    public List<String> findAds(String accessToken, String adsetId, String fragment) {
        Map<String, Object> r = call("/api/meta/ads-builder/find-ads", Map.of("adset_id", adsetId, "name_contains", fragment), accessToken);
        List<String> ids = new ArrayList<>();
        if (r.get("ads") instanceof List<?> rows) {
            for (Object row : rows) if (row instanceof Map<?, ?> m && m.get("id") != null) ids.add(String.valueOf(m.get("id")));
        }
        return ids;
    }

    @Override
    public String createAd(String accessToken, String adAccountId, String adsetId, String creativeId, String name) {
        return text(call("/api/meta/ads-builder/ad", Map.of("ad_account_id", adAccountId, "adset_id", adsetId,
                "creative_id", creativeId, "name", name, "confirmed", true), accessToken).get("ad_id"));
    }

    private Map<String, Object> call(String path, Object body, String token) {
        try {
            return voice.metaExecutionCall(path, body, token);
        } catch (MetaGraphException e) {
            throw new GatewayException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new GatewayException("Meta could not be reached", e);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
