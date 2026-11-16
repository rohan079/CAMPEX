package com.aivoice.lead.service;

import com.aivoice.lead.dto.Dtos.CallEventRequest;
import com.aivoice.lead.dto.Dtos.StartCallRequest;
import com.aivoice.lead.dto.Dtos.StartCallResponse;
import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.entity.CallStatus;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadStatus;
import com.aivoice.lead.entity.VoiceCall;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.VoiceCallRepository;
import com.aivoice.lead.service.Exceptions.VoiceServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides which calls happen and when, hands them to the voice service, and applies
 * what the voice service reports back. This service owns call state; the voice service owns the phone line.
 */
@Service
public class CallOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(CallOrchestratorService.class);
    private static final int MAX_DISPATCH_PER_TICK = 5;
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    /** The voice service sends these; anything else is rejected. */
    static final String EVENT_RINGING = "ringing";
    static final String EVENT_ANSWERED = "answered";
    static final String EVENT_FAILED = "failed";
    static final String EVENT_CLOSED = "closed";
    static final String EVENT_NOT_INTERESTED = "not_interested";

    private final LeadRepository leads;
    private final VoiceCallRepository calls;
    private final ConsentService consent;
    private final LeadService leadService;
    private final ClientService clients;
    private final VoiceServiceClient voice;
    private final FollowUpPolicy followUp;
    private final AppProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CallOrchestratorService(LeadRepository leads, VoiceCallRepository calls, ConsentService consent,
                                   LeadService leadService, ClientService clients, VoiceServiceClient voice, FollowUpPolicy followUp,
                                   AppProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.leads = leads;
        this.calls = calls;
        this.consent = consent;
        this.leadService = leadService;
        this.clients = clients;
        this.voice = voice;
        this.followUp = followUp;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ scheduling

    @Scheduled(fixedDelayString = "${app.call-poll-seconds:3}s", initialDelayString = "5s")
    public void tick() {
        try {
            int queued = queueDueFollowUps();
            if (queued > 0) {
                log.info("Queued {} follow-up call(s)", queued);
            }
            for (int i = 0; i < MAX_DISPATCH_PER_TICK && dispatchNext(); i++) {
                // keep going while there is queued work
            }
        } catch (RuntimeException e) {
            log.error("Call scheduler tick failed", e);
        }
    }

    /** Adds today's call for every ad lead whose window is open, has not replied and has no call today. */
    public int queueDueFollowUps() {
        Integer queued = tx.execute(status -> {
            LocalDate today = followUp.localDate(clock.instant());
            int count = 0;
            for (Lead lead : leads.findDueFollowUps(today)) {
                if (!clients.voiceAgentEnabled(clients.require(lead.getClientId()))) {
                    calls.cancelQueued(lead.getId());
                    continue;
                }
                if ("rejected".equals(lead.getReviewStatus())) {
                    calls.cancelQueued(lead.getId());
                    continue;
                }
                List<VoiceCall> existing = calls.findByLeadId(lead.getId());
                if (lead.getFollowUpEnd() != null && lead.getFollowUpEnd().isBefore(today)) {
                    if (existing.stream().noneMatch(c -> CallStatus.ACTIVE.contains(c.getStatus()))) {
                        lead.setStatus(LeadStatus.FOLLOW_UP_COMPLETE);
                    }
                    continue;
                }
                if (!consent.hasActiveCallConsent(lead.getId())) {
                    continue;
                }
                if (!followUp.shouldQueue(today, lead.getFollowUpStart(), lead.getFollowUpEnd(), existing)) {
                    continue;
                }
                VoiceCall call = new VoiceCall();
                call.setLeadId(lead.getId());
                call.setStatus(CallStatus.QUEUED);
                call.setLanguage(LanguageService.normalize(lead.getPreferredLanguage()));
                calls.save(call);
                count++;
            }
            return count;
        });
        return queued == null ? 0 : queued;
    }

    private record Dispatch(UUID callId, StartCallRequest request) {
    }

    /** Claims one queued call and starts it. Returns false when nothing was waiting. */
    public boolean dispatchNext() {
        // The claim commits "initiating" before the network call, so a crash cannot dial twice.
        AtomicReference<Optional<Dispatch>> claimed = new AtomicReference<>(Optional.empty());
        boolean found = Boolean.TRUE.equals(tx.execute(status -> {
            if (calls.countByStatusIn(CallStatus.CAPACITY) >= props.voiceService().maxConcurrentCalls()) {
                return false;
            }
            Optional<VoiceCall> next = calls.claimNextQueued();
            if (next.isEmpty()) {
                return false;
            }
            claimed.set(claim(next.get()));
            return true;
        }));
        if (!found) {
            return false;
        }
        if (claimed.get().isEmpty()) {
            return true;
        }
        Dispatch dispatch = claimed.get().get();
        try {
            StartCallResponse response = voice.startCall(dispatch.request());
            tx.executeWithoutResult(status -> markStarted(dispatch.callId(), response.sessionId()));
        } catch (VoiceServiceException e) {
            if (e.isCapacityDeferred()) {
                log.info("Call {} deferred because voice capacity is full", dispatch.callId());
                tx.executeWithoutResult(status -> deferForVoiceCapacity(dispatch.callId()));
                return false;
            }
            log.warn("Call {} did not start (ambiguous={}): {}", dispatch.callId(), e.isAmbiguous(), e.getMessage());
            tx.executeWithoutResult(status -> markStartFailed(dispatch.callId(), e));
        }
        return true;
    }

    private Optional<Dispatch> claim(VoiceCall call) {
        Lead lead = leads.findById(call.getLeadId()).orElse(null);
        if (lead == null || LeadStatus.DO_NOT_CALL.equals(lead.getStatus())) {
            call.setStatus(CallStatus.BLOCKED);
            return Optional.empty();
        }
        if ("rejected".equals(lead.getReviewStatus())) {
            call.setStatus(CallStatus.CANCELLED);
            return Optional.empty();
        }
        Client client = clients.require(lead.getClientId());
        if (!clients.voiceAgentEnabled(client)) {
            call.setStatus(CallStatus.CANCELLED);
            return Optional.empty();
        }
        if (!consent.hasActiveCallConsent(lead.getId())) {
            call.setStatus(CallStatus.BLOCKED);
            return Optional.empty();
        }
        boolean followUpCall = lead.isAdLead() && lead.getRepliedAt() == null;
        String language = LanguageService.normalize(lead.getPreferredLanguage());
        call.setLanguage(language);
        call.setStatus(CallStatus.INITIATING);
        call.setAttempts(call.getAttempts() + 1);
        StartCallRequest request = new StartCallRequest(
                call.getId().toString(),
                lead.getId().toString(),
                lead.getPhoneNumber(),
                language,
                lead.getFirstName(),
                clients.businessName(client),
                clients.businessDescription(client),
                lead.getAdHeadline(),
                lead.getAdBody(),
                followUpCall,
                clients.knowledgeBase(client));
        return Optional.of(new Dispatch(call.getId(), request));
    }

    private void markStarted(UUID callId, String sessionId) {
        calls.findById(callId).ifPresent(call -> {
            call.setOpenaiSessionId(sessionId);
            if (call.getStartedAt() == null) {
                call.setStartedAt(clock.instant());
            }
            // A ringing/answered event may already have arrived; never move the call backwards.
            if (CallStatus.INITIATING.equals(call.getStatus())) {
                call.setStatus(CallStatus.INITIATED);
            }
            leads.findById(call.getLeadId()).ifPresent(lead -> lead.setStatus(LeadStatus.CALLED_OR_CALLING));
        });
    }

    private void markStartFailed(UUID callId, VoiceServiceException failure) {
        calls.findById(callId).ifPresent(call -> {
            if (failure.isAmbiguous()) {
                // The carrier may be ringing the customer. Do not retry; a person reconciles it.
                call.setStatus(CallStatus.INITIATION_UNKNOWN);
                call.setOutcome("initiation_unknown");
            } else {
                call.setStatus(CallStatus.FAILED);
                call.setOutcome("start_failed");
                call.setEndedAt(clock.instant());
                resumeLead(call.getLeadId());
            }
        });
    }

    private void deferForVoiceCapacity(UUID callId) {
        calls.findById(callId).ifPresent(call -> {
            if (CallStatus.INITIATING.equals(call.getStatus())) {
                call.setStatus(CallStatus.QUEUED);
                call.setAttempts(Math.max(0, call.getAttempts() - 1));
            }
        });
    }

    /**
     * The voice service keeps live calls in memory. If it restarts mid-call we never hear the end,
     * and the lead would stay "calling" forever. Calls that old are closed as lost contact.
     * A call stuck in "initiating" is different: the line may have been dialled, so it is flagged, not retried.
     */
    @Scheduled(fixedDelayString = "5m", initialDelayString = "1m")
    public void sweepStaleCalls() {
        try {
            tx.executeWithoutResult(status -> closeStaleCalls());
        } catch (RuntimeException e) {
            log.error("Stale call sweep failed", e);
        }
    }

    int closeStaleCalls() {
        List<VoiceCall> stale = calls.findByStatusInAndCreatedAtBefore(
                CallStatus.ACTIVE, clock.instant().minus(STALE_AFTER));
        for (VoiceCall call : stale) {
            if (CallStatus.INITIATING.equals(call.getStatus())) {
                call.setStatus(CallStatus.INITIATION_UNKNOWN);
                call.setOutcome("initiation_unknown");
            } else {
                finish(call, CallStatus.COMPLETED, "lost_contact");
            }
        }
        if (!stale.isEmpty()) {
            log.warn("Closed {} call(s) that never reported an end", stale.size());
        }
        return stale.size();
    }

    // ------------------------------------------------------------------ voice service callbacks

    /** Applies one event from the voice service. Safe to receive twice, and out of order. */
    @Transactional
    public void handleEvent(CallEventRequest event) {
        VoiceCall call = calls.findById(parseUuid(event.callId())).orElse(null);
        if (call == null) {
            // The lead was deleted (for example "not interested"); nothing left to update.
            log.info("Ignoring {} event for unknown call {}", event.type(), event.callId());
            return;
        }
        if (call.getOpenaiSessionId() == null && event.sessionId() != null) {
            call.setOpenaiSessionId(event.sessionId());
        }
        switch (event.type()) {
            case EVENT_RINGING -> advance(call, CallStatus.RINGING);
            case EVENT_ANSWERED -> {
                advance(call, CallStatus.IN_PROGRESS);
                if (call.getStartedAt() == null) {
                    call.setStartedAt(clock.instant());
                }
            }

            case EVENT_FAILED -> finish(call, CallStatus.FAILED, firstNonBlank(event.detail(), "call_failed"));
            case EVENT_CLOSED -> {
                if (!CallStatus.FAILED.equals(call.getStatus())) {
                    finish(call, CallStatus.COMPLETED, call.getOutcome());
                }
            }
            case EVENT_NOT_INTERESTED -> leads.findById(call.getLeadId())
                    .ifPresent(lead -> leadService.removeLead(lead, event.sessionId()));
            default -> throw new IllegalArgumentException("Unknown call event type: " + event.type());
        }
    }

    /** Store a bounded summary on the existing call row. Unknown/deleted calls are harmless no-ops. */
    @Transactional
    public void saveSummary(String callId, String summary) {
        calls.findById(parseUuid(callId)).ifPresent(call -> call.setSummary(summary));
    }

    private void advance(VoiceCall call, String status) {
        if (CallStatus.FINISHED.contains(call.getStatus())) {
            return;
        }
        if (CallStatus.IN_PROGRESS.equals(call.getStatus()) && CallStatus.RINGING.equals(status)) {
            return;
        }
        call.setStatus(status);
    }

    private void finish(VoiceCall call, String status, String outcome) {
        if (CallStatus.FINISHED.contains(call.getStatus()) && call.getEndedAt() != null) {
            return;
        }
        call.setStatus(status);
        if (outcome != null) {
            call.setOutcome(outcome);
        }
        call.setEndedAt(clock.instant());
        resumeLead(call.getLeadId());
    }

    /** After a call ends an ad lead goes back to waiting while the window is open, then closes. */
    private void resumeLead(UUID leadId) {
        leads.findById(leadId).ifPresent(lead -> {
            if (!lead.isAdLead() || lead.getRepliedAt() != null) {
                return;
            }
            LocalDate today = followUp.localDate(clock.instant());
            boolean open = lead.getFollowUpEnd() != null && !lead.getFollowUpEnd().isBefore(today);
            lead.setStatus(open ? LeadStatus.AWAITING_REPLY : LeadStatus.FOLLOW_UP_COMPLETE);
        });
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("call_id must be a UUID");
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
