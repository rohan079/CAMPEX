package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.Dtos.CallEventRequest;
import com.aivoice.lead.entity.CallStatus;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadStatus;
import com.aivoice.lead.entity.VoiceCall;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.VoiceCallRepository;
import com.aivoice.lead.service.Exceptions.VoiceServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class CallOrchestratorServiceTest {

    private LeadRepository leads;
    private VoiceCallRepository calls;
    private LeadService leadService;
    private CallOrchestratorService orchestrator;
    private ClientService clients;
    private VoiceServiceClient voiceClient;
    private ConsentService consent;
    private Lead lead;
    private VoiceCall call;

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        calls = mock(VoiceCallRepository.class);
        leadService = mock(LeadService.class);
        clients = mock(ClientService.class);
        voiceClient = mock(VoiceServiceClient.class);
        consent = mock(ConsentService.class);
        Client client = new Client();
        client.setName("Acme");
        client.setVoiceAgentEnabled(true);
        when(clients.require(any())).thenReturn(client);
        when(clients.voiceAgentEnabled(any())).thenReturn(true);
        when(clients.businessName(any())).thenReturn("Acme");
        when(clients.businessDescription(any())).thenReturn("");
        when(clients.knowledgeBase(any())).thenReturn("");
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"), ZoneOffset.UTC);
        AppProperties props = new AppProperties("development", "Acme", "", "Asia/Kolkata", 7, 3, "", "", "", "",
                null, "http://localhost:8000", 5, 1);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        orchestrator = new CallOrchestratorService(leads, calls, consent, leadService,
                clients, voiceClient, new FollowUpPolicy(ZoneId.of("Asia/Kolkata"), 7), props, clock,
                txManager);

        lead = new Lead();
        lead.setSource(LeadStatus.SOURCE_AD_POPUP);
        lead.setStatus(LeadStatus.CALLED_OR_CALLING);
        lead.setFollowUpStart(LocalDate.of(2026, 11, 1));
        lead.setFollowUpEnd(LocalDate.of(2026, 11, 7));
        UUID leadId = UUID.randomUUID();
        lead.setId(leadId);
        call = new VoiceCall();
        call.setId(UUID.randomUUID());
        call.setLeadId(leadId);
        call.setStatus(CallStatus.INITIATED);
        when(calls.findById(call.getId())).thenReturn(Optional.of(call));
        when(leads.findById(leadId)).thenReturn(Optional.of(lead));
    }

    private CallEventRequest event(String type) {
        return new CallEventRequest(call.getId().toString(), "sess-1", type, null, null);
    }

    @Test
    void callMovesThroughRingingAnsweredClosedAndLeadResumesWaiting() {
        orchestrator.handleEvent(event("ringing"));
        assertEquals(CallStatus.RINGING, call.getStatus());
        assertEquals("sess-1", call.getOpenaiSessionId());

        orchestrator.handleEvent(event("answered"));
        assertEquals(CallStatus.IN_PROGRESS, call.getStatus());
        assertNotNull(call.getStartedAt());

        orchestrator.handleEvent(event("closed"));
        assertEquals(CallStatus.COMPLETED, call.getStatus());
        assertNotNull(call.getEndedAt());
        // The window is still open, so tomorrow's call can happen.
        assertEquals(LeadStatus.AWAITING_REPLY, lead.getStatus());
    }

    @Test
    void lateRingingNeverMovesAFinishedOrConnectedCallBackwards() {
        orchestrator.handleEvent(event("answered"));
        orchestrator.handleEvent(event("ringing"));
        assertEquals(CallStatus.IN_PROGRESS, call.getStatus());

        orchestrator.handleEvent(event("closed"));
        orchestrator.handleEvent(event("ringing"));
        assertEquals(CallStatus.COMPLETED, call.getStatus());
    }

    @Test
    void failedCallRecordsReasonAndLastWindowDayClosesFollowUp() {
        lead.setFollowUpEnd(LocalDate.of(2026, 11, 1)); // window already over on 2 Nov
        orchestrator.handleEvent(new CallEventRequest(call.getId().toString(), "sess-1", "failed", "busy", null));
        assertEquals(CallStatus.FAILED, call.getStatus());
        assertEquals("busy", call.getOutcome());
        assertEquals(LeadStatus.FOLLOW_UP_COMPLETE, lead.getStatus());
    }

    @Test
    void notInterestedDeletesTheLeadExceptingTheSessionAlreadyHungUp() {
        orchestrator.handleEvent(event("not_interested"));
        verify(leadService).removeLead(lead, "sess-1");
    }

    @Test
    void eventsForDeletedCallsAreIgnored() {
        UUID gone = UUID.randomUUID();
        when(calls.findById(gone)).thenReturn(Optional.empty());
        orchestrator.handleEvent(new CallEventRequest(gone.toString(), "sess-2", "closed", null, null));
        verify(leadService, never()).removeLead(any(), any());
    }

    @Test
    void callsThatNeverReportedAnEndAreClosedAndTheLeadIsReleased() {
        call.setStatus(CallStatus.IN_PROGRESS);
        VoiceCall dialling = new VoiceCall();
        dialling.setId(UUID.randomUUID());
        dialling.setLeadId(lead.getId());
        dialling.setStatus(CallStatus.INITIATING);
        when(calls.findByStatusInAndCreatedAtBefore(any(), any())).thenReturn(java.util.List.of(call, dialling));

        assertEquals(2, orchestrator.closeStaleCalls());

        assertEquals(CallStatus.COMPLETED, call.getStatus());
        assertEquals("lost_contact", call.getOutcome());
        assertEquals(LeadStatus.AWAITING_REPLY, lead.getStatus());
        // Possibly dialled, so it is flagged for a person rather than retried.
        assertEquals(CallStatus.INITIATION_UNKNOWN, dialling.getStatus());
    }

    @Test
    void unknownEventTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.handleEvent(event("exploded")));
    }

    @Test
    void callSummaryIsStoredWithoutChangingCallState() {
        orchestrator.saveSummary(call.getId().toString(), "Interested in a site visit next week.");
        assertEquals("Interested in a site visit next week.", call.getSummary());
        assertEquals(CallStatus.INITIATED, call.getStatus());
    }

    @Test
    void dispatchWaitsWhenConfiguredVoiceCapacityIsFull() {
        when(calls.countByStatusIn(CallStatus.CAPACITY)).thenReturn(5L);

        assertFalse(orchestrator.dispatchNext());
        verify(calls, never()).claimNextQueued();
    }

    @Test
    void adFollowUpCallIsBlockedWithoutRecordedCallConsent() {
        call.setStatus(CallStatus.QUEUED);
        when(calls.countByStatusIn(CallStatus.CAPACITY)).thenReturn(0L);
        when(calls.claimNextQueued()).thenReturn(Optional.of(call));
        when(consent.hasActiveCallConsent(lead.getId())).thenReturn(false);

        assertTrue(orchestrator.dispatchNext());
        assertEquals(CallStatus.BLOCKED, call.getStatus());
        verify(voiceClient, never()).startCall(any());
    }

    @Test
    void rejectedLeadCancelsQueuedCallBeforeVoiceDispatch() {
        call.setStatus(CallStatus.QUEUED);
        lead.setReviewStatus("rejected");
        when(calls.countByStatusIn(CallStatus.CAPACITY)).thenReturn(0L);
        when(calls.claimNextQueued()).thenReturn(Optional.of(call));
        when(consent.hasActiveCallConsent(lead.getId())).thenReturn(true);

        assertTrue(orchestrator.dispatchNext());
        assertEquals(CallStatus.CANCELLED, call.getStatus());
        verify(voiceClient, never()).startCall(any());
    }

    @Test
    void dailyFollowUpDoesNotQueueUntilExplicitConsentExists() {
        when(leads.findDueFollowUps(any())).thenReturn(java.util.List.of(lead));
        when(calls.findByLeadId(lead.getId())).thenReturn(java.util.List.of());
        when(consent.hasActiveCallConsent(lead.getId())).thenReturn(false);

        assertEquals(0, orchestrator.queueDueFollowUps());
        verify(calls, never()).save(any(VoiceCall.class));
    }

    @Test
    void adOnlyClientDoesNotQueueDailyVoiceFollowUp() {
        when(leads.findDueFollowUps(any())).thenReturn(java.util.List.of(lead));
        when(calls.findByLeadId(lead.getId())).thenReturn(java.util.List.of());
        when(clients.voiceAgentEnabled(any())).thenReturn(false);

        assertEquals(0, orchestrator.queueDueFollowUps());
        verify(calls, never()).save(any(VoiceCall.class));
    }

    @Test
    void voiceCapacityResponseRequeuesInsteadOfFailingTheCall() {
        call.setStatus(CallStatus.QUEUED);
        when(calls.countByStatusIn(CallStatus.CAPACITY)).thenReturn(0L);
        when(calls.claimNextQueued()).thenReturn(Optional.of(call));
        when(consent.hasActiveCallConsent(lead.getId())).thenReturn(true);
        when(voiceClient.startCall(any())).thenThrow(new VoiceServiceException("pool full", false, true, null));

        assertFalse(orchestrator.dispatchNext());
        assertEquals(CallStatus.QUEUED, call.getStatus());
        assertEquals(0, call.getAttempts());
    }
}
