package com.aivoice.lead.controller;

import com.aivoice.lead.dto.Dtos.CallEventRequest;
import com.aivoice.lead.dto.Dtos.CallPage;
import com.aivoice.lead.dto.Dtos.CallSummaryRequest;
import com.aivoice.lead.service.CallOrchestratorService;
import com.aivoice.lead.service.LeadService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CallController {

    private final LeadService leads;
    private final CallOrchestratorService orchestrator;

    public CallController(LeadService leads, CallOrchestratorService orchestrator) {
        this.leads = leads;
        this.orchestrator = orchestrator;
    }

    /** Call history for the dashboard. */
    @GetMapping("/api/calls")
    public CallPage list(
            @RequestParam(required = false, name = "client_id") String clientId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") long offset) {
        return leads.listCalls(LeadController.optionalId(clientId), status, language, LeadController.clamp(limit), Math.max(offset, 0));
    }

    /** Called by the voice service when a call rings, connects, fails, ends, or the caller opts out. */
    @PostMapping("/internal/call-events")
    public Map<String, String> callEvent(@Valid @RequestBody CallEventRequest event) {
        orchestrator.handleEvent(event);
        return Map.of("status", "ok");
    }

    /** Stores a transcript-grounded summary from the voice service; transcript/audio are not sent here. */
    @PostMapping("/internal/call-summaries")
    public Map<String, String> callSummary(@Valid @RequestBody CallSummaryRequest summary) {
        orchestrator.saveSummary(summary.callId(), summary.summary());
        return Map.of("status", "ok");
    }
}
