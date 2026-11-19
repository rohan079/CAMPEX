package com.aivoice.lead.controller;

import com.aivoice.lead.dto.Dtos.CampaignAssistRequest;
import com.aivoice.lead.dto.Dtos.CampaignAssistResponse;
import com.aivoice.lead.dto.Dtos.SkillSummary;
import com.aivoice.lead.dto.Dtos.SkillUploadRequest;
import com.aivoice.lead.service.Exceptions.CampaignAssistException;
import com.aivoice.lead.service.DashboardAuthService;
import com.aivoice.lead.service.MetaConnectionStore;
import com.aivoice.lead.service.VoiceServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Dashboard-authenticated proxy; the AI provider credential remains in FastAPI. */
@RestController
public class CampaignAssistController {

    private final VoiceServiceClient voice;
    private final DashboardAuthService dashboardAuth;
    private final MetaConnectionStore metaConnections;

    public CampaignAssistController(
            VoiceServiceClient voice,
            DashboardAuthService dashboardAuth,
            MetaConnectionStore metaConnections) {
        this.voice = voice;
        this.dashboardAuth = dashboardAuth;
        this.metaConnections = metaConnections;
    }

    @PostMapping("/api/ai/campaign-assist")
    public CampaignAssistResponse assist(HttpServletRequest httpRequest, @Valid @RequestBody CampaignAssistRequest request) {
        try {
            String ownerEmail = dashboardAuth.requireOwnerEmail(httpRequest);
            String accessToken = metaConnections.userAccessToken(ownerEmail);
            return voice.campaignAssist(request, accessToken, ownerEmail);
        } catch (CampaignAssistException exception) {
            HttpStatus status = exception.status() == 503 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @GetMapping("/api/ai/campaign-skills")
    public List<SkillSummary> listSkills(HttpServletRequest httpRequest) {
        String ownerEmail = dashboardAuth.requireOwnerEmail(httpRequest);
        return voice.listCampaignSkills(ownerEmail);
    }

    @PostMapping("/api/ai/campaign-skills")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillSummary uploadSkill(HttpServletRequest httpRequest, @Valid @RequestBody SkillUploadRequest request) {
        String ownerEmail = dashboardAuth.requireOwnerEmail(httpRequest);
        return voice.uploadCampaignSkill(request, ownerEmail);
    }

    @DeleteMapping("/api/ai/campaign-skills/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(HttpServletRequest httpRequest, @PathVariable String skillId) {
        String ownerEmail = dashboardAuth.requireOwnerEmail(httpRequest);
        voice.deleteCampaignSkill(skillId, ownerEmail);
    }
}
