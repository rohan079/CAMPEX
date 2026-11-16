package com.aivoice.lead.service;

import com.aivoice.lead.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Everything that talks to Meta: webhook verification, parsing, and sending text. */
@Service
public class WhatsAppService {

    public record InboundMessage(String messageId, String sender, String text, String name,
                                 Map<String, Object> referral) {
    }

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final RestClient client;

    public WhatsAppService(AppProperties props, ObjectMapper mapper, RestClient.Builder builder) {
        this.props = props;
        this.mapper = mapper;
        this.client = builder.build();
    }

    /** GET handshake: returns the challenge when the token matches, otherwise null. */
    public String verifyChallenge(String mode, String token, String challenge) {
        String expected = props.whatsapp().verifyToken();
        if ("subscribe".equals(mode) && expected != null && !expected.isBlank() && token != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return challenge;
        }
        return null;
    }

    /** Checks X-Hub-Signature-256 over the raw request body. */
    public boolean verifySignature(byte[] rawBody, String header) {
        String secret = props.whatsapp().appSecret();
        if (secret == null || secret.isBlank()) {
            return props.isDevelopment();
        }
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    header.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Flattens Meta's entry/changes/value nesting into the text messages we act on. */
    public List<InboundMessage> parse(JsonNode payload) {
        List<InboundMessage> result = new ArrayList<>();
        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                Map<String, String> names = new HashMap<>();
                for (JsonNode contact : value.path("contacts")) {
                    names.put(contact.path("wa_id").asText(""), contact.path("profile").path("name").asText(null));
                }
                for (JsonNode message : value.path("messages")) {
                    String id = message.path("id").asText("");
                    String from = message.path("from").asText("");
                    if (id.isEmpty() || from.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> referral = message.has("referral") && message.get("referral").isObject()
                            ? mapper.convertValue(message.get("referral"), new com.fasterxml.jackson.core.type.TypeReference<>() { })
                            : null;
                    result.add(new InboundMessage(id, from, textOf(message), names.get(from), referral));
                }
            }
        }
        return result;
    }

    private String textOf(JsonNode message) {
        String type = message.path("type").asText("");
        return switch (type) {
            case "text" -> message.path("text").path("body").asText("");
            case "button" -> message.path("button").path("text").asText("");
            case "interactive" -> {
                JsonNode interactive = message.path("interactive");
                JsonNode reply = interactive.has("button_reply") ? interactive.get("button_reply") : interactive.path("list_reply");
                yield reply.path("title").asText("");
            }
            default -> "";
        };
    }

    public void sendText(String toDigits, String body) {
        AppProperties.WhatsApp config = props.whatsapp();
        if (isBlank(config.accessToken()) || isBlank(config.phoneNumberId())) {
            throw new IllegalStateException("WhatsApp access token and phone number id are not configured");
        }
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toDigits,
                "type", "text",
                "text", Map.of("body", body));
        client.post()
                .uri(config.messagesUrl())
                .header("Authorization", "Bearer " + config.accessToken())
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
