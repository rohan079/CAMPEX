package com.aivoice.lead.service.crm;

import com.aivoice.lead.dto.CrmDtos.AgentDto;
import com.aivoice.lead.dto.CrmDtos.AgentRequest;
import com.aivoice.lead.dto.CrmDtos.MemberDto;
import com.aivoice.lead.dto.CrmDtos.MemberRequest;
import com.aivoice.lead.dto.CrmDtos.ProjectDto;
import com.aivoice.lead.dto.CrmDtos.ProjectRequest;
import com.aivoice.lead.dto.CrmDtos.ScoringConfig;
import com.aivoice.lead.dto.CrmDtos.ScoringRule;
import com.aivoice.lead.entity.ClientAdAccount;
import com.aivoice.lead.entity.ClientMember;
import com.aivoice.lead.entity.ClientProject;
import com.aivoice.lead.entity.LeadScoringConfig;
import com.aivoice.lead.entity.SalesAgent;
import com.aivoice.lead.repository.ClientAdAccountRepository;
import com.aivoice.lead.repository.ClientMemberRepository;
import com.aivoice.lead.repository.ClientProjectRepository;
import com.aivoice.lead.repository.LeadScoringConfigRepository;
import com.aivoice.lead.repository.SalesAgentRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-client CRM setup: sales agents, property projects, members, Meta ad accounts and scoring weights. */
@Service
public class CrmSetupService {

    private final SalesAgentRepository agents;
    private final ClientProjectRepository projects;
    private final ClientMemberRepository members;
    private final ClientAdAccountRepository adAccounts;
    private final LeadScoringConfigRepository scoringConfigs;
    private final ClientAccessService access;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;

    public CrmSetupService(SalesAgentRepository agents, ClientProjectRepository projects, ClientMemberRepository members,
                           ClientAdAccountRepository adAccounts, LeadScoringConfigRepository scoringConfigs,
                           ClientAccessService access, AuditService audit, NamedParameterJdbcTemplate jdbc) {
        this.agents = agents;
        this.projects = projects;
        this.members = members;
        this.adAccounts = adAccounts;
        this.scoringConfigs = scoringConfigs;
        this.access = access;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- agents

    @Transactional(readOnly = true)
    public List<AgentDto> listAgents(UUID clientId) {
        access.read(clientId);
        Map<UUID, Long> open = new LinkedHashMap<>();
        jdbc.query("""
                select assigned_agent_id a, count(*) n from leads
                where client_id = :cid and assigned_agent_id is not null and pipeline_status not in ('WON', 'LOST')
                group by a""", new MapSqlParameterSource("cid", clientId),
                rs -> { open.put(rs.getObject("a", UUID.class), rs.getLong("n")); });
        return agents.findByClientIdOrderByNameAsc(clientId).stream()
                .map(a -> toDto(a, open.getOrDefault(a.getId(), 0L))).toList();
    }

    @Transactional
    public AgentDto createAgent(UUID clientId, AgentRequest request) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        SalesAgent agent = new SalesAgent();
        agent.setClientId(clientId);
        applyAgent(agent, request);
        agent = agents.saveAndFlush(agent);
        audit.record(clientId, actor.email(), "sales_agent", agent.getId(), "created", Map.of("name", agent.getName()));
        return toDto(agent, 0);
    }

    @Transactional
    public AgentDto updateAgent(UUID clientId, UUID agentId, AgentRequest request) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        SalesAgent agent = requireAgent(clientId, agentId);
        applyAgent(agent, request);
        audit.record(clientId, actor.email(), "sales_agent", agent.getId(), "updated",
                Map.of("name", agent.getName(), "active", agent.isActive()));
        return toDto(agents.save(agent), 0);
    }

    private static void applyAgent(SalesAgent agent, AgentRequest request) {
        agent.setName(request.name().trim());
        agent.setEmail(request.email() == null || request.email().isBlank() ? null
                : request.email().trim().toLowerCase(Locale.ROOT));
        agent.setPhoneNumber(request.phoneNumber() == null || request.phoneNumber().isBlank() ? null
                : request.phoneNumber().trim());
        agent.setActive(request.active() == null || request.active());
    }

    public SalesAgent requireAgent(UUID clientId, UUID agentId) {
        return agents.findByIdAndClientId(agentId, clientId).orElseThrow(() -> new NotFoundException("Agent not found"));
    }

    static AgentDto toDto(SalesAgent a, long openLeads) {
        return new AgentDto(a.getId().toString(), a.getClientId().toString(), a.getName(), a.getEmail(),
                a.getPhoneNumber(), a.isActive(), openLeads);
    }

    // ---------------------------------------------------------------- projects

    @Transactional(readOnly = true)
    public List<ProjectDto> listProjects(UUID clientId) {
        access.read(clientId);
        return projects.findByClientIdOrderByNameAsc(clientId).stream().map(CrmSetupService::toDto).toList();
    }

    @Transactional
    public ProjectDto createProject(UUID clientId, ProjectRequest request) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        ClientProject project = new ClientProject();
        project.setClientId(clientId);
        applyProject(project, request);
        project = projects.saveAndFlush(project);
        audit.record(clientId, actor.email(), "client_project", project.getId(), "created", Map.of("name", project.getName()));
        return toDto(project);
    }

    @Transactional
    public ProjectDto updateProject(UUID clientId, UUID projectId, ProjectRequest request) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        ClientProject project = requireProject(clientId, projectId);
        applyProject(project, request);
        audit.record(clientId, actor.email(), "client_project", project.getId(), "updated", Map.of("name", project.getName()));
        return toDto(projects.save(project));
    }

    private static void applyProject(ClientProject project, ProjectRequest request) {
        BigDecimal min = request.minPrice();
        BigDecimal max = request.maxPrice();
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min_price must not exceed max_price");
        }
        project.setName(request.name().trim());
        project.setLocation(blank(request.location()));
        project.setPropertyTypes(blank(request.propertyTypes()));
        project.setMinPrice(min);
        project.setMaxPrice(max);
        project.setActive(request.active() == null || request.active());
    }

    public ClientProject requireProject(UUID clientId, UUID projectId) {
        return projects.findByIdAndClientId(projectId, clientId).orElseThrow(() -> new NotFoundException("Project not found"));
    }

    static ProjectDto toDto(ClientProject p) {
        return new ProjectDto(p.getId().toString(), p.getClientId().toString(), p.getName(), p.getLocation(),
                p.getPropertyTypes(), p.getMinPrice(), p.getMaxPrice(), p.isActive());
    }

    // ---------------------------------------------------------------- members

    @Transactional(readOnly = true)
    public List<MemberDto> listMembers(UUID clientId) {
        access.require(clientId, Permission.MANAGE);
        return members.findByClientIdOrderByUserEmailAsc(clientId).stream()
                .map(m -> new MemberDto(m.getId().toString(), m.getUserEmail(), m.getRole(), m.getCreatedAt())).toList();
    }

    @Transactional
    public MemberDto upsertMember(UUID clientId, MemberRequest request) {
        Actor actor = access.require(clientId, Permission.OWN);
        String email = request.userEmail().trim().toLowerCase(Locale.ROOT);
        ClientMember member = members.findByClientIdAndUserEmail(clientId, email).orElseGet(ClientMember::new);
        member.setClientId(clientId);
        member.setUserEmail(email);
        member.setRole(request.role());
        if (member.getCreatedBy() == null) {
            member.setCreatedBy(actor.email());
        }
        member = members.save(member);
        audit.record(clientId, actor.email(), "client_member", member.getId(), "upserted", Map.of("role", request.role()));
        return new MemberDto(member.getId().toString(), member.getUserEmail(), member.getRole(), member.getCreatedAt());
    }

    @Transactional
    public void removeMember(UUID clientId, UUID memberId) {
        Actor actor = access.require(clientId, Permission.OWN);
        ClientMember member = members.findById(memberId).filter(m -> m.getClientId().equals(clientId))
                .orElseThrow(() -> new NotFoundException("Member not found"));
        if ("owner".equals(member.getRole()) && members.findByClientIdOrderByUserEmailAsc(clientId).stream()
                .filter(m -> "owner".equals(m.getRole())).count() <= 1) {
            throw new ConflictException("A client must keep at least one owner");
        }
        members.delete(member);
        audit.record(clientId, actor.email(), "client_member", memberId, "removed", Map.of());
    }

    // ---------------------------------------------------------------- Meta ad accounts

    @Transactional(readOnly = true)
    public List<String> adAccounts(UUID clientId) {
        access.read(clientId);
        return adAccounts.findByClientIdOrderByAdAccountIdAsc(clientId).stream().map(ClientAdAccount::getAdAccountId).toList();
    }

    @Transactional
    public List<String> replaceAdAccounts(UUID clientId, List<String> ids) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        Set<String> normalized = new HashSet<>();
        for (String id : ids) {
            normalized.add(id.startsWith("act_") ? id : "act_" + id);
        }
        adAccounts.deleteByClientId(clientId);
        adAccounts.flush();
        for (String id : normalized) {
            ClientAdAccount account = new ClientAdAccount();
            account.setClientId(clientId);
            account.setAdAccountId(id);
            account.setCreatedBy(actor.email());
            adAccounts.save(account);
        }
        audit.record(clientId, actor.email(), "client_ad_accounts", clientId, "replaced",
                Map.of("ad_account_ids", normalized.stream().sorted().toList()));
        return normalized.stream().sorted().toList();
    }

    // ---------------------------------------------------------------- scoring

    @Transactional(readOnly = true)
    public List<ScoringRule> scoringRules(UUID clientId) {
        return scoringConfigs.findById(clientId).map(c -> c.getRules().stream()
                        .map(r -> new ScoringRule(String.valueOf(r.get("factor")), ((Number) r.get("weight")).intValue()))
                        .toList())
                .orElse(LeadScoringService.DEFAULT_RULES);
    }

    @Transactional(readOnly = true)
    public ScoringConfig getScoring(UUID clientId) {
        access.read(clientId);
        return new ScoringConfig(scoringRules(clientId));
    }

    @Transactional
    public ScoringConfig updateScoring(UUID clientId, ScoringConfig config) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        Set<String> seen = new HashSet<>();
        for (ScoringRule rule : config.rules()) {
            if (!seen.add(rule.factor())) {
                throw new IllegalArgumentException("Duplicate factor: " + rule.factor());
            }
        }
        if (config.rules().stream().mapToInt(ScoringRule::weight).sum() == 0) {
            throw new IllegalArgumentException("At least one factor needs a weight above zero");
        }
        LeadScoringConfig entity = scoringConfigs.findById(clientId).orElseGet(LeadScoringConfig::new);
        entity.setClientId(clientId);
        entity.setRules(config.rules().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("factor", r.factor());
            row.put("weight", r.weight());
            return row;
        }).toList());
        entity.setUpdatedBy(actor.email());
        scoringConfigs.save(entity);
        audit.record(clientId, actor.email(), "lead_scoring", clientId, "updated", Map.of("rules", entity.getRules()));
        return new ScoringConfig(config.rules());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
