package com.aivoice.lead.service;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.Dtos.AdLeadRequest;
import com.aivoice.lead.dto.Dtos.AdLeadResponse;
import com.aivoice.lead.dto.Dtos.CallDto;
import com.aivoice.lead.dto.Dtos.CallPage;
import com.aivoice.lead.dto.Dtos.CallStats;
import com.aivoice.lead.dto.Dtos.DashboardDto;
import com.aivoice.lead.dto.Dtos.LanguageDto;
import com.aivoice.lead.dto.Dtos.LeadDto;
import com.aivoice.lead.dto.Dtos.LeadPage;
import com.aivoice.lead.dto.Dtos.LeadReviewStats;
import com.aivoice.lead.dto.Dtos.LeadStats;
import com.aivoice.lead.entity.CallStatus;
import com.aivoice.lead.entity.Client;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadStatus;
import com.aivoice.lead.entity.VoiceCall;
import com.aivoice.lead.entity.WhatsAppEvent;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.NameCount;
import com.aivoice.lead.repository.OffsetPageRequest;
import com.aivoice.lead.repository.VoiceCallRepository;
import com.aivoice.lead.repository.WhatsAppEventRepository;
import com.aivoice.lead.service.Exceptions.MessageDeliveryException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.LanguageService.Kind;
import com.aivoice.lead.service.WhatsAppService.InboundMessage;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Leads: how they arrive, what they say back, and when they are forgotten. */
@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    /** Result of handling one inbound message. {@code replyText} is sent back to the sender when present. */
    public record InboundResult(String replyText, String outcome) {
        static InboundResult duplicate() {
            return new InboundResult(null, "duplicate");
        }
    }

    private final LeadRepository leads;
    private final VoiceCallRepository calls;
    private final WhatsAppEventRepository events;
    private final ConsentService consent;
    private final LanguageService languages;
    private final FollowUpPolicy followUp;
    private final WhatsAppService whatsApp;
    private final VoiceServiceClient voice;
    private final LeadMapper mapper;
    private final ClientService clients;
    private final AppProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public LeadService(LeadRepository leads, VoiceCallRepository calls, WhatsAppEventRepository events,
                       ConsentService consent, LanguageService languages, FollowUpPolicy followUp,
                       WhatsAppService whatsApp, VoiceServiceClient voice, LeadMapper mapper,
                       ClientService clients, AppProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.leads = leads;
        this.calls = calls;
        this.events = events;
        this.consent = consent;
        this.languages = languages;
        this.followUp = followUp;
        this.whatsApp = whatsApp;
        this.voice = voice;
        this.mapper = mapper;
        this.clients = clients;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ ad popup

    private record Prepared(UUID leadId, String message, boolean alreadyReplied, String phone) {
    }

    /**
     * An ad popup submission: store the lead, send the ad details on WhatsApp,
     * and open the one-week follow-up window that starts tomorrow.
     */
    public AdLeadResponse registerAdLead(AdLeadRequest request) {
        String phone = PhoneNumbers.normalize(request.phoneNumber());
        Prepared prepared = tx.execute(status -> prepareAdLead(phone, request));
        try {
            whatsApp.sendText(PhoneNumbers.forWhatsApp(phone), prepared.message());
        } catch (RuntimeException e) {
            if (!prepared.alreadyReplied()) {
                tx.executeWithoutResult(status ->
                        leads.findById(prepared.leadId()).ifPresent(l -> l.setStatus(LeadStatus.MESSAGE_FAILED)));
            }
            throw new MessageDeliveryException("WhatsApp could not deliver the ad details", e);
        }
        return tx.execute(status -> {
            Lead lead = leads.findById(prepared.leadId()).orElseThrow(() -> new NotFoundException("Lead not found"));
            if (prepared.alreadyReplied()) {
                return adResponse(lead, "already_replied");
            }
            Instant now = clock.instant();
            lead.setOutreachSentAt(now);
            lead.setStatus(LeadStatus.AWAITING_REPLY);
            lead.setRepliedAt(null);
            if (lead.getFollowUpStart() == null || lead.getFollowUpEnd().isBefore(followUp.localDate(now))) {
                scheduleFollowUp(lead, now);
            }
            return adResponse(lead, "outreach_sent");
        });
    }

    private Prepared prepareAdLead(String phone, AdLeadRequest request) {
        Client client = clients.resolve(request.client(), request.adId(), request.campaignId());
        Optional<Lead> existing = findByPhone(phone, client.getId());
        String language = hasText(request.language()) ? LanguageService.normalize(request.language())
                : existing.map(l -> LanguageService.normalize(l.getPreferredLanguage()))
                .orElseGet(() -> languages.detect(firstText(request.adBody(), request.adHeadline())).orElse("en"));
        String message = languages.adDetails(language, clients.businessName(client), request.adHeadline(),
                request.adBody(), clients.businessDescription(client));

        Lead lead = existing.orElseGet(() -> {
            Lead created = new Lead();
            created.setClientId(client.getId());
            created.setPhoneNumber(phone);
            created.setWhatsappUserId(PhoneNumbers.forWhatsApp(phone));
            created.setSource(LeadStatus.SOURCE_AD_POPUP);
            created.setStatus(LeadStatus.AWAITING_REPLY);
            return created;
        });
        boolean alreadyReplied = lead.getRepliedAt() != null;
        if (!alreadyReplied) {
            lead.setSource(LeadStatus.SOURCE_AD_POPUP);
            lead.setPreferredLanguage(language);
            if (lead.getInitialMessage() == null) {
                lead.setInitialMessage(message);
            }
        }
        if (hasText(request.name())) {
            lead.setFirstName(request.name());
        }
        fillAdFields(lead, request.adId(), request.campaignId(), request.adHeadline(), request.adBody());
        lead = leads.saveAndFlush(lead);
        return new Prepared(lead.getId(), message, alreadyReplied, phone);
    }

    private AdLeadResponse adResponse(Lead lead, String status) {
        return new AdLeadResponse(lead.getId().toString(), status, lead.getPhoneNumber(),
                lead.getFollowUpStart(), lead.getFollowUpEnd());
    }

    private void scheduleFollowUp(Lead lead, Instant now) {
        FollowUpPolicy.Window window = followUp.windowFrom(now);
        lead.setFollowUpStart(window.start());
        lead.setFollowUpEnd(window.end());
    }

    // ------------------------------------------------------------------ inbound WhatsApp

    @Transactional
    public InboundResult processInbound(InboundMessage m) {
        if (events.existsByMessageId(m.messageId())) {
            return InboundResult.duplicate();
        }
        // saveAndFlush so a concurrent retry hits the unique key here, not at commit.
        events.saveAndFlush(new WhatsAppEvent(m.messageId(), m.sender(), m.text()));

        String text = m.text() == null ? "" : m.text();
        AdFields ad = adFieldsFromReferral(m.referral());
        // An ad we know belongs to a client decides which client this person is a lead for.
        Optional<Client> adOwner = ad == null ? Optional.empty()
                : clients.byAd(firstText(ad.adId(), ad.campaignId()));
        Optional<Lead> found = findByPhone(m.sender(), adOwner.map(Client::getId).orElse(null));
        Optional<String> detected = languages.detect(text);

        if (found.isEmpty()) {
            return handleNewSender(m, text, ad, adOwner.orElseGet(clients::defaultClient), detected.orElse("en"));
        }

        Lead lead = found.get();
        detected.ifPresent(language -> setPreferredLanguage(lead, language));
        String language = LanguageService.normalize(lead.getPreferredLanguage());
        if (hasText(m.name()) && !hasText(lead.getFirstName())) {
            lead.setFirstName(m.name());
        }
        if (ad != null) {
            fillAdFields(lead, ad.adId(), ad.campaignId(), ad.headline(), ad.body());
        }

        if (ConsentService.isNotInterested(text)) {
            consent.revokeAll(lead.getId());
            removeLead(lead, null);
            return new InboundResult(languages.message(Kind.REMOVED, language), "not_interested");
        }

        if (lead.isAdLead() && lead.getOutreachSentAt() != null && lead.getRepliedAt() == null) {
            return markReplied(lead, text, m.messageId(), language);
        }

        if (consent.recordCallConsent(lead.getId(), m.messageId(), text)) {
            if (!clients.voiceAgentEnabled(clients.require(lead.getClientId()))
                    || "rejected".equals(lead.getReviewStatus())) {
                lead.setStatus(LeadStatus.REPLIED);
                return new InboundResult(languages.message(Kind.CONSENTED_NO_VOICE_FOLLOWUP, language), "consented_no_voice_followup");
            }
            lead.setStatus(LeadStatus.CALL_QUEUED);
            if (!calls.existsByLeadIdAndStatusIn(lead.getId(), Set.of(
                    CallStatus.QUEUED, CallStatus.INITIATING, CallStatus.INITIATED,
                    CallStatus.IN_PROGRESS, CallStatus.COMPLETED))) {
                queueCall(lead.getId(), language);
            }
            return new InboundResult(languages.message(Kind.CONSENTED, language), "consented");
        }

        if (languages.isLanguageRequest(text)) {
            boolean active = calls.existsByLeadIdAndStatusIn(lead.getId(), CallStatus.ACTIVE);
            return new InboundResult(languageReply(lead, language, active), "language_updated");
        }
        return new InboundResult(languages.message(Kind.CONSENT, language), "awaiting_consent");
    }

    private InboundResult handleNewSender(InboundMessage m, String text, AdFields ad, Client client, String language) {
        if (ConsentService.isNotInterested(text)) {
            return new InboundResult(languages.message(Kind.REMOVED, language), "not_interested");
        }
        Lead lead = new Lead();
        lead.setClientId(client.getId());
        lead.setPhoneNumber(PhoneNumbers.forStorage(m.sender()));
        lead.setWhatsappUserId(m.sender());
        lead.setFirstName(m.name());
        lead.setInitialMessage(text);
        lead.setReferral(m.referral());
        lead.setPreferredLanguage(language);
        if (ad != null) {
            lead.setSource(LeadStatus.SOURCE_CTWA);
            lead.setStatus(LeadStatus.AWAITING_REPLY);
            Instant now = clock.instant();
            lead.setOutreachSentAt(now);
            fillAdFields(lead, ad.adId(), ad.campaignId(), ad.headline(), ad.body());
            scheduleFollowUp(lead, now);
            leads.save(lead);
            String details = languages.adDetails(language, clients.businessName(client), lead.getAdHeadline(),
                    lead.getAdBody(), clients.businessDescription(client));
            return new InboundResult(details, "ad_outreach");
        }
        lead.setSource(LeadStatus.SOURCE_WHATSAPP);
        lead.setStatus(LeadStatus.AWAITING_CONSENT);
        if (m.referral() != null) {
            lead.setCampaignId(asString(m.referral().get("source_id")));
            lead.setAdId(asString(m.referral().get("ad_id")));
        }
        leads.save(lead);
        return new InboundResult(languages.message(Kind.CONSENT, language), "new_lead");
    }

    /** An ad lead answered: stop the daily calls, and honour an explicit CALL. */
    private InboundResult markReplied(Lead lead, String text, String messageId, String language) {
        lead.setRepliedAt(clock.instant());
        lead.setStatus(LeadStatus.REPLIED);
        calls.cancelQueued(lead.getId());
        if (consent.recordCallConsent(lead.getId(), messageId, text)) {
            if (!clients.voiceAgentEnabled(clients.require(lead.getClientId()))
                    || "rejected".equals(lead.getReviewStatus())) {
                lead.setStatus(LeadStatus.REPLIED);
                return new InboundResult(languages.message(Kind.CONSENTED_NO_VOICE_FOLLOWUP, language), "consented_no_voice_followup");
            }
            if (!calls.existsByLeadIdAndStatusIn(lead.getId(), Set.of(
                    CallStatus.QUEUED, CallStatus.INITIATING, CallStatus.INITIATED, CallStatus.IN_PROGRESS))) {
                queueCall(lead.getId(), language);
            }
            lead.setStatus(LeadStatus.CALL_QUEUED);
            return new InboundResult(languages.message(Kind.CONSENTED, language), "consented");
        }
        if (languages.isLanguageRequest(text)) {
            return new InboundResult(languages.message(Kind.LANGUAGE, language) + "\n\n"
                    + languages.message(Kind.REPLIED, language), "replied");
        }
        return new InboundResult(languages.message(Kind.REPLIED, language), "replied");
    }

    private String languageReply(Lead lead, String language, boolean callActive) {
        String ack = languages.message(Kind.LANGUAGE, language);
        boolean telugu = "te".equals(language);
        if (callActive) {
            return telugu
                    ? ack + " ఇప్పుడు జరుగుతున్న కాల్ భాష మారదు. తర్వాతి కాల్ తెలుగులో ఉంటుంది."
                    : ack + " The call already in progress keeps its current language. The next call will use English.";
        }
        if (LeadStatus.AWAITING_CONSENT.equals(lead.getStatus())) {
            return ack + "\n\n" + languages.message(Kind.CONSENT, language);
        }
        if (LeadStatus.CALL_QUEUED.equals(lead.getStatus()) || LeadStatus.AWAITING_REPLY.equals(lead.getStatus())) {
            return telugu ? ack + " వరుసలో ఉన్న కాల్ తెలుగులో ఉంటుంది."
                    : ack + " The queued call will be in English.";
        }
        return ack;
    }

    // ------------------------------------------------------------------ language, removal

    @Transactional
    public LeadDto updateLanguage(UUID leadId, String language) {
        if (!LanguageService.isSupported(language)) {
            throw new IllegalArgumentException("Supported languages: " + String.join(", ", LanguageService.SUPPORTED));
        }
        Lead lead = leads.findById(leadId).orElseThrow(() -> new NotFoundException("Lead not found"));
        setPreferredLanguage(lead, language.trim().toLowerCase());
        return detail(lead);
    }

    @Transactional
    public LeadDto updateReviewStatus(UUID leadId, String reviewStatus) {
        if (!Set.of("pending", "accepted", "rejected").contains(reviewStatus)) {
            throw new IllegalArgumentException("Review status must be pending, accepted, or rejected");
        }
        Lead lead = leads.findById(leadId).orElseThrow(() -> new NotFoundException("Lead not found"));
        lead.setReviewStatus(reviewStatus);
        if ("rejected".equals(reviewStatus)) {
            calls.cancelQueued(leadId);
        }
        return detail(lead);
    }

    @Transactional(readOnly = true)
    public LeadReviewStats leadReviewStats(UUID clientId) {
        if (clientId == null) {
            return new LeadReviewStats(
                    leads.countByReviewStatus("pending"),
                    leads.countByReviewStatus("accepted"),
                    leads.countByReviewStatus("rejected"));
        }
        return new LeadReviewStats(
                leads.countByClientIdAndReviewStatus(clientId, "pending"),
                leads.countByClientIdAndReviewStatus(clientId, "accepted"),
                leads.countByClientIdAndReviewStatus(clientId, "rejected"));
    }

    private void setPreferredLanguage(Lead lead, String language) {
        lead.setPreferredLanguage(language);
        if (lead.getId() != null) {
            calls.updateQueuedLanguage(lead.getId(), language);
        }
    }

    /**
     * Deletes the lead, its consents and its calls, and (after commit) hangs up any live call
     * other than {@code exceptSessionId}, which the voice service has already ended.
     */
    @Transactional
    public void removeLead(Lead lead, String exceptSessionId) {
        List<String> liveSessions = calls.findByLeadId(lead.getId()).stream()
                .filter(c -> CallStatus.ACTIVE.contains(c.getStatus()))
                .map(VoiceCall::getOpenaiSessionId)
                .filter(id -> id != null && !id.equals(exceptSessionId))
                .toList();
        leads.delete(lead);
        leads.flush();
        runAfterCommit(() -> liveSessions.forEach(session -> {
            try {
                voice.hangup(session);
            } catch (RuntimeException e) {
                log.warn("Could not hang up session {} after lead removal", session, e);
            }
        }));
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void queueCall(UUID leadId, String language) {
        VoiceCall call = new VoiceCall();
        call.setLeadId(leadId);
        call.setStatus(CallStatus.QUEUED);
        call.setLanguage(language);
        calls.save(call);
    }

    // ------------------------------------------------------------------ dashboard reads

    @Transactional(readOnly = true)
    public LeadPage listLeads(UUID clientId, String status, String reviewStatus, String language, String query,
                              int limit, long offset) {
        if (hasText(language) && !LanguageService.isSupported(language)) {
            throw new IllegalArgumentException("Supported languages: " + String.join(", ", LanguageService.SUPPORTED));
        }
        if (hasText(reviewStatus) && !Set.of("pending", "accepted", "rejected").contains(reviewStatus)) {
            throw new IllegalArgumentException("Review status must be pending, accepted, or rejected");
        }
        Page<Lead> page = leads.findAll(leadFilter(clientId, status, reviewStatus, language, query),
                new OffsetPageRequest(offset, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<LeadDto> rows = summaries(page.getContent());
        return new LeadPage(rows, page.getTotalElements(), limit, offset);
    }

    @Transactional(readOnly = true)
    public LeadDto getLead(UUID id) {
        Lead lead = leads.findById(id).orElseThrow(() -> new NotFoundException("Lead not found"));
        return detail(lead);
    }

    @Transactional(readOnly = true)
    public CallPage listCalls(UUID clientId, String status, String language, int limit, long offset) {
        Specification<VoiceCall> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (clientId != null) {
                var owned = query.subquery(UUID.class);
                var lead = owned.from(Lead.class);
                owned.select(lead.get("id")).where(cb.equal(lead.get("clientId"), clientId));
                predicates.add(root.get("leadId").in(owned));
            }
            if (hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (hasText(language)) {
                predicates.add(cb.equal(root.get("language"), language));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<VoiceCall> page = calls.findAll(spec,
                new OffsetPageRequest(offset, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new CallPage(callDtos(page.getContent()), page.getTotalElements(), limit, offset);
    }

    @Transactional(readOnly = true)
    public DashboardDto dashboard(UUID clientId) {
        Map<String, Long> leadStatus = counts(clientId == null ? leads.countByStatus() : leads.countByStatusForClient(clientId));
        Map<String, Long> leadLanguage = new LinkedHashMap<>();
        LanguageService.SUPPORTED.forEach(code -> leadLanguage.put(code, 0L));
        (clientId == null ? leads.countByLanguage() : leads.countByLanguageForClient(clientId))
                .forEach(c -> leadLanguage.merge(LanguageService.normalize(c.getName()), c.getTotal(), Long::sum));
        Map<String, Long> callStatus = counts(clientId == null ? calls.countByStatus() : calls.countByStatusForClient(clientId));

        List<LanguageDto> languageList = List.of(
                new LanguageDto("en", "English", "English"),
                new LanguageDto("te", "Telugu", "తెలుగు"));
        String name = clientId == null ? props.businessName() : clients.businessName(clients.require(clientId));
        List<Lead> recentLeads = clientId == null ? leads.findTop6ByOrderByCreatedAtDesc()
                : leads.findTop6ByClientIdOrderByCreatedAtDesc(clientId);
        List<VoiceCall> recentCalls = clientId == null ? calls.findTop6ByOrderByCreatedAtDesc()
                : calls.recentForClient(clientId, org.springframework.data.domain.PageRequest.of(0, 6));
        return new DashboardDto(
                name,
                languageList,
                new LeadStats(sum(leadStatus), leadStatus, leadLanguage),
                new CallStats(sum(callStatus), callStatus),
                summaries(recentLeads),
                callDtos(recentCalls));
    }

    private List<LeadDto> summaries(List<Lead> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<VoiceCall>> byLead = calls.findByLeadIdIn(rows.stream().map(Lead::getId).toList())
                .stream().collect(Collectors.groupingBy(VoiceCall::getLeadId));
        return rows.stream().map(l -> mapper.toSummary(l, byLead.getOrDefault(l.getId(), List.of()))).toList();
    }

    private LeadDto detail(Lead lead) {
        return mapper.toDetail(lead, calls.findByLeadId(lead.getId()));
    }

    private List<CallDto> callDtos(List<VoiceCall> rows) {
        Map<UUID, Lead> byId = leads.findAllById(rows.stream().map(VoiceCall::getLeadId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Lead::getId, Function.identity()));
        return rows.stream().map(c -> mapper.toCallDto(c, byId.get(c.getLeadId()))).toList();
    }

    private Map<String, Long> counts(Collection<NameCount> rows) {
        Map<String, Long> result = new HashMap<>();
        rows.forEach(row -> result.put(row.getName(), row.getTotal()));
        return result;
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Specification<Lead> leadFilter(
            UUID clientId, String status, String reviewStatus, String language, String query) {
        return (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (clientId != null) {
                predicates.add(cb.equal(root.get("clientId"), clientId));
            }
            if (hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (hasText(reviewStatus)) {
                predicates.add(cb.equal(root.get("reviewStatus"), reviewStatus));
            }
            if (hasText(language)) {
                predicates.add(cb.equal(root.get("preferredLanguage"), language));
            }
            if (hasText(query)) {
                String like = "%" + query.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("phoneNumber")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("firstName"), "")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Within one client when known; otherwise the person's most recent lead across clients. */
    private Optional<Lead> findByPhone(String raw, UUID clientId) {
        List<String> keys = PhoneNumbers.keys(raw);
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        return clientId == null
                ? leads.findFirstByPhoneNumberInOrderByCreatedAtDesc(keys)
                : leads.findFirstByClientIdAndPhoneNumberInOrderByCreatedAtDesc(clientId, keys);
    }

    private record AdFields(String adId, String campaignId, String headline, String body) {
    }

    /** Click-to-WhatsApp messages carry the ad the person tapped in a "referral" block. */
    static AdFields adFieldsFromReferral(Map<String, Object> referral) {
        if (referral == null || referral.isEmpty()) {
            return null;
        }
        String sourceType = String.valueOf(referral.getOrDefault("source_type", "")).toLowerCase();
        String headline = firstText(asString(referral.get("headline")), asString(referral.get("headline_text")));
        String body = firstText(asString(referral.get("body")), asString(referral.get("body_text")));
        String sourceId = asString(referral.get("source_id"));
        if (!"ad".equals(sourceType) && headline == null && body == null && sourceId == null) {
            return null;
        }
        return new AdFields(firstText(asString(referral.get("ad_id")), sourceId), sourceId, headline, body);
    }

    private static void fillAdFields(Lead lead, String adId, String campaignId, String headline, String body) {
        if (hasText(adId)) {
            lead.setAdId(adId);
        }
        if (hasText(campaignId)) {
            lead.setCampaignId(campaignId);
        }
        if (hasText(headline)) {
            lead.setAdHeadline(headline);
        }
        if (hasText(body)) {
            lead.setAdBody(body);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
