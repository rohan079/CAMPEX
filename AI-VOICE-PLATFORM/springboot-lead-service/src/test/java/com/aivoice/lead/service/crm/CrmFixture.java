package com.aivoice.lead.service.crm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.entity.Lead;
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
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.attribution.AttributionService;
import com.aivoice.lead.service.tracking.ConversionEventService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Mocked collaborators for CRM services. Client A is accessible; client B is another tenant. */
class CrmFixture {

    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final UUID clientA = UUID.randomUUID();
    final UUID clientB = UUID.randomUUID();
    final LeadRepository leads = mock(LeadRepository.class);
    final LeadStatusHistoryRepository history = mock(LeadStatusHistoryRepository.class);
    final LeadAssignmentRepository assignments = mock(LeadAssignmentRepository.class);
    final LeadActivityRepository activities = mock(LeadActivityRepository.class);
    final LeadFollowUpRepository followUps = mock(LeadFollowUpRepository.class);
    final LeadQualificationRepository qualifications = mock(LeadQualificationRepository.class);
    final SalesAgentRepository agents = mock(SalesAgentRepository.class);
    final CrmSetupService setup = mock(CrmSetupService.class);
    final AttributionService attribution = mock(AttributionService.class);
    final ConversionEventService conversions = mock(ConversionEventService.class);
    final ClientAccessService access = mock(ClientAccessService.class);
    final AppProperties props = new AppProperties("development", "Acme", "", "Asia/Kolkata", 7, 3, "", "", "", null,
            "http://voice", 5, 1);
    final CrmService crm;
    final Lead leadA;
    final Lead leadB;
    final SalesAgent agent;

    CrmFixture() {
        when(access.require(eq(clientA), any())).thenReturn(new Actor("agent@a.example", false));
        when(access.read(clientA)).thenReturn(new Actor("agent@a.example", false));
        when(access.require(eq(clientB), any())).thenThrow(new NotFoundException("Client not found"));
        when(access.read(clientB)).thenThrow(new NotFoundException("Client not found"));
        leadA = lead(clientA);
        leadB = lead(clientB);
        agent = new SalesAgent();
        agent.setId(UUID.randomUUID());
        agent.setClientId(clientA);
        agent.setName("Asha");
        agent.setActive(true);
        when(setup.requireAgent(clientA, agent.getId())).thenReturn(agent);
        when(setup.requireAgent(eq(clientB), any())).thenThrow(new NotFoundException("Agent not found"));
        when(agents.findByClientIdOrderByNameAsc(any())).thenReturn(List.of(agent));
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> {
            var a = (com.aivoice.lead.entity.LeadActivity) i.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(followUps.save(any())).thenAnswer(i -> {
            var f = (com.aivoice.lead.entity.LeadFollowUp) i.getArgument(0);
            f.setId(UUID.randomUUID());
            return f;
        });
        when(leads.findDuplicateCandidates(any(), any(), any(), any())).thenReturn(List.of());
        crm = new CrmService(leads, history, assignments, activities, followUps, qualifications, agents, setup,
                attribution, conversions, access, mock(NamedParameterJdbcTemplate.class), clock, "INR", props);
    }

    Lead lead(UUID clientId) {
        Lead lead = new Lead();
        lead.setId(UUID.randomUUID());
        lead.setClientId(clientId);
        lead.setPhoneNumber("+919876543210");
        lead.setCreatedAt(NOW.minusSeconds(3600));
        when(leads.findById(lead.getId())).thenReturn(Optional.of(lead));
        when(leads.findByIdAndClientId(lead.getId(), clientId)).thenReturn(Optional.of(lead));
        return lead;
    }
}
