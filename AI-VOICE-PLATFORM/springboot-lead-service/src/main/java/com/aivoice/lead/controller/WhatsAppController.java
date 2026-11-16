package com.aivoice.lead.controller;

import com.aivoice.lead.service.LeadService;
import com.aivoice.lead.service.LeadService.InboundResult;
import com.aivoice.lead.service.PhoneNumbers;
import com.aivoice.lead.service.WhatsAppService;
import com.aivoice.lead.service.WhatsAppService.InboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Meta's WhatsApp Cloud API webhook. */
@RestController
@RequestMapping("/webhooks/whatsapp")
public class WhatsAppController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppController.class);

    private final WhatsAppService whatsApp;
    private final LeadService leads;
    private final ObjectMapper mapper;

    public WhatsAppController(WhatsAppService whatsApp, LeadService leads, ObjectMapper mapper) {
        this.whatsApp = whatsApp;
        this.leads = leads;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String answer = whatsApp.verifyChallenge(mode, token, challenge);
        if (answer == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
        }
        return ResponseEntity.ok(answer);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) throws IOException {
        if (!whatsApp.verifySignature(body, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("detail", "Invalid signature"));
        }
        JsonNode payload = mapper.readTree(body);
        int handled = 0;
        for (InboundMessage message : whatsApp.parse(payload)) {
            InboundResult result;
            try {
                result = leads.processInbound(message);
            } catch (DataIntegrityViolationException e) {
                continue; // a retry of a message another request is already processing
            }
            handled++;
            reply(message, result);
        }
        return ResponseEntity.ok(Map.of("status", "ok", "handled", handled));
    }

    /** A failed reply must not turn into a 5xx: Meta would retry and the message is already stored. */
    private void reply(InboundMessage message, InboundResult result) {
        if (result.replyText() == null) {
            return;
        }
        try {
            whatsApp.sendText(PhoneNumbers.forWhatsApp(message.sender()), result.replyText());
        } catch (RuntimeException e) {
            log.error("Could not send WhatsApp reply for message {} ({})", message.messageId(), result.outcome(), e);
        }
    }
}
