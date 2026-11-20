package com.aivoice.lead.service.crm;

import com.aivoice.lead.dto.SiteVisitDtos.CreateSiteVisit;
import com.aivoice.lead.dto.SiteVisitDtos.RescheduleRequest;
import com.aivoice.lead.dto.SiteVisitDtos.SiteVisitDto;
import com.aivoice.lead.dto.SiteVisitDtos.SiteVisitPage;
import com.aivoice.lead.dto.SiteVisitDtos.SiteVisitStatusUpdate;
import com.aivoice.lead.entity.ClientProject;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadFollowUp;
import com.aivoice.lead.entity.PipelineStatus;
import com.aivoice.lead.entity.SalesAgent;
import com.aivoice.lead.entity.SiteVisit;
import com.aivoice.lead.repository.ClientProjectRepository;
import com.aivoice.lead.repository.LeadFollowUpRepository;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.OffsetPageRequest;
import com.aivoice.lead.repository.SalesAgentRepository;
import com.aivoice.lead.repository.SiteVisitRepository;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.crm.CrmService.LeadContext;
import com.aivoice.lead.service.tracking.ConversionEventService;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.Source;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Site visits: schedule, confirm, complete, cancel, no-show, reschedule, reminders. */
@Service
public class SiteVisitService {

    private static final Set<String> ACTIVE = Set.of("SCHEDULED", "CONFIRMED");

    public record VisitFilter(Instant from, Instant to, String status, UUID agentId, UUID projectId) {
    }

    private final SiteVisitRepository visits;
    private final LeadRepository leads;
    private final SalesAgentRepository agents;
    private final ClientProjectRepository projects;
    private final LeadFollowUpRepository followUps;
    private final CrmService crm;
    private final CrmSetupService setup;
    private final ConversionEventService conversions;
    private final ClientAccessService access;
    private final Clock clock;

    public SiteVisitService(SiteVisitRepository visits, LeadRepository leads, SalesAgentRepository agents,
                            ClientProjectRepository projects, LeadFollowUpRepository followUps, CrmService crm,
                            CrmSetupService setup, ConversionEventService conversions, ClientAccessService access,
                            Clock clock) {
        this.visits = visits;
        this.leads = leads;
        this.agents = agents;
        this.projects = projects;
        this.followUps = followUps;
        this.crm = crm;
        this.setup = setup;
        this.conversions = conversions;
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public SiteVisitDto create(UUID leadId, CreateSiteVisit request) {
        LeadContext ctx = crm.requireLead(leadId, Permission.OPERATE);
        Lead lead = ctx.lead();
        if (request.scheduledAt().isBefore(clock.instant().minus(Duration.ofDays(1)))) {
            throw new IllegalArgumentException("A site visit cannot be scheduled more than a day in the past");
        }
        SiteVisit visit = new SiteVisit();
        visit.setClientId(lead.getClientId());
        visit.setLeadId(lead.getId());
        visit.setProjectId(resolveProject(lead, request.projectId()));
        visit.setAgentId(resolveAgent(lead, request.agentId()));
        visit.setScheduledAt(request.scheduledAt());
        visit.setDurationMinutes(request.durationMinutes() == null ? 60 : request.durationMinutes());
        visit.setStatus("SCHEDULED");
        visit.setFollowUpNotes(blank(request.notes()));
        visit.setReminderAt(reminderAt(request.scheduledAt(), request.reminderMinutesBefore()));
        visit.setCreatedBy(ctx.actor().email());
        visit = visits.save(visit);
        afterScheduled(lead, visit, ctx.actor().email(), "Site visit scheduled");
        return toDto(visit, lead);
    }

    private void afterScheduled(Lead lead, SiteVisit visit, String actor, String text) {
        if (lead.getProjectId() == null && visit.getProjectId() != null) {
            lead.setProjectId(visit.getProjectId());
            leads.save(lead);
        }
        crm.addActivity(lead, "site_visit", text + " for " + visit.getScheduledAt(),
                Map.of("site_visit_id", visit.getId().toString()), actor, clock.instant(), visit.getAgentId());
        crm.transition(lead, PipelineStatus.SITE_VISIT_SCHEDULED, text, null, actor, false);
        conversions.recordForLead(lead, EventName.Schedule, "visit-" + visit.getId() + "-scheduled",
                Source.manual_crm, Map.of("content_name", "site_visit"));
    }

    @Transactional
    public SiteVisitDto updateStatus(UUID visitId, SiteVisitStatusUpdate request) {
        SiteVisit visit = visits.findById(visitId).orElseThrow(() -> new NotFoundException("Site visit not found"));
        LeadContext ctx = crm.requireLead(visit.getLeadId(), Permission.OPERATE);
        String target = request.status();
        if (!ACTIVE.contains(visit.getStatus())) {
            throw new ConflictException("This visit is already " + visit.getStatus());
        }
        if ("CONFIRMED".equals(target) && "CONFIRMED".equals(visit.getStatus())) {
            throw new ConflictException("This visit is already confirmed");
        }
        Instant now = clock.instant();
        Lead lead = ctx.lead();
        switch (target) {
            case "CONFIRMED" -> {
                visit.setCustomerConfirmed(true);
                visit.setCustomerConfirmedAt(now);
            }
            case "COMPLETED" -> {
                if (visit.getScheduledAt().isAfter(now.plus(Duration.ofHours(1)))) {
                    throw new ConflictException("A visit can only be completed from an hour before its scheduled time");
                }
                visit.setCompletedAt(now);
                visit.setOutcome(request.outcome());
            }
            case "CANCELLED" -> {
                if (request.cancellationReason() == null || request.cancellationReason().isBlank()) {
                    throw new IllegalArgumentException("A cancellation reason is required");
                }
                visit.setCancellationReason(request.cancellationReason().trim());
            }
            case "NO_SHOW" -> visit.setOutcome(null);
            default -> throw new IllegalArgumentException("Unsupported status " + target);
        }
        if (request.followUpNotes() != null && !request.followUpNotes().isBlank()) {
            visit.setFollowUpNotes(request.followUpNotes().trim());
        }
        String from = visit.getStatus();
        visit.setStatus(target);
        visit = visits.save(visit);
        crm.addActivity(lead, "site_visit", "Site visit " + from + " → " + target
                        + (visit.getOutcome() == null ? "" : " (" + visit.getOutcome() + ")"),
                Map.of("site_visit_id", visit.getId().toString()), ctx.actor().email(), now, visit.getAgentId());
        if ("COMPLETED".equals(target)) {
            crm.transition(lead, PipelineStatus.SITE_VISIT_COMPLETED, "Site visit completed", null, ctx.actor().email(), false);
            if ("negotiation".equals(visit.getOutcome()) || "booked".equals(visit.getOutcome())) {
                crm.transition(lead, PipelineStatus.NEGOTIATION, "Visit outcome: " + visit.getOutcome(), null,
                        ctx.actor().email(), false);
            }
            conversions.recordForLead(lead, EventName.SiteVisitCompleted, "visit-" + visit.getId() + "-completed",
                    Source.manual_crm, Map.of("content_name", "site_visit"));
        }
        return toDto(visit, lead);
    }

    @Transactional
    public SiteVisitDto reschedule(UUID visitId, RescheduleRequest request) {
        SiteVisit old = visits.findById(visitId).orElseThrow(() -> new NotFoundException("Site visit not found"));
        LeadContext ctx = crm.requireLead(old.getLeadId(), Permission.OPERATE);
        if (!ACTIVE.contains(old.getStatus())) {
            throw new ConflictException("Only scheduled or confirmed visits can be rescheduled");
        }
        if (request.scheduledAt().isBefore(clock.instant())) {
            throw new IllegalArgumentException("Reschedule to a future time");
        }
        old.setStatus("RESCHEDULED");
        old.setCancellationReason(blank(request.reason()));
        visits.save(old);
        SiteVisit next = new SiteVisit();
        next.setClientId(old.getClientId());
        next.setLeadId(old.getLeadId());
        next.setProjectId(old.getProjectId());
        next.setAgentId(request.agentId() == null || request.agentId().isBlank() ? old.getAgentId()
                : resolveAgent(ctx.lead(), request.agentId()));
        next.setScheduledAt(request.scheduledAt());
        next.setDurationMinutes(old.getDurationMinutes());
        next.setStatus("SCHEDULED");
        next.setRescheduledFromId(old.getId());
        Long lead = old.getReminderAt() == null ? null
                : Duration.between(old.getReminderAt(), old.getScheduledAt()).toMinutes();
        next.setReminderAt(reminderAt(request.scheduledAt(), lead == null ? 0 : lead.intValue()));
        next.setFollowUpNotes(old.getFollowUpNotes());
        next.setCreatedBy(ctx.actor().email());
        next = visits.save(next);
        crm.addActivity(ctx.lead(), "site_visit", "Site visit rescheduled to " + request.scheduledAt(),
                Map.of("site_visit_id", next.getId().toString(), "previous_visit_id", old.getId().toString()),
                ctx.actor().email(), clock.instant(), next.getAgentId());
        return toDto(next, ctx.lead());
    }

    @Transactional(readOnly = true)
    public SiteVisitDto get(UUID visitId) {
        SiteVisit visit = visits.findById(visitId).orElseThrow(() -> new NotFoundException("Site visit not found"));
        LeadContext ctx = crm.requireLead(visit.getLeadId(), Permission.READ);
        return toDto(visit, ctx.lead());
    }

    @Transactional(readOnly = true)
    public SiteVisitPage list(UUID clientId, VisitFilter filter, int limit, long offset) {
        access.read(clientId);
        Specification<SiteVisit> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("clientId"), clientId));
            if (filter.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"), filter.from()));
            if (filter.to() != null) p.add(cb.lessThan(root.get("scheduledAt"), filter.to()));
            if (filter.status() != null && !filter.status().isBlank()) p.add(cb.equal(root.get("status"), filter.status()));
            if (filter.agentId() != null) p.add(cb.equal(root.get("agentId"), filter.agentId()));
            if (filter.projectId() != null) p.add(cb.equal(root.get("projectId"), filter.projectId()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<SiteVisit> page = visits.findAll(spec, new OffsetPageRequest(offset, limit, Sort.by("scheduledAt")));
        Map<UUID, Lead> byLead = leads.findAllById(page.getContent().stream().map(SiteVisit::getLeadId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Lead::getId, Function.identity()));
        Map<UUID, String> agentNames = new HashMap<>();
        agents.findByClientIdOrderByNameAsc(clientId).forEach(a -> agentNames.put(a.getId(), a.getName()));
        Map<UUID, String> projectNames = new HashMap<>();
        projects.findByClientIdOrderByNameAsc(clientId).forEach(p -> projectNames.put(p.getId(), p.getName()));
        return new SiteVisitPage(page.getContent().stream()
                .map(v -> toDto(v, byLead.get(v.getLeadId()), agentNames, projectNames)).toList(), page.getTotalElements());
    }

    /**
     * Raises due reminders as lead activities (visible on the timeline and the visits page). Outbound
     * WhatsApp/SMS reminders are not sent in Phase 1.
     */
    @Scheduled(fixedDelayString = "${campex.crm.reminder-interval-ms:60000}", initialDelayString = "30000")
    @Transactional
    public void raiseReminders() {
        Instant now = clock.instant();
        for (SiteVisit visit : visits.findDueReminders(now)) {
            leads.findById(visit.getLeadId()).ifPresent(lead -> crm.addActivity(lead, "reminder",
                    "Reminder: site visit at " + visit.getScheduledAt(),
                    Map.of("site_visit_id", visit.getId().toString()), "system", now, visit.getAgentId()));
            visit.setReminderSentAt(now);
        }
        for (LeadFollowUp followUp : followUps.findDueReminders(now)) {
            leads.findById(followUp.getLeadId()).ifPresent(lead -> crm.addActivity(lead, "reminder",
                    "Follow-up due (" + followUp.getChannel() + ")",
                    Map.of("follow_up_id", followUp.getId().toString()), "system", now, followUp.getAgentId()));
            followUp.setReminderSentAt(now);
        }
    }

    private UUID resolveProject(Lead lead, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return lead.getProjectId();
        }
        ClientProject project = setup.requireProject(lead.getClientId(), CrmService.parse(projectId, "Project"));
        return project.getId();
    }

    private UUID resolveAgent(Lead lead, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return lead.getAssignedAgentId();
        }
        SalesAgent agent = setup.requireAgent(lead.getClientId(), CrmService.parse(agentId, "Agent"));
        return agent.getId();
    }

    private static Instant reminderAt(Instant scheduledAt, Integer minutesBefore) {
        int minutes = minutesBefore == null ? 120 : minutesBefore;
        return minutes <= 0 ? null : scheduledAt.minus(Duration.ofMinutes(minutes));
    }

    private SiteVisitDto toDto(SiteVisit v, Lead lead) {
        Map<UUID, String> agentNames = new HashMap<>();
        if (v.getAgentId() != null) {
            agents.findById(v.getAgentId()).ifPresent(a -> agentNames.put(a.getId(), a.getName()));
        }
        Map<UUID, String> projectNames = new LinkedHashMap<>();
        if (v.getProjectId() != null) {
            projects.findById(v.getProjectId()).ifPresent(p -> projectNames.put(p.getId(), p.getName()));
        }
        return toDto(v, lead, agentNames, projectNames);
    }

    static SiteVisitDto toDto(SiteVisit v, Lead lead, Map<UUID, String> agentNames, Map<UUID, String> projectNames) {
        return new SiteVisitDto(v.getId().toString(), v.getClientId().toString(), v.getLeadId().toString(),
                lead == null ? null : lead.getFirstName(), lead == null ? null : lead.getPhoneNumber(),
                v.getProjectId() == null ? null : v.getProjectId().toString(), projectNames.get(v.getProjectId()),
                v.getAgentId() == null ? null : v.getAgentId().toString(), agentNames.get(v.getAgentId()),
                v.getScheduledAt(), v.getDurationMinutes(), v.getStatus(), v.isCustomerConfirmed(),
                v.getCustomerConfirmedAt(), v.getCompletedAt(), v.getOutcome(), v.getFollowUpNotes(),
                v.getCancellationReason(), v.getRescheduledFromId() == null ? null : v.getRescheduledFromId().toString(),
                v.getReminderAt(), v.getReminderSentAt(), v.getCreatedAt());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
