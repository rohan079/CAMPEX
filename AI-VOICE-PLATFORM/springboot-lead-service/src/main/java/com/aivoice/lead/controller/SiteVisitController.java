package com.aivoice.lead.controller;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.SiteVisitDtos.CreateSiteVisit;
import com.aivoice.lead.dto.SiteVisitDtos.RescheduleRequest;
import com.aivoice.lead.dto.SiteVisitDtos.SiteVisitDto;
import com.aivoice.lead.dto.SiteVisitDtos.SiteVisitPage;
import com.aivoice.lead.dto.SiteVisitDtos.SiteVisitStatusUpdate;
import com.aivoice.lead.service.crm.SiteVisitService;
import com.aivoice.lead.service.crm.SiteVisitService.VisitFilter;
import jakarta.validation.Valid;
import java.time.ZoneId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SiteVisitController {

    private final SiteVisitService visits;
    private final ZoneId zone;

    public SiteVisitController(SiteVisitService visits, AppProperties props) {
        this.visits = visits;
        this.zone = props.zone();
    }

    @PostMapping("/api/leads/{leadId}/site-visits")
    public SiteVisitDto create(@PathVariable String leadId, @Valid @RequestBody CreateSiteVisit body) {
        return visits.create(Ids.uuid(leadId, "Lead"), body);
    }

    @GetMapping("/api/clients/{clientId}/site-visits")
    public SiteVisitPage list(@PathVariable String clientId, @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to, @RequestParam(required = false) String status,
                              @RequestParam(required = false, name = "agent_id") String agentId,
                              @RequestParam(required = false, name = "project_id") String projectId,
                              @RequestParam(defaultValue = "200") int limit,
                              @RequestParam(defaultValue = "0") long offset) {
        return visits.list(Ids.uuid(clientId, "Client"), new VisitFilter(Ids.instant(from, zone, false),
                Ids.instant(to, zone, true), status, Ids.optionalUuid(agentId, "Agent"),
                Ids.optionalUuid(projectId, "Project")), Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    @GetMapping("/api/site-visits/{visitId}")
    public SiteVisitDto get(@PathVariable String visitId) {
        return visits.get(Ids.uuid(visitId, "Site visit"));
    }

    @PatchMapping("/api/site-visits/{visitId}/status")
    public SiteVisitDto status(@PathVariable String visitId, @Valid @RequestBody SiteVisitStatusUpdate body) {
        return visits.updateStatus(Ids.uuid(visitId, "Site visit"), body);
    }

    @PostMapping("/api/site-visits/{visitId}/reschedule")
    public SiteVisitDto reschedule(@PathVariable String visitId, @Valid @RequestBody RescheduleRequest body) {
        return visits.reschedule(Ids.uuid(visitId, "Site visit"), body);
    }
}
