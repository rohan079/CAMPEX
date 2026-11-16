package com.aivoice.lead.controller;

import com.aivoice.lead.dto.Dtos.CampaignAssistRequest;
import com.aivoice.lead.dto.Dtos.CampaignAssistResponse;
import com.aivoice.lead.service.Exceptions.CampaignAssistException;
import com.aivoice.lead.service.VoiceServiceClient;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Dashboard-authenticated proxy; the AI provider credential remains in FastAPI. */
@RestController
public class CampaignAssistController {

    private final VoiceServiceClient voice;

    public CampaignAssistController(VoiceServiceClient voice) {
        this.voice = voice;
    }

    @PostMapping("/api/ai/campaign-assist")
    public CampaignAssistResponse assist(@Valid @RequestBody CampaignAssistRequest request) {
        try {
            return voice.campaignAssist(request);
        } catch (CampaignAssistException exception) {
            HttpStatus status = exception.status() == 503 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "Campaign AI is unavailable", exception);
        }
    }
}
