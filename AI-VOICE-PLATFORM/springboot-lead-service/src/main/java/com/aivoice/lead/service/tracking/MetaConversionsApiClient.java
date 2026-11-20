package com.aivoice.lead.service.tracking;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.config.TrackingProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sends events to {@code POST /{graph-version}/{pixel-id}/events}. The access token travels in the form body (never
 * the URL), and is never logged or echoed in results.
 */
@Component
public class MetaConversionsApiClient {

    /** Graph error codes Meta documents as temporary/throttling. */
    static final Set<Integer> TRANSIENT_CODES = Set.of(1, 2, 4, 17, 32, 341, 613, 80004);

    public record Result(
            boolean success,
            boolean retryable,
            Integer httpStatus,
            Integer eventsReceived,
            String fbtraceId,
            Integer errorCode,
            Integer errorSubcode,
            String errorMessage,
            Map<String, Object> metadata) {
    }

    private final RestClient client;
    private final ObjectMapper mapper;
    private final TrackingProperties tracking;
    private final MetaOAuthProperties meta;

    @org.springframework.beans.factory.annotation.Autowired
    public MetaConversionsApiClient(RestClient.Builder builder, ObjectMapper mapper, TrackingProperties tracking,
                                    MetaOAuthProperties meta) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(tracking.capiTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(tracking.capiTimeoutSeconds()));
        this.client = builder.clone().requestFactory(factory).build();
        this.mapper = mapper;
        this.tracking = tracking;
        this.meta = meta;
    }

    /** For tests: a pre-built client (e.g. bound to MockRestServiceServer). */
    MetaConversionsApiClient(RestClient client, ObjectMapper mapper, TrackingProperties tracking, MetaOAuthProperties meta) {
        this.client = client;
        this.mapper = mapper;
        this.tracking = tracking;
        this.meta = meta;
    }

    public Result send(String metaPixelId, String accessToken, List<Map<String, Object>> events, String testEventCode) {
        String base = tracking.capiBaseUrl().replaceAll("/+$", "");
        URI uri = UriComponentsBuilder.fromUriString(base + "/" + meta.graphApiVersion() + "/" + metaPixelId + "/events")
                .build().encode().toUri();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        try {
            form.add("data", mapper.writeValueAsString(events));
        } catch (JsonProcessingException e) {
            return new Result(false, false, null, null, null, null, null, "Could not serialize events", Map.of());
        }
        form.add("access_token", accessToken);
        if (testEventCode != null && !testEventCode.isBlank()) {
            form.add("test_event_code", testEventCode);
        }
        try {
            String body = client.post().uri(uri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
            JsonNode json = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            Integer received = json.path("events_received").canConvertToInt() ? json.path("events_received").asInt() : null;
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (json.has("messages")) {
                metadata.put("messages", sanitize(json.path("messages").toString()));
            }
            boolean ok = received != null && received == events.size();
            return new Result(ok, !ok, 200, received, json.path("fbtrace_id").asText(null), null, null,
                    ok ? null : "Meta acknowledged " + received + " of " + events.size() + " events", metadata);
        } catch (RestClientResponseException e) {
            return fromError(e.getStatusCode().value(), e.getResponseBodyAsByteArray());
        } catch (RestClientException | IOException e) {
            return new Result(false, true, null, null, null, null, null,
                    "Transport failure: " + e.getClass().getSimpleName(), Map.of());
        }
    }

    Result fromError(int status, byte[] body) {
        Integer code = null;
        Integer subcode = null;
        String message = "Meta returned HTTP " + status;
        String trace = null;
        boolean transientFlag = false;
        try {
            JsonNode error = mapper.readTree(body).path("error");
            if (error.path("code").canConvertToInt()) code = error.path("code").asInt();
            if (error.path("error_subcode").canConvertToInt()) subcode = error.path("error_subcode").asInt();
            String providerMessage = error.path("error_user_msg").asText(error.path("message").asText(""));
            if (!providerMessage.isBlank()) message = providerMessage;
            trace = error.path("fbtrace_id").asText(null);
            transientFlag = error.path("is_transient").asBoolean(false);
        } catch (IOException | RuntimeException ignored) {
            // keep the generic message
        }
        boolean retryable = status == 429 || status >= 500 || transientFlag || (code != null && TRANSIENT_CODES.contains(code));
        return new Result(false, retryable, status, null, trace, code, subcode, sanitize(message), Map.of());
    }

    /** Provider text is stored for operators; strip anything shaped like a token and cap the length. */
    static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("EA[A-Za-z0-9]{20,}", "[redacted]")
                .replaceAll("(?i)access_token=[^&\\s\"]+", "access_token=[redacted]");
        return cleaned.length() > 480 ? cleaned.substring(0, 480) : cleaned;
    }
}
