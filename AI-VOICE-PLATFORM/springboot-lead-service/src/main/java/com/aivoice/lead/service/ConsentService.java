package com.aivoice.lead.service;

import com.aivoice.lead.entity.LeadConsent;
import com.aivoice.lead.repository.LeadConsentRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Call consent for WhatsApp-initiated leads, plus the "do not contact me" vocabulary. */
@Service
public class ConsentService {

    public static final Set<String> CONSENT_KEYWORDS = Set.of("CALL", "YES CALL", "I AGREE", "YES");
    public static final Set<String> STOP_KEYWORDS = Set.of("STOP", "UNSUBSCRIBE", "DO NOT CALL", "DONT CALL");

    private static final Set<String> NOT_INTERESTED_PHRASES = Set.of(
            "NOT INTERESTED", "NO THANKS", "NO THANK YOU", "STOP CALLING", "REMOVE ME",
            "REMOVE MY NUMBER", "DELETE MY NUMBER", "DELETE MY DETAILS");

    private static final List<String> TELUGU_NOT_INTERESTED = List.of(
            "ఆసక్తి లేదు", "అవసరం లేదు", "కాల్ చేయవద్దు", "వద్దండి", "వద్దు");

    private final LeadConsentRepository consents;
    private final Clock clock;

    public ConsentService(LeadConsentRepository consents, Clock clock) {
        this.consents = consents;
        this.clock = clock;
    }

    private static String compact(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    public static boolean isConsent(String text) {
        return CONSENT_KEYWORDS.contains(compact(text).toUpperCase());
    }

    public static boolean isStop(String text) {
        return STOP_KEYWORDS.contains(compact(text).toUpperCase());
    }

    /** True when the person is declining further contact, in English or Telugu. */
    public static boolean isNotInterested(String text) {
        String compact = compact(text);
        if (compact.isEmpty()) {
            return false;
        }
        String upper = compact.toUpperCase();
        if (STOP_KEYWORDS.contains(upper) || NOT_INTERESTED_PHRASES.contains(upper)) {
            return true;
        }
        String lowered = compact.toLowerCase();
        if (lowered.contains("not interested") || lowered.contains("no thanks") || lowered.contains("stop calling")) {
            return true;
        }
        return TELUGU_NOT_INTERESTED.stream().anyMatch(compact::contains);
    }

    /** Stores consent when the message is an explicit agreement. Returns whether consent now exists. */
    @Transactional
    public boolean recordCallConsent(UUID leadId, String messageId, String text) {
        if (!isConsent(text)) {
            return false;
        }
        if (!consents.existsBySourceMessageId(messageId)) {
            LeadConsent consent = new LeadConsent();
            consent.setLeadId(leadId);
            consent.setEvidenceText(compact(text));
            consent.setSourceMessageId(messageId);
            consent.setConsentedAt(clock.instant());
            consents.save(consent);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveCallConsent(UUID leadId) {
        return consents.existsByLeadIdAndRevokedAtIsNull(leadId);
    }

    @Transactional
    public void revokeAll(UUID leadId) {
        consents.findByLeadIdAndRevokedAtIsNull(leadId).forEach(c -> c.setRevokedAt(clock.instant()));
    }
}
