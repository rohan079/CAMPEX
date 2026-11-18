package com.aivoice.lead.controller;

import com.aivoice.lead.dto.Dtos.MetaCampaignPublishRequest;
import com.aivoice.lead.dto.Dtos.MetaCampaignPublishResponse;
import com.aivoice.lead.service.DashboardAuthService;
import com.aivoice.lead.service.MetaCampaignPublishService;
import com.aivoice.lead.service.MetaConnectionStore;
import com.aivoice.lead.service.VoiceServiceClient;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MetaCampaignController {

    private final DashboardAuthService dashboardAuth;
    private final MetaCampaignPublishService publisher;
    private final MetaConnectionStore metaConnections;
    private final VoiceServiceClient voice;

    public MetaCampaignController(
            DashboardAuthService dashboardAuth,
            MetaCampaignPublishService publisher,
            MetaConnectionStore metaConnections,
            VoiceServiceClient voice) {
        this.dashboardAuth = dashboardAuth;
        this.publisher = publisher;
        this.metaConnections = metaConnections;
        this.voice = voice;
    }

    @GetMapping("/api/meta/ads-dashboard")
    public Map<String, Object> adsDashboard(
            jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(name = "ad_account_id", required = false) String adAccountId,
            @RequestParam(name = "date_preset", required = false, defaultValue = "last_14d") String datePreset) {
        String ownerEmail = dashboardAuth.requireOwnerEmail(request);
        String accessToken = metaConnections.userAccessToken(ownerEmail);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connect Meta Business under Channels first");
        }
        return voice.metaAdsDashboard(accessToken, adAccountId, datePreset);
    }

    @PostMapping("/api/meta/campaigns/publish")
    public MetaCampaignPublishResponse publish(
            jakarta.servlet.http.HttpServletRequest request,
            @Valid @RequestBody MetaCampaignPublishRequest body) {
        return publisher.publish(dashboardAuth.requireOwnerEmail(request), body);
    }
}
