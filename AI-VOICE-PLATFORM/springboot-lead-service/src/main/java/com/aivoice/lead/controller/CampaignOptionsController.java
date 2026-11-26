package com.aivoice.lead.controller;

import com.aivoice.lead.service.performance.CampaignOptionsService;
import com.aivoice.lead.service.performance.CampaignOptionsService.CampaignOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CampaignOptionsController {

    private final CampaignOptionsService options;

    public CampaignOptionsController(CampaignOptionsService options) {
        this.options = options;
    }

    /** Campaigns to filter Performance AI by, including ones with no delivery yet. */
    @GetMapping("/api/clients/{clientId}/performance/campaign-options")
    public CampaignOptions campaignOptions(@PathVariable String clientId) {
        return options.list(Ids.uuid(clientId, "Client"));
    }
}
