package com.aivoice.lead.controller;

import com.aivoice.lead.dto.Dtos.MetaCampaignPublishRequest;
import com.aivoice.lead.dto.Dtos.MetaCampaignPublishResponse;
import com.aivoice.lead.service.DashboardAuthService;
import com.aivoice.lead.service.MetaCampaignPublishService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaCampaignController {

    private final DashboardAuthService dashboardAuth;
    private final MetaCampaignPublishService publisher;

    public MetaCampaignController(DashboardAuthService dashboardAuth, MetaCampaignPublishService publisher) {
        this.dashboardAuth = dashboardAuth;
        this.publisher = publisher;
    }

    @PostMapping("/api/meta/campaigns/publish")
    public MetaCampaignPublishResponse publish(
            jakarta.servlet.http.HttpServletRequest request,
            @Valid @RequestBody MetaCampaignPublishRequest body) {
        return publisher.publish(dashboardAuth.requireOwnerEmail(request), body);
    }
}
