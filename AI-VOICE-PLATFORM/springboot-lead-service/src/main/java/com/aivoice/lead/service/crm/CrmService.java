package com.aivoice.lead.service.crm;

import com.aivoice.lead.dto.CrmDtos.ActivityDto;
import com.aivoice.lead.dto.CrmDtos.ActivityRequest;
import com.aivoice.lead.dto.CrmDtos.AgentPerformance;
import com.aivoice.lead.dto.CrmDtos.AssignRequest;
import com.aivoice.lead.dto.CrmDtos.AssignmentDto;
import com.aivoice.lead.dto.CrmDtos.CrmLeadDetail;
import com.aivoice.lead.dto.CrmDtos.DuplicateDto;
import com.aivoice.lead.dto.CrmDtos.FollowUpDto;
import com.aivoice.lead.dto.CrmDtos.FollowUpRequest;
import com.aivoice.lead.dto.CrmDtos.FollowUpUpdate;
import com.aivoice.lead.dto.CrmDtos.LeadCard;
import com.aivoice.lead.dto.CrmDtos.LeadTrackingConsentRequest;
import com.aivoice.lead.dto.CrmDtos.ManualLeadRequest;
import com.aivoice.lead.dto.CrmDtos.PerformanceReport;
import com.aivoice.lead.dto.CrmDtos.Pipeline;
import com.aivoice.lead.dto.CrmDtos.PipelineColumn;
import com.aivoice.lead.dto.CrmDtos.QualificationDto;
import com.aivoice.lead.dto.CrmDtos.QualificationRequest;
import com.aivoice.lead.dto.CrmDtos.StatusChangeRequest;
import com.aivoice.lead.dto.CrmDtos.StatusHistoryDto;
import com.aivoice.lead.entity.ClientProject;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadActivity;
import com.aivoice.lead.entity.LeadAssignment;
import com.aivoice.lead.entity.LeadFollowUp;
import com.aivoice.lead.entity.LeadQualification;
import com.aivoice.lead.entity.LeadStatus;
import com.aivoice.lead.entity.LeadStatusHistory;
import com.aivoice.lead.entity.PipelineStatus;
import com.aivoice.lead.entity.SalesAgent;
import com.aivoice.lead.repository.LeadActivityRepository;
import com.aivoice.lead.repository.LeadAssignmentRepository;
import com.aivoice.lead.repository.LeadFollowUpRepository;
import com.aivoice.lead.repository.LeadQualificationRepository;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.LeadStatusHistoryRepository;
import com.aivoice.lead.repository.SalesAgentRepository;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.LanguageService;
import com.aivoice.lead.service.PhoneNumbers;
import com.aivoice.lead.service.attribution.AttributionService;
import com.aivoice.lead.service.tracking.ConversionEventService;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.Source;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operational CRM: pipeline status (separate from call status and review status), assignment, activities,
 * follow-ups, qualification, duplicates and agent performance. All access goes through the lead's own client.
 */
@Service
public class CrmService {

    public record LeadContext(Lead lead, Actor actor) {
    }

    public record PipelineFilter(String query, UUID agentId, UUID projectId, String source, String campaignId) {
    }

    private final LeadRepository leads;
    private final LeadStatusHistoryRepository history;
    private final LeadAssignmentRepository assignments;
    private final LeadActivityRepository activities;
    private final LeadFollowUpRepository followUps;
    private final LeadQualificationRepository qualifications;
    private final SalesAgentRepository agents;
    private final CrmSetupService setup;
    private final AttributionService attribution;
    private final ConversionEventService conversions;
    private final ClientAccessService access;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final String currency;
    private final java.time.ZoneId zone;

    public CrmService(LeadRepository leads, LeadStatusHistoryRepository history, LeadAssignmentRepository assignments,
                      LeadActivityRepository activities, LeadFollowUpRepository followUps,
                      LeadQualificationRepository qualifications, SalesAgentRepository agents, CrmSetupService setup,
                      AttributionService attribution, ConversionEventService conversions, ClientAccessService access,
                      NamedParameterJdbcTemplate jdbc, Clock clock, @Value("${app.currency:INR}") String currency,
                      com.aivoice.lead.config.AppProperties props) {
        this.leads = leads;
        this.history = history;
        this.assignments = assignments;
        this.activities = activities;
        this.followUps = followUps;
        this.qualifications = qualifications;
        this.agents = agents;
        this.setup = setup;
        this.attribution = attribution;
        this.conversions = conversions;
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
        this.currency = currency;
        this.zone = props.zone();
    }

    /** Loads a lead and checks the caller's permission on the lead's client (looked up, never trusted). */
    public LeadContext requireLead(UUID leadId, Permission permission) {
        Lead lead = leads.findById(leadId).orElseThrow(() -> new NotFoundException("Lead not found"));
        Actor actor = access.require(lead.getClientId(), permission);
        return new LeadContext(lead, actor);
    }

    // ---------------------------------------------------------------- manual leads

    @Transactional
    public CrmLeadDetail createManualLead(UUID clientId, ManualLeadRequest request) {
        Actor actor = access.require(clientId, Permission.OPERATE);
        String phone = PhoneNumbers.normalize(request.phoneNumber());
        leads.findFirstByClientIdAndPhoneNumberInOrderByCreatedAtDesc(clientId, PhoneNumbers.keys(phone))
                .ifPresent(existing -> {
                    throw new ConflictException("A lead with this phone number already exists (" + existing.getId() + ")");
                });
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setPhoneNumber(phone);
        lead.setWhatsappUserId(PhoneNumbers.forWhatsApp(phone));
        lead.setFirstName(blank(request.name()));
        lead.setEmail(request.email() == null || request.email().isBlank() ? null
                : request.email().trim().toLowerCase(Locale.ROOT));
        lead.setSource(LeadStatus.SOURCE_MANUAL);
        // Manual leads never enter the automated WhatsApp/call flow: no outreach, no consent recorded.
        lead.setStatus(LeadStatus.AWAITING_CONSENT);
        lead.setPreferredLanguage(request.language() == null ? "en" : LanguageService.normalize(request.language()));
        if (request.projectId() != null && !request.projectId().isBlank()) {
            lead.setProjectId(setup.requireProject(clientId, parse(request.projectId(), "Project")).getId());
        }
        lead.setPipelineUpdatedAt(clock.instant());
        lead = leads.saveAndFlush(lead);
        attribution.recordTouch(lead, "manual", request.attribution());
        writeHistory(lead, null, PipelineStatus.NEW, "Created manually", actor.email());
        if (request.note() != null && !request.note().isBlank()) {
            addActivity(lead, "note", request.note().trim(), null, actor.email(), clock.instant(), null);
        }
        if (request.agentId() != null && !request.agentId().isBlank()) {
            assign(lead, setup.requireAgent(clientId, parse(request.agentId(), "Agent")), "Assigned at creation", actor);
        }
        return detail(lead);
    }

    // ---------------------------------------------------------------- pipeline board

    @Transactional(readOnly = true)
    public Pipeline pipeline(UUID clientId, PipelineFilter filter, int perColumn) {
        access.read(clientId);
        MapSqlParameterSource p = new MapSqlParameterSource("cid", clientId).addValue("per", perColumn);
        StringBuilder where = new StringBuilder(" where l.client_id = :cid");
        if (filter.query() != null && !filter.query().isBlank()) {
            where.append(" and (l.first_name ilike :q or l.phone_number like :qd or lower(l.email) like :ql)");
            String q = filter.query().trim();
            p.addValue("q", "%" + q + "%").addValue("qd", "%" + q.replaceAll("\\D", "") + "%")
                    .addValue("ql", "%" + q.toLowerCase(Locale.ROOT) + "%");
            if (q.replaceAll("\\D", "").isEmpty()) {
                p.addValue("qd", "%" + q + "%");
            }
        }
        if (filter.agentId() != null) {
            where.append(" and l.assigned_agent_id = :agent");
            p.addValue("agent", filter.agentId());
        }
        if (filter.projectId() != null) {
            where.append(" and l.project_id = :project");
            p.addValue("project", filter.projectId());
        }
        if (filter.source() != null && !filter.source().isBlank()) {
            where.append(" and coalesce(ft.source, 'unknown') = :src");
            p.addValue("src", filter.source());
        }
        if (filter.campaignId() != null && !filter.campaignId().isBlank()) {
            where.append(" and (ft.campaign_id = :camp or lt.campaign_id = :camp)");
            p.addValue("camp", filter.campaignId());
        }
        Map<String, List<LeadCard>> cards = new LinkedHashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (PipelineStatus status : PipelineStatus.values()) {
            cards.put(status.name(), new ArrayList<>());
        }
        jdbc.query("""
                select * from (
                  select l.id, coalesce(nullif(l.first_name, ''), l.phone_number) as name, l.phone_number,
                         l.pipeline_status, l.review_status, l.source, l.assigned_agent_id, ag.name as agent_name,
                         l.project_id, q.qualification_score, l.created_at, l.pipeline_updated_at,
                         coalesce(ft.source, 'unknown') as attr_source,
                         coalesce(ft.campaign_name, ft.campaign_id, ft.utm_campaign) as campaign_label,
                         (select min(f.due_at) from lead_follow_ups f where f.lead_id = l.id and f.status = 'pending') as next_fu,
                         row_number() over (partition by l.pipeline_status
                                            order by coalesce(l.pipeline_updated_at, l.created_at) desc) as rn,
                         count(*) over (partition by l.pipeline_status) as cnt
                  from leads l
                  left join sales_agents ag on ag.id = l.assigned_agent_id
                  left join lead_qualifications q on q.lead_id = l.id
                  left join lead_attributions a on a.lead_id = l.id
                  left join lead_attribution_touches ft on ft.id = a.first_touch_id
                  left join lead_attribution_touches lt on lt.id = a.last_touch_id
                """ + " " + where + ") x where rn <= :per", p, rs -> {
            String status = rs.getString("pipeline_status");
            counts.put(status, rs.getLong("cnt"));
            UUID agentId = rs.getObject("assigned_agent_id", UUID.class);
            UUID projectId = rs.getObject("project_id", UUID.class);
            Integer score = rs.getObject("qualification_score") == null ? null : rs.getInt("qualification_score");
            cards.get(status).add(new LeadCard(rs.getObject("id", UUID.class).toString(), rs.getString("name"),
                    rs.getString("phone_number"), status, rs.getString("review_status"), rs.getString("source"),
                    rs.getString("attr_source"), rs.getString("campaign_label"),
                    agentId == null ? null : agentId.toString(), rs.getString("agent_name"),
                    projectId == null ? null : projectId.toString(), score, instant(rs.getTimestamp("next_fu")),
                    instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("pipeline_updated_at"))));
        });
        List<PipelineColumn> columns = new ArrayList<>();
        long total = 0;
        for (PipelineStatus status : PipelineStatus.values()) {
            long count = counts.getOrDefault(status.name(), 0L);
            total += count;
            columns.add(new PipelineColumn(status.name(), count, cards.get(status.name())));
        }
        return new Pipeline(columns, total);
    }

    // ---------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public CrmLeadDetail detail(UUID leadId) {
        return detail(requireLead(leadId, Permission.READ).lead());
    }

    CrmLeadDetail detail(Lead lead) {
        PipelineStatus status = PipelineStatus.parse(lead.getPipelineStatus());
        Map<UUID, String> agentNames = agentNames(lead.getClientId());
        Long responseMinutes = lead.getFirstContactedAt() == null ? null
                : Duration.between(lead.getCreatedAt(), lead.getFirstContactedAt()).toMinutes();
        return new CrmLeadDetail(lead.getId().toString(), lead.getClientId().toString(), lead.getFirstName(),
                lead.getPhoneNumber(), lead.getEmail(), lead.getSource(), lead.getStatus(), lead.getReviewStatus(),
                status.name(), status.nextStatuses().stream().map(Enum::name).toList(), lead.getLostReason(),
                str(lead.getAssignedAgentId()), agentNames.get(lead.getAssignedAgentId()), str(lead.getProjectId()),
                lead.getCreatedAt(), lead.getFirstContactedAt(), responseMinutes,
                qualifications.findByLeadId(lead.getId()).map(CrmService::toDto).orElse(null),
                followUps.findByLeadIdOrderByDueAtAsc(lead.getId()).stream().map(this::toDto).toList(),
                history.findByLeadIdOrderByChangedAtAsc(lead.getId()).stream()
                        .map(h -> new StatusHistoryDto(h.getFromStatus(), h.getToStatus(), h.getReason(),
                                h.getChangedBy(), h.getChangedAt())).toList(),
                assignments.findByLeadIdOrderByAssignedAtDesc(lead.getId()).stream()
                        .map(a -> new AssignmentDto(str(a.getAgentId()), agentNames.get(a.getAgentId()),
                                str(a.getPreviousAgentId()), a.getReason(), a.getAssignedBy(), a.getAssignedAt(),
                                a.getUnassignedAt())).toList(),
                duplicates(lead),
                conversions.leadConsentStatus(lead, null));
    }

    List<DuplicateDto> duplicates(Lead lead) {
        String email = lead.getEmail() == null ? null : lead.getEmail().trim().toLowerCase(Locale.ROOT);
        List<String> phones = PhoneNumbers.keys(lead.getPhoneNumber());
        if (phones.isEmpty()) {
            phones = List.of(lead.getPhoneNumber());
        }
        return leads.findDuplicateCandidates(lead.getClientId(), lead.getId(), phones, email).stream()
                .limit(10)
                .map(d -> new DuplicateDto(d.getId().toString(), d.getFirstName(), d.getPhoneNumber(),
                        d.getPipelineStatus(), d.getCreatedAt())).toList();
    }

    // ---------------------------------------------------------------- assignment

    @Transactional
    public CrmLeadDetail assign(UUID leadId, AssignRequest request) {
        LeadContext ctx = requireLead(leadId, Permission.OPERATE);
        SalesAgent agent = setup.requireAgent(ctx.lead().getClientId(), parse(request.agentId(), "Agent"));
        if (!agent.isActive()) {
            throw new ConflictException("This agent is inactive");
        }
        assign(ctx.lead(), agent, request.reason(), ctx.actor());
        return detail(ctx.lead());
    }

    void assign(Lead lead, SalesAgent agent, String reason, Actor actor) {
        if (agent.getId().equals(lead.getAssignedAgentId())) {
            return;
        }
        Instant now = clock.instant();
        UUID previous = lead.getAssignedAgentId();
        assignments.findFirstByLeadIdAndUnassignedAtIsNullOrderByAssignedAtDesc(lead.getId())
                .ifPresent(open -> open.setUnassignedAt(now));
        LeadAssignment assignment = new LeadAssignment();
        assignment.setClientId(lead.getClientId());
        assignment.setLeadId(lead.getId());
        assignment.setAgentId(agent.getId());
        assignment.setPreviousAgentId(previous);
        assignment.setReason(blank(reason));
        assignment.setAssignedBy(actor.email());
        assignment.setAssignedAt(now);
        assignments.save(assignment);
        lead.setAssignedAgentId(agent.getId());
        leads.save(lead);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agent_id", agent.getId().toString());
        meta.put("previous_agent_id", previous == null ? null : previous.toString());
        addActivity(lead, "assignment", (previous == null ? "Assigned to " : "Reassigned to ") + agent.getName(),
                meta, actor.email(), now, agent.getId());
    }

    // ---------------------------------------------------------------- status

    @Transactional
    public CrmLeadDetail changeStatus(UUID leadId, StatusChangeRequest request) {
        LeadContext ctx = requireLead(leadId, Permission.OPERATE);
        PipelineStatus target = PipelineStatus.parse(request.status());
        if (target == PipelineStatus.LOST && (request.lostReason() == null || request.lostReason().isBlank())) {
            throw new IllegalArgumentException("A lost reason is required");
        }
        transition(ctx.lead(), target, request.reason(), request.lostReason(), ctx.actor().email(), true);
        return detail(ctx.lead());
    }

    /**
     * Moves a lead to {@code target}. With {@code strict}, illegal transitions fail; otherwise (automatic moves from
     * site visits and sales) the lead only ever moves forward and illegal moves are skipped.
     */
    public void transition(Lead lead, PipelineStatus target, String reason, String lostReason, String actorEmail,
                           boolean strict) {
        PipelineStatus current = PipelineStatus.parse(lead.getPipelineStatus());
        if (current == target) {
            return;
        }
        if (strict) {
            if (!current.canMoveTo(target)) {
                throw new ConflictException("Cannot move a lead from " + current + " to " + target);
            }
        } else {
            // Automatic moves record CRM facts (a visit happened, a sale was confirmed): they may skip stages but only
            // ever move forward. A confirmed sale always means WON, even for a lead previously marked LOST.
            boolean forward = current.isBefore(target);
            boolean confirmedSale = target == PipelineStatus.WON;
            if (!forward && !confirmedSale) {
                return;
            }
        }
        Instant now = clock.instant();
        lead.setPipelineStatus(target.name());
        lead.setPipelineUpdatedAt(now);
        if (target == PipelineStatus.LOST) {
            lead.setLostReason(blank(lostReason));
        } else if (current == PipelineStatus.LOST) {
            lead.setLostReason(null);
        }
        if (target != PipelineStatus.NEW && target != PipelineStatus.LOST && lead.getFirstContactedAt() == null) {
            lead.setFirstContactedAt(now);
        }
        leads.save(lead);
        writeHistory(lead, current, target, target == PipelineStatus.LOST ? lostReason : reason, actorEmail);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from", current.name());
        meta.put("to", target.name());
        addActivity(lead, "status_change", current + " → " + target + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()),
                meta, actorEmail, now, lead.getAssignedAgentId());
        switch (target) {
            case CONTACTED -> conversions.recordForLead(lead, EventName.Contact, "lead-" + lead.getId() + "-contact",
                    Source.manual_crm, Map.of());
            case QUALIFIED -> conversions.recordForLead(lead, EventName.QualifiedLead,
                    "lead-" + lead.getId() + "-qualified", Source.manual_crm, Map.of());
            default -> {
            }
        }
    }

    private void writeHistory(Lead lead, PipelineStatus from, PipelineStatus to, String reason, String actorEmail) {
        LeadStatusHistory row = new LeadStatusHistory();
        row.setClientId(lead.getClientId());
        row.setLeadId(lead.getId());
        row.setFromStatus(from == null ? null : from.name());
        row.setToStatus(to.name());
        row.setReason(blank(reason));
        row.setChangedBy(actorEmail);
        row.setChangedAt(clock.instant());
        history.save(row);
    }

    // ---------------------------------------------------------------- activities

    @Transactional
    public ActivityDto addActivity(UUID leadId, ActivityRequest request) {
        LeadContext ctx = requireLead(leadId, Permission.OPERATE);
        Instant at = request.occurredAt() == null ? clock.instant() : request.occurredAt();
        if (at.isAfter(clock.instant().plusSeconds(300))) {
            throw new IllegalArgumentException("An activity cannot be in the future; schedule a follow-up instead");
        }
        Lead lead = ctx.lead();
        LeadActivity activity = addActivity(lead, request.activityType(), request.body().trim(), null,
                ctx.actor().email(), at, lead.getAssignedAgentId());
        if (!"note".equals(request.activityType())) {
            // Reaching out counts as first contact and moves a NEW lead to CONTACTED.
            if (lead.getFirstContactedAt() == null || lead.getFirstContactedAt().isAfter(at)) {
                lead.setFirstContactedAt(at);
                leads.save(lead);
            }
            transition(lead, PipelineStatus.CONTACTED, "Logged " + request.activityType(), null, ctx.actor().email(), false);
        }
        return toDto(activity);
    }

    public LeadActivity addActivity(Lead lead, String type, String body, Map<String, Object> metadata, String actorEmail,
                                    Instant at, UUID agentId) {
        LeadActivity activity = new LeadActivity();
        activity.setClientId(lead.getClientId());
        activity.setLeadId(lead.getId());
        activity.setActivityType(type);
        activity.setBody(body);
        activity.setMetadata(metadata);
        activity.setActorEmail(actorEmail);
        activity.setAgentId(agentId);
        activity.setOccurredAt(at);
        return activities.save(activity);
    }

    @Transactional(readOnly = true)
    public List<ActivityDto> activities(UUID leadId) {
        LeadContext ctx = requireLead(leadId, Permission.READ);
        return activities.findTop200ByLeadIdOrderByOccurredAtDesc(ctx.lead().getId()).stream().map(CrmService::toDto).toList();
    }

    static ActivityDto toDto(LeadActivity a) {
        return new ActivityDto(a.getId().toString(), a.getActivityType(), a.getBody(), a.getMetadata(),
                str(a.getAgentId()), a.getActorEmail(), a.getOccurredAt());
    }

    // ---------------------------------------------------------------- follow-ups

    @Transactional
    public FollowUpDto addFollowUp(UUID leadId, FollowUpRequest request) {
        LeadContext ctx = requireLead(leadId, Permission.OPERATE);
        if (request.dueAt().isBefore(clock.instant().minusSeconds(300))) {
            throw new IllegalArgumentException("A follow-up must be scheduled in the future");
        }
        Lead lead = ctx.lead();
        UUID agentId = request.agentId() == null || request.agentId().isBlank() ? lead.getAssignedAgentId()
                : setup.requireAgent(lead.getClientId(), parse(request.agentId(), "Agent")).getId();
        LeadFollowUp followUp = new LeadFollowUp();
        followUp.setClientId(lead.getClientId());
        followUp.setLeadId(lead.getId());
        followUp.setAgentId(agentId);
        followUp.setDueAt(request.dueAt());
        followUp.setChannel(request.channel() == null ? "call" : request.channel());
        followUp.setNote(blank(request.note()));
        followUp.setCreatedBy(ctx.actor().email());
        followUp = followUps.save(followUp);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("follow_up_id", followUp.getId().toString());
        meta.put("due_at", request.dueAt().toString());
        addActivity(lead, "follow_up", "Follow-up scheduled (" + followUp.getChannel() + ")", meta,
                ctx.actor().email(), clock.instant(), agentId);
        return toDto(followUp);
    }

    @Transactional(readOnly = true)
    public List<FollowUpDto> followUps(UUID leadId) {
        LeadContext ctx = requireLead(leadId, Permission.READ);
        return followUps.findByLeadIdOrderByDueAtAsc(ctx.lead().getId()).stream().map(this::toDto).toList();
    }

    @Transactional
    public FollowUpDto updateFollowUp(UUID followUpId, FollowUpUpdate request) {
        LeadFollowUp followUp = followUps.findById(followUpId).orElseThrow(() -> new NotFoundException("Follow-up not found"));
        LeadContext ctx = requireLead(followUp.getLeadId(), Permission.OPERATE);
        if (!"pending".equals(followUp.getStatus())) {
            throw new ConflictException("This follow-up is already " + followUp.getStatus());
        }
        followUp.setStatus(request.status());
        if (request.note() != null && !request.note().isBlank()) {
            followUp.setNote(request.note().trim());
        }
        if (!"pending".equals(request.status())) {
            followUp.setCompletedAt(clock.instant());
            addActivity(ctx.lead(), "follow_up", "Follow-up " + request.status(),
                    Map.of("follow_up_id", followUp.getId().toString()), ctx.actor().email(), clock.instant(),
                    followUp.getAgentId());
        }
        return toDto(followUps.save(followUp));
    }

    FollowUpDto toDto(LeadFollowUp f) {
        return new FollowUpDto(f.getId().toString(), f.getLeadId().toString(), str(f.getAgentId()), f.getDueAt(),
                f.getChannel(), f.getNote(), f.getStatus(), f.getCompletedAt(),
                "pending".equals(f.getStatus()) && f.getDueAt().isBefore(clock.instant()));
    }

    // ---------------------------------------------------------------- qualification

    @Transactional
    public QualificationDto qualify(UUID leadId, QualificationRequest request) {
        LeadContext ctx = requireLead(leadId, Permission.OPERATE);
        Lead lead = ctx.lead();
        if (request.budgetMin() != null && request.budgetMax() != null
                && request.budgetMin().compareTo(request.budgetMax()) > 0) {
            throw new IllegalArgumentException("budget_min must not exceed budget_max");
        }
        if (request.projectId() != null && !request.projectId().isBlank()) {
            lead.setProjectId(setup.requireProject(lead.getClientId(), parse(request.projectId(), "Project")).getId());
            leads.save(lead);
        }
        LeadQualification q = qualifications.findByLeadId(lead.getId()).orElseGet(LeadQualification::new);
        q.setClientId(lead.getClientId());
        q.setLeadId(lead.getId());
        q.setBudgetMin(request.budgetMin());
        q.setBudgetMax(request.budgetMax());
        q.setPreferredLocation(blank(request.preferredLocation()));
        q.setPropertyType(blank(request.propertyType()));
        q.setPurchaseTimeline(request.purchaseTimeline());
        q.setBedrooms(request.bedrooms() == null ? null : request.bedrooms().shortValue());
        q.setFinancingPreference(request.financingPreference());
        q.setPurpose(request.purpose());
        q.setGenuineInterest(request.genuineInterest());
        q.setQualificationReason(blank(request.qualificationReason()));
        q.setQualifiedBy(ctx.actor().email());
        ClientProject project = lead.getProjectId() == null ? null
                : setup.requireProject(lead.getClientId(), lead.getProjectId());
        LeadScoringService.Result result = LeadScoringService.score(lead, q, project, setup.scoringRules(lead.getClientId()));
        q.setQualificationScore(result.score());
        q.setScoreBreakdown(LeadScoringService.toJson(result.factors()));
        q = qualifications.save(q);
        addActivity(lead, "qualification", "Qualification updated · score " + result.score(),
                Map.of("score", result.score()), ctx.actor().email(), clock.instant(), lead.getAssignedAgentId());
        return toDto(q);
    }

    static QualificationDto toDto(LeadQualification q) {
        return new QualificationDto(q.getBudgetMin(), q.getBudgetMax(), q.getPreferredLocation(), q.getPropertyType(),
                q.getPurchaseTimeline(), q.getBedrooms() == null ? null : q.getBedrooms().intValue(),
                q.getFinancingPreference(), q.getPurpose(), q.getGenuineInterest(), q.getQualificationScore(),
                LeadScoringService.fromJson(q.getScoreBreakdown()), q.getQualificationReason(), q.getQualifiedBy(),
                q.getUpdatedAt());
    }

    // ---------------------------------------------------------------- tracking consent recorded by staff

    @Transactional
    public CrmLeadDetail recordTrackingConsent(UUID leadId, LeadTrackingConsentRequest request) {
        LeadContext ctx = requireLead(leadId, Permission.OPERATE);
        conversions.recordLeadConsent(ctx.lead(), request.adDataGranted(), blank(request.policyVersion()), ctx.actor());
        addActivity(ctx.lead(), "system", "Ad-data consent recorded: " + (request.adDataGranted() ? "granted" : "denied"),
                null, ctx.actor().email(), clock.instant(), null);
        return detail(ctx.lead());
    }

    // ---------------------------------------------------------------- performance

    @Transactional(readOnly = true)
    public PerformanceReport performance(UUID clientId, Instant from, Instant to) {
        access.read(clientId);
        MapSqlParameterSource p = new MapSqlParameterSource("cid", clientId)
                .addValue("f", Timestamp.from(from)).addValue("t", Timestamp.from(to))
                .addValue("now", Timestamp.from(clock.instant()))
                .addValue("fd", java.sql.Date.valueOf(from.atZone(zone).toLocalDate()))
                .addValue("td", java.sql.Date.valueOf(to.minusSeconds(1).atZone(zone).toLocalDate()));
        Map<UUID, long[]> leadStats = new HashMap<>();
        Map<UUID, Double> response = new HashMap<>();
        jdbc.query("""
                select l.assigned_agent_id a, count(*) n,
                       count(*) filter (where """ + " " + FunnelSql.contacted("l") + " " + """
                ) contacted,
                       count(*) filter (where """ + " " + FunnelSql.qualified("l") + " " + """
                ) qualified,
                       count(*) filter (where l.pipeline_status = 'WON') won,
                       avg(extract(epoch from (l.first_contacted_at - l.created_at)) / 60.0)
                         filter (where l.first_contacted_at is not null) resp
                from leads l
                where l.client_id = :cid and l.assigned_agent_id is not null and l.created_at >= :f and l.created_at < :t
                group by l.assigned_agent_id""", p, rs -> {
            UUID agent = rs.getObject("a", UUID.class);
            leadStats.put(agent, new long[] {rs.getLong("n"), rs.getLong("contacted"), rs.getLong("qualified"), rs.getLong("won")});
            double resp = rs.getDouble("resp");
            if (!rs.wasNull()) {
                response.put(agent, Math.round(resp * 10) / 10.0);
            }
        });
        Map<UUID, long[]> visits = new HashMap<>();
        jdbc.query("""
                select agent_id a,
                       count(*) filter (where status <> 'RESCHEDULED' and scheduled_at >= :f and scheduled_at < :t) sched,
                       count(*) filter (where status = 'COMPLETED' and completed_at >= :f and completed_at < :t) done
                from site_visits where client_id = :cid and agent_id is not null group by agent_id""", p,
                rs -> { visits.put(rs.getObject("a", UUID.class), new long[] {rs.getLong("sched"), rs.getLong("done")}); });
        Map<UUID, Object[]> sales = new HashMap<>();
        jdbc.query("""
                select agent_id a,
                       count(*) filter (where status in """ + " " + FunnelSql.BOOKED_STATUSES + " " + """
                 and booking_date >= :fd and booking_date <= :td) bookings,
                       coalesce(sum(confirmed_revenue) filter (where sale_confirmed_at >= :f and sale_confirmed_at < :t), 0) rev
                from sales where client_id = :cid and agent_id is not null and deleted_at is null group by agent_id""", p,
                rs -> { sales.put(rs.getObject("a", UUID.class), new Object[] {rs.getLong("bookings"), rs.getBigDecimal("rev")}); });
        Map<UUID, Long> overdue = new HashMap<>();
        jdbc.query("""
                select agent_id a, count(*) n from lead_follow_ups
                where client_id = :cid and agent_id is not null and status = 'pending' and due_at < :now group by agent_id""",
                p, rs -> { overdue.put(rs.getObject("a", UUID.class), rs.getLong("n")); });

        List<AgentPerformance> rows = new ArrayList<>();
        for (SalesAgent agent : agents.findByClientIdOrderByNameAsc(clientId)) {
            long[] l = leadStats.getOrDefault(agent.getId(), new long[4]);
            long[] v = visits.getOrDefault(agent.getId(), new long[2]);
            Object[] s = sales.getOrDefault(agent.getId(), new Object[] {0L, BigDecimal.ZERO});
            rows.add(new AgentPerformance(agent.getId().toString(), agent.getName(), l[0], l[1], l[2], v[0], v[1], l[3],
                    (Long) s[0], (BigDecimal) s[1], response.get(agent.getId()), overdue.getOrDefault(agent.getId(), 0L)));
        }
        return new PerformanceReport(from, to, currency, rows);
    }

    // ---------------------------------------------------------------- helpers

    private Map<UUID, String> agentNames(UUID clientId) {
        Map<UUID, String> names = new HashMap<>();
        agents.findByClientIdOrderByNameAsc(clientId).forEach(a -> names.put(a.getId(), a.getName()));
        return names;
    }

    static UUID parse(String id, String what) {
        try {
            return UUID.fromString(id.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotFoundException(what + " not found");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

}
