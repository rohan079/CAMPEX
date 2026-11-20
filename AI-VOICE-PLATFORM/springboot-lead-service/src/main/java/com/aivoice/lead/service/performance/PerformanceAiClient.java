package com.aivoice.lead.service.performance;

import com.aivoice.lead.config.AppProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the FastAPI performance-intelligence endpoints. Only computed metrics and findings are sent — never Meta
 * tokens, provider keys or personal data. AI output is optional: callers fall back to deterministic text.
 */
@Component
public class PerformanceAiClient {

    private static final Logger log = LoggerFactory.getLogger(PerformanceAiClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) {
            super(message);
        }
    }

    private final RestClient client;

    @Autowired
    public PerformanceAiClient(AppProperties props, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.client = builder.clone().requestFactory(factory).baseUrl(props.voiceService().baseUrl())
                .defaultHeader("X-Service-Key", props.internalServiceKey() == null ? "" : props.internalServiceKey())
                .build();
    }

    PerformanceAiClient(RestClient client) {
        this.client = client;
    }

    /** Explanations keyed by item id; empty when AI is unavailable. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> explain(String task, Map<String, Object> context, List<Map<String, Object>> items) {
        try {
            return Optional.ofNullable(post("/api/performance/explain", Map.of("task", task, "context", context, "items", items)));
        } catch (AiUnavailableException e) {
            log.info("Performance AI explanations skipped: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Map<String, Object> ask(String question, Map<String, Object> context) {
        return post("/api/performance/ask", Map.of("question", question, "context", context));
    }

    public Map<String, Object> creativeSuggestions(Map<String, Object> body) {
        return post("/api/performance/creative-suggestions", body);
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            Map<String, Object> response = client.post().uri(path).body(body).retrieve().body(MAP);
            if (response == null) {
                throw new AiUnavailableException("Empty response from the AI service");
            }
            return response;
        } catch (RestClientResponseException e) {
            throw new AiUnavailableException(e.getStatusCode().value() == 503
                    ? "No AI provider is configured for performance analysis"
                    : "The AI service returned HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new AiUnavailableException("The AI service could not be reached");
        }
    }
}
