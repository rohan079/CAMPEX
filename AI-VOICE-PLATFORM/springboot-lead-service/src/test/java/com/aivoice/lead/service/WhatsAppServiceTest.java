package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.service.WhatsAppService.InboundMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WhatsAppServiceTest {

    private static final String SECRET = "app-secret";

    private final ObjectMapper mapper = new ObjectMapper();

    private WhatsAppService service(String environment) {
        AppProperties props = new AppProperties(environment, "Acme", "", "Asia/Kolkata", 7, 3, "", "", "", "",
                new AppProperties.WhatsApp("verify-me", SECRET, "token", "123", "v1", "http://localhost"),
                "http://localhost:8000", 5, 1);
        return new WhatsAppService(props, mapper, RestClient.builder());
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void acceptsOnlyAValidSignature() throws Exception {
        byte[] body = "{\"entry\":[]}".getBytes(StandardCharsets.UTF_8);
        WhatsAppService service = service("production");
        assertTrue(service.verifySignature(body, sign(body)));
        assertFalse(service.verifySignature(body, "sha256=deadbeef"));
        assertFalse(service.verifySignature(body, null));
        assertFalse(service.verifySignature("{\"entry\":[1]}".getBytes(StandardCharsets.UTF_8), sign(body)));
    }

    @Test
    void challengeNeedsTheVerifyToken() {
        WhatsAppService service = service("production");
        assertEquals("12345", service.verifyChallenge("subscribe", "verify-me", "12345"));
        assertNull(service.verifyChallenge("subscribe", "wrong", "12345"));
        assertNull(service.verifyChallenge("other", "verify-me", "12345"));
    }

    @Test
    void parsesTextMessagesNamesAndAdReferrals() throws Exception {
        String json = """
                {"entry":[{"changes":[{"value":{
                  "contacts":[{"wa_id":"919800000001","profile":{"name":"Ravi"}}],
                  "messages":[{"id":"wamid.1","from":"919800000001","type":"text","text":{"body":"Hi"},
                    "referral":{"source_type":"ad","source_id":"ad-9","headline":"Sale","body":"20% off"}}]
                }}]}]}
                """;
        List<InboundMessage> messages = service("production").parse(mapper.readTree(json));
        assertEquals(1, messages.size());
        InboundMessage message = messages.get(0);
        assertEquals("wamid.1", message.messageId());
        assertEquals("Ravi", message.name());
        assertEquals("Hi", message.text());
        assertEquals("Sale", message.referral().get("headline"));
    }

    @Test
    void ignoresPayloadsWithoutMessages() throws Exception {
        String statusOnly = "{\"entry\":[{\"changes\":[{\"value\":{\"statuses\":[{\"id\":\"x\"}]}}]}]}";
        assertTrue(service("production").parse(mapper.readTree(statusOnly)).isEmpty());
    }
}
