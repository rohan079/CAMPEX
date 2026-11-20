package com.aivoice.lead.controller;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.CrmDtos.ActivityDto;
import com.aivoice.lead.dto.CrmDtos.ActivityRequest;
import com.aivoice.lead.dto.CrmDtos.AdAccountMapping;
import com.aivoice.lead.dto.CrmDtos.AgentDto;
import com.aivoice.lead.dto.CrmDtos.AgentRequest;
import com.aivoice.lead.dto.CrmDtos.AssignRequest;
import com.aivoice.lead.dto.CrmDtos.CrmLeadDetail;
import com.aivoice.lead.dto.CrmDtos.FollowUpDto;
import com.aivoice.lead.dto.CrmDtos.FollowUpRequest;
import com.aivoice.lead.dto.CrmDtos.FollowUpUpdate;
import com.aivoice.lead.dto.CrmDtos.LeadTrackingConsentRequest;
import com.aivoice.lead.dto.CrmDtos.ManualLeadRequest;
import com.aivoice.lead.dto.CrmDtos.MemberDto;
import com.aivoice.lead.dto.CrmDtos.MemberRequest;
import com.aivoice.lead.dto.CrmDtos.PerformanceReport;
import com.aivoice.lead.dto.CrmDtos.Pipeline;
import com.aivoice.lead.dto.CrmDtos.ProjectDto;
import com.aivoice.lead.dto.CrmDtos.ProjectRequest;
import com.aivoice.lead.dto.CrmDtos.QualificationDto;
import com.aivoice.lead.dto.CrmDtos.QualificationRequest;
import com.aivoice.lead.dto.CrmDtos.ScoringConfig;
import com.aivoice.lead.dto.CrmDtos.StatusChangeRequest;
import com.aivoice.lead.service.crm.CrmService;
import com.aivoice.lead.service.crm.CrmService.PipelineFilter;
import com.aivoice.lead.service.crm.CrmSetupService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CRM pipeline, lead operations and per-client CRM setup. */
@RestController
public class CrmController {

    private final CrmService crm;
    private final CrmSetupService setup;
    private final ZoneId zone;

    public CrmController(CrmService crm, CrmSetupService setup, AppProperties props) {
        this.crm = crm;
        this.setup = setup;
        this.zone = props.zone();
    }

    // ---------------------------------------------------------------- client scope

    @GetMapping("/api/clients/{clientId}/crm/pipeline")
    public Pipeline pipeline(@PathVariable String clientId, @RequestParam(required = false) String q,
                             @RequestParam(required = false, name = "agent_id") String agentId,
                             @RequestParam(required = false, name = "project_id") String projectId,
                             @RequestParam(required = false) String source,
                             @RequestParam(required = false, name = "campaign_id") String campaignId,
                             @RequestParam(defaultValue = "50", name = "per_column") int perColumn) {
        return crm.pipeline(Ids.uuid(clientId, "Client"), new PipelineFilter(q, Ids.optionalUuid(agentId, "Agent"),
                Ids.optionalUuid(projectId, "Project"), source, campaignId), Ids.limit(perColumn));
    }

    @PostMapping("/api/clients/{clientId}/crm/leads")
    public CrmLeadDetail createLead(@PathVariable String clientId, @Valid @RequestBody ManualLeadRequest body) {
        return crm.createManualLead(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/clients/{clientId}/crm/agents")
    public List<AgentDto> agents(@PathVariable String clientId) {
        return setup.listAgents(Ids.uuid(clientId, "Client"));
    }

    @PostMapping("/api/clients/{clientId}/crm/agents")
    public AgentDto createAgent(@PathVariable String clientId, @Valid @RequestBody AgentRequest body) {
        return setup.createAgent(Ids.uuid(clientId, "Client"), body);
    }

    @PutMapping("/api/clients/{clientId}/crm/agents/{agentId}")
    public AgentDto updateAgent(@PathVariable String clientId, @PathVariable String agentId,
                                @Valid @RequestBody AgentRequest body) {
        return setup.updateAgent(Ids.uuid(clientId, "Client"), Ids.uuid(agentId, "Agent"), body);
    }

    @GetMapping("/api/clients/{clientId}/crm/performance")
    public PerformanceReport performance(@PathVariable String clientId, @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        Instant[] range = AttributionController.Range.of(from, to, zone);
        return crm.performance(Ids.uuid(clientId, "Client"), range[0], range[1]);
    }

    @GetMapping("/api/clients/{clientId}/crm/scoring")
    public ScoringConfig scoring(@PathVariable String clientId) {
        return setup.getScoring(Ids.uuid(clientId, "Client"));
    }

    @PutMapping("/api/clients/{clientId}/crm/scoring")
    public ScoringConfig updateScoring(@PathVariable String clientId, @Valid @RequestBody ScoringConfig body) {
        return setup.updateScoring(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/clients/{clientId}/projects")
    public List<ProjectDto> projects(@PathVariable String clientId) {
        return setup.listProjects(Ids.uuid(clientId, "Client"));
    }

    @PostMapping("/api/clients/{clientId}/projects")
    public ProjectDto createProject(@PathVariable String clientId, @Valid @RequestBody ProjectRequest body) {
        return setup.createProject(Ids.uuid(clientId, "Client"), body);
    }

    @PutMapping("/api/clients/{clientId}/projects/{projectId}")
    public ProjectDto updateProject(@PathVariable String clientId, @PathVariable String projectId,
                                    @Valid @RequestBody ProjectRequest body) {
        return setup.updateProject(Ids.uuid(clientId, "Client"), Ids.uuid(projectId, "Project"), body);
    }

    @GetMapping("/api/clients/{clientId}/members")
    public List<MemberDto> members(@PathVariable String clientId) {
        return setup.listMembers(Ids.uuid(clientId, "Client"));
    }

    @PostMapping("/api/clients/{clientId}/members")
    public MemberDto upsertMember(@PathVariable String clientId, @Valid @RequestBody MemberRequest body) {
        return setup.upsertMember(Ids.uuid(clientId, "Client"), body);
    }

    @DeleteMapping("/api/clients/{clientId}/members/{memberId}")
    public Map<String, String> removeMember(@PathVariable String clientId, @PathVariable String memberId) {
        setup.removeMember(Ids.uuid(clientId, "Client"), Ids.uuid(memberId, "Member"));
        return Map.of("status", "removed");
    }

    @GetMapping("/api/clients/{clientId}/measurement/ad-accounts")
    public Map<String, List<String>> adAccounts(@PathVariable String clientId) {
        return Map.of("ad_account_ids", setup.adAccounts(Ids.uuid(clientId, "Client")));
    }

    @PutMapping("/api/clients/{clientId}/measurement/ad-accounts")
    public Map<String, List<String>> replaceAdAccounts(@PathVariable String clientId,
                                                       @Valid @RequestBody AdAccountMapping body) {
        return Map.of("ad_account_ids", setup.replaceAdAccounts(Ids.uuid(clientId, "Client"), body.adAccountIds()));
    }

    // ---------------------------------------------------------------- lead scope

    @GetMapping("/api/leads/{leadId}/crm")
    public CrmLeadDetail detail(@PathVariable String leadId) {
        return crm.detail(Ids.uuid(leadId, "Lead"));
    }

    @PostMapping("/api/leads/{leadId}/assign")
    public CrmLeadDetail assign(@PathVariable String leadId, @Valid @RequestBody AssignRequest body) {
        return crm.assign(Ids.uuid(leadId, "Lead"), body);
    }

    @PatchMapping("/api/leads/{leadId}/status")
    public CrmLeadDetail status(@PathVariable String leadId, @Valid @RequestBody StatusChangeRequest body) {
        return crm.changeStatus(Ids.uuid(leadId, "Lead"), body);
    }

    @PostMapping("/api/leads/{leadId}/activities")
    public ActivityDto addActivity(@PathVariable String leadId, @Valid @RequestBody ActivityRequest body) {
        return crm.addActivity(Ids.uuid(leadId, "Lead"), body);
    }

    @GetMapping("/api/leads/{leadId}/activities")
    public List<ActivityDto> activities(@PathVariable String leadId) {
        return crm.activities(Ids.uuid(leadId, "Lead"));
    }

    @PostMapping("/api/leads/{leadId}/follow-ups")
    public FollowUpDto addFollowUp(@PathVariable String leadId, @Valid @RequestBody FollowUpRequest body) {
        return crm.addFollowUp(Ids.uuid(leadId, "Lead"), body);
    }

    @GetMapping("/api/leads/{leadId}/follow-ups")
    public List<FollowUpDto> followUps(@PathVariable String leadId) {
        return crm.followUps(Ids.uuid(leadId, "Lead"));
    }

    @PatchMapping("/api/follow-ups/{followUpId}")
    public FollowUpDto updateFollowUp(@PathVariable String followUpId, @Valid @RequestBody FollowUpUpdate body) {
        return crm.updateFollowUp(Ids.uuid(followUpId, "Follow-up"), body);
    }

    @PutMapping("/api/leads/{leadId}/qualification")
    public QualificationDto qualify(@PathVariable String leadId, @Valid @RequestBody QualificationRequest body) {
        return crm.qualify(Ids.uuid(leadId, "Lead"), body);
    }

    @PostMapping("/api/leads/{leadId}/tracking-consent")
    public CrmLeadDetail trackingConsent(@PathVariable String leadId, @Valid @RequestBody LeadTrackingConsentRequest body) {
        return crm.recordTrackingConsent(Ids.uuid(leadId, "Lead"), body);
    }
}
