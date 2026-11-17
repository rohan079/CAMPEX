package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.ClientDtos.UpdateClientVoiceSettingsRequest;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.repository.AdRepository;
import com.aivoice.lead.repository.AnalyticsRepository;
import com.aivoice.lead.repository.ClientRepository;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.VoiceCallRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientServiceTest {

    private ClientRepository clients;
    private LeadRepository leads;
    private VoiceCallRepository calls;
    private ClientService service;
    private Client client;

    @BeforeEach
    void setUp() {
        clients = mock(ClientRepository.class);
        leads = mock(LeadRepository.class);
        calls = mock(VoiceCallRepository.class);
        AnalyticsRepository analytics = mock(AnalyticsRepository.class);
        when(analytics.leadCountsPerClient()).thenReturn(List.of());
        client = new Client();
        client.setId(UUID.randomUUID());
        client.setName("Green Fields");
        client.setSlug("green-fields");
        client.setKnowledgeBase("Verified location: Guntur");
        client.setVoiceAgentEnabled(true);
        when(clients.findById(client.getId())).thenReturn(Optional.of(client));
        when(clients.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Lead clientLead = new Lead();
        clientLead.setId(UUID.randomUUID());
        when(leads.findByClientId(client.getId())).thenReturn(List.of(clientLead));
        AppProperties props = new AppProperties("development", "Default", "", "Asia/Kolkata", 7, 3,
                "", "", "", null, "http://voice", 5, 1);
        service = new ClientService(clients, leads, calls, mock(AdRepository.class), analytics, props);
    }

    @Test
    void disablingVoiceStoresKnowledgeAndCancelsQueuedCalls() {
        var dto = service.updateVoiceSettings(
                client.getId(), new UpdateClientVoiceSettingsRequest(false, "  Verified project: Guntur  "));

        assertFalse(dto.voiceAgentEnabled());
        assertEquals("Verified project: Guntur", dto.knowledgeBase());
        verify(calls).cancelQueued(any(UUID.class));
    }

    @Test
    void enablingVoiceKeepsQueuedCallsAndReturnsTheClientKnowledge() {
        var dto = service.updateVoiceSettings(
                client.getId(), new UpdateClientVoiceSettingsRequest(true, "Project: Green Fields\nArea: Guntur"));

        assertTrue(dto.voiceAgentEnabled());
        assertEquals("Project: Green Fields\nArea: Guntur", dto.knowledgeBase());
        verify(calls, never()).cancelQueued(any(UUID.class));
    }
}
