package com.aivoice.lead.service;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.Dtos.CampaignAssistRequest;
import com.aivoice.lead.dto.Dtos.CampaignAssistResponse;
import com.aivoice.lead.dto.Dtos.MetaCampaignPublishRequest;
import com.aivoice.lead.dto.Dtos.MetaCampaignPublishResponse;
import com.aivoice.lead.dto.Dtos.SkillSummary;
import com.aivoice.lead.dto.Dtos.SkillUploadRequest;
import com.aivoice.lead.dto.Dtos.StartCallRequest;
import com.aivoice.lead.dto.Dtos.StartCallResponse;
import com.aivoice.lead.service.Exceptions.CampaignAssistException;
import com.aivoice.lead.service.Exceptions.VoiceServiceException;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** The only door from this service into the FastAPI voice service. */
@Component
public class VoiceServiceClient {

    private final RestClient client;
    private final RestClient campaignClient;

    public VoiceServiceClient(AppProperties props, RestClient.Builder builder) {
        Duration timeout = Duration.ofSeconds(props.voiceService().requestTimeoutSeconds());
        // HTTP/1.1 on purpose: the JDK client otherwise sends an h2c upgrade that uvicorn answers with 400.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(timeout);
        this.client = builder
                .baseUrl(props.voiceService().baseUrl())
                .defaultHeader("X-Service-Key", props.internalServiceKey() == null ? "" : props.internalServiceKey())
                .requestFactory(factory)
                .build();

        JdkClientHttpRequestFactory campaignFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        campaignFactory.setReadTimeout(Duration.ofSeconds(90));
        this.campaignClient = builder.clone()
                .baseUrl(props.voiceService().baseUrl())
                .defaultHeader("X-Service-Key", props.internalServiceKey() == null ? "" : props.internalServiceKey())
                .requestFactory(campaignFactory)
                .build();
    }

    /**
     * Starts an outbound call. A timeout or dropped connection is reported as ambiguous
     * because the voice service may already have asked the carrier to dial.
     */
    public StartCallResponse startCall(StartCallRequest request) {
        try {
            StartCallResponse response = client.post().uri("/api/calls").body(request).retrieve().body(StartCallResponse.class);
            if (response == null || response.sessionId() == null) {
                throw new VoiceServiceException("Voice service returned no session id", true, null);
            }
            return response;
        } catch (HttpStatusCodeException e) {
            // 504 means the voice service itself timed out talking to the provider.
            boolean ambiguous = e.getStatusCode().value() == 504;
            boolean capacityDeferred = e.getStatusCode().value() == 429;
            throw new VoiceServiceException(
                    "Voice service rejected the call: " + describe(e.getStatusCode()),
                    ambiguous,
                    capacityDeferred,
                    e);
        } catch (ResourceAccessException e) {
            throw new VoiceServiceException("Voice service did not answer in time", true, e);
        }
    }

    public void hangup(String sessionId) {
        client.post().uri("/api/calls/{sessionId}/hangup", sessionId).retrieve().toBodilessEntity();
    }

    /** Proxy campaign copy assistance so the browser never receives the model provider key. */
    public CampaignAssistResponse campaignAssist(CampaignAssistRequest request, String metaAccessToken, String ownerEmail) {
        try {
            CampaignAssistResponse response = campaignClient.post().uri("/api/ads/assist")
                    .headers(headers -> {
                        if (metaAccessToken != null && !metaAccessToken.isBlank()) {
                            headers.set("X-Meta-Access-Token", metaAccessToken);
                        }
                        if (ownerEmail != null && !ownerEmail.isBlank()) {
                            headers.set("X-Owner-Email", ownerEmail);
                        }
                    })
                    .body(request)
                    .retrieve().body(CampaignAssistResponse.class);
            if (response == null || response.reply() == null || response.reply().isBlank()) {
                throw new CampaignAssistException("Campaign AI returned no copy", 502, null);
            }
            return response;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value() == 503 ? 503 : 502;
            throw new CampaignAssistException(extractUpstreamDetail(e, "Campaign AI is unavailable"), status, e);
        } catch (ResourceAccessException e) {
            throw new CampaignAssistException(
                    "Campaign AI service did not respond. Check VOICE_SERVICE_URL and that the voice service is running.",
                    502,
                    e);
        }
    }

    public List<SkillSummary> listCampaignSkills(String ownerEmail) {
        SkillSummary[] skills = campaignClient.get().uri("/api/ads/skills")
                .headers(headers -> {
                    if (ownerEmail != null && !ownerEmail.isBlank()) {
                        headers.set("X-Owner-Email", ownerEmail);
                    }
                })
                .retrieve()
                .body(SkillSummary[].class);
        return skills == null ? List.of() : Arrays.asList(skills);
    }

    public SkillSummary uploadCampaignSkill(SkillUploadRequest request, String ownerEmail) {
        return campaignClient.post().uri("/api/ads/skills")
                .headers(headers -> headers.set("X-Owner-Email", ownerEmail))
                .body(request)
                .retrieve()
                .body(SkillSummary.class);
    }

    public void deleteCampaignSkill(String skillId, String ownerEmail) {
        campaignClient.delete().uri("/api/ads/skills/{skillId}", skillId)
                .headers(headers -> headers.set("X-Owner-Email", ownerEmail))
                .retrieve()
                .toBodilessEntity();
    }

    private static String extractUpstreamDetail(HttpStatusCodeException error, String fallback) {
        try {
            String body = error.getResponseBodyAsString();
            if (body != null && body.contains("\"detail\"")) {
                int start = body.indexOf("\"detail\"");
                int colon = body.indexOf(':', start);
                int firstQuote = body.indexOf('"', colon + 1);
                int secondQuote = body.indexOf('"', firstQuote + 1);
                if (firstQuote >= 0 && secondQuote > firstQuote) {
                    String detail = body.substring(firstQuote + 1, secondQuote).trim();
                    if (!detail.isBlank()) return detail;
                }
            }
        } catch (RuntimeException ignored) {
            // keep fallback
        }
        return fallback;
    }

    /** Aggregated Meta Ads MCP read snapshot for the dashboard UI. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> metaAdsDashboard(String metaAccessToken, String adAccountId, String datePreset) {
        try {
            String uri = UriComponentsBuilder.fromPath("/api/meta/dashboard")
                    .queryParam("date_preset", datePreset == null || datePreset.isBlank() ? "last_14d" : datePreset)
                    .queryParamIfPresent("ad_account_id",
                            java.util.Optional.ofNullable(adAccountId).filter(value -> !value.isBlank()))
                    .build(true)
                    .toUriString();
            Map<String, Object> response = campaignClient.get().uri(uri)
                    .header("X-Meta-Access-Token", metaAccessToken)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new MetaGraphException(502, -1, "mcp_empty_response", "Meta MCP dashboard returned no data");
            }
            return response;
        } catch (HttpStatusCodeException e) {
            throw new MetaGraphException(
                    e.getStatusCode().value(),
                    -1,
                    "mcp_http_response",
                    extractUpstreamDetail(e, "Could not load Meta Ads MCP dashboard"));
        } catch (ResourceAccessException e) {
            throw new MetaGraphException(502, -1, "mcp_transport_failure", "Meta MCP service did not respond");
        }
    }

    /** Calls the private FastMCP publishing tool; the user's Meta token never enters the browser or logs. */
    public MetaCampaignPublishResponse publishMetaCampaign(
            MetaCampaignPublishRequest request,
            String metaAccessToken) {
        try {
            MetaCampaignPublishResponse response = campaignClient.post().uri("/api/meta/publish")
                    .header("X-Meta-Access-Token", metaAccessToken)
                    .body(request)
                    .retrieve().body(MetaCampaignPublishResponse.class);
            if (response == null || response.campaignId() == null || response.campaignId().isBlank()) {
                throw new MetaGraphException(502, -1, "mcp_empty_response", "Meta campaign tool returned no campaign ID");
            }
            return response;
        } catch (HttpStatusCodeException e) {
            throw new MetaGraphException(
                    e.getStatusCode().value(),
                    -1,
                    "mcp_http_response",
                    "Meta campaign creation failed (HTTP " + e.getStatusCode().value()
                            + "). Check the account's permissions and campaign inputs.");
        } catch (ResourceAccessException e) {
            throw new MetaGraphException(502, -1, "mcp_transport_failure", "Meta MCP service did not respond");
        }
    }

    private static String describe(HttpStatusCode status) {
        return "HTTP " + status.value();
    }
}
