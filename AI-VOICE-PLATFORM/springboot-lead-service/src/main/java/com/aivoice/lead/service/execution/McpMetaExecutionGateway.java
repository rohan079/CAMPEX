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
                c.adAccountId(), c.pageId(), Math.toIntExact(c.dailyBudgetMinor()), true);
        Map<String, Object> r = call("/api/meta/publish", body, accessToken);
        return new PublishResult(text(r.get("campaign_id")), text(r.get("ad_set_id")), text(r.get("ad_id")),
                text(r.get("status")), text(r.get("warning")));
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
