package com.aivoice.lead.controller;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.MeasurementDtos.KpiReport;
import com.aivoice.lead.service.measurement.MeasurementService;
import com.aivoice.lead.service.measurement.MeasurementService.KpiFilter;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Measurement KPIs with explicit provenance per metric. */
@RestController
public class MeasurementController {

    private final MeasurementService measurement;
    private final ZoneId zone;

    public MeasurementController(MeasurementService measurement, AppProperties props) {
        this.measurement = measurement;
        this.zone = props.zone();
    }

    @GetMapping("/api/clients/{clientId}/measurement/kpis")
    public KpiReport kpis(@PathVariable String clientId, @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(required = false, name = "campaign_id") String campaignId,
                          @RequestParam(required = false, name = "agent_id") String agentId,
                          @RequestParam(required = false, name = "project_id") String projectId,
                          @RequestParam(required = false, name = "ad_account_id") String adAccountId) {
        Instant[] range = AttributionController.Range.of(from, to, zone);
        return measurement.kpis(Ids.uuid(clientId, "Client"), range[0], range[1], new KpiFilter(
                campaignId == null || campaignId.isBlank() ? null : campaignId.trim(),
                Ids.optionalUuid(agentId, "Agent"), Ids.optionalUuid(projectId, "Project"), adAccountId));
    }
}
