package com.aivoice.lead.service;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.Dtos.CampaignAssistRequest;
import com.aivoice.lead.dto.Dtos.CampaignAssistResponse;
import com.aivoice.lead.dto.Dtos.StartCallRequest;
import com.aivoice.lead.dto.Dtos.StartCallResponse;
import com.aivoice.lead.service.Exceptions.CampaignAssistException;
import com.aivoice.lead.service.Exceptions.VoiceServiceException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

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
    public CampaignAssistResponse campaignAssist(CampaignAssistRequest request) {
        try {
            CampaignAssistResponse response = campaignClient.post().uri("/api/ads/assist").body(request)
                    .retrieve().body(CampaignAssistResponse.class);
            if (response == null || response.reply() == null || response.reply().isBlank()) {
                throw new CampaignAssistException("Campaign AI returned no copy", 502, null);
            }
            return response;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value() == 503 ? 503 : 502;
            throw new CampaignAssistException("Campaign AI is unavailable", status, e);
        } catch (ResourceAccessException e) {
            throw new CampaignAssistException("Campaign AI service did not respond", 502, e);
        }
    }

    private static String describe(HttpStatusCode status) {
        return "HTTP " + status.value();
    }
}
