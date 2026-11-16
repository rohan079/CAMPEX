package com.aivoice.lead.controller;

import com.aivoice.lead.dto.AnalyticsDtos.ClientAnalytics;
import com.aivoice.lead.dto.AnalyticsDtos.Comparison;
import com.aivoice.lead.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Leads, sales, calling-agent results and ad reach for one client, or for all clients when no client is given. */
    @GetMapping
    public ClientAnalytics forClient(
            @RequestParam(required = false, name = "client_id") String clientId,
            @RequestParam(defaultValue = "30") int days) {
        return analytics.forClient(LeadController.optionalId(clientId), days);
    }

    /** One row per client, for comparing them side by side. */
    @GetMapping("/clients")
    public Comparison compare(@RequestParam(defaultValue = "30") int days) {
        return analytics.compare(days);
    }
}
