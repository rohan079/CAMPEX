package com.aivoice.lead.service.tracking;

import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared persistence step for every event source: decides the initial Meta delivery status, applies the
 * deduplication key and writes the row (which is also the CAPI outbox entry).
 */
@Component
public class ConversionEventRecorder {

    public record Recorded(ConversionEvent event, boolean duplicate) {
    }

    private final ConversionEventRepository events;
    private final Clock clock;

    public ConversionEventRecorder(ConversionEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public static String dedupKey(String eventName, String eventId) {
        return eventName + ":" + eventId;
    }

    /**
     * Initial Meta delivery status. Nothing reaches Meta unless the event maps to a Meta name, consent allows it, the
     * pixel is enabled for the event and has a Conversions API token.
     */
    public static String initialDeliveryStatus(TrackingPixel pixel, EventName name, String consentStatus,
                                               Optional<String> metaEventName) {
        if (metaEventName.isEmpty()) {
            return "not_applicable";
        }
        if (!"granted".equals(consentStatus) && !"not_required".equals(consentStatus)) {
            return "skipped_no_consent";
        }
        if (pixel == null || !pixel.isEnabled() || pixel.getDeletedAt() != null
                || pixel.getCapiTokenCiphertext() == null
                || !TrackingEventCatalog.eventSet(pixel.getEnabledEvents()).contains(name)) {
            return "disabled";
        }
        return "pending";
    }

    /** Persists the event unless one with the same dedup key exists, in which case that one is returned. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Recorded record(ConversionEvent event, TrackingPixel pixel) {
        EventName name = TrackingEventCatalog.parse(event.getEventName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown event: " + event.getEventName()));
        event.setEventCategory(name.categoryValue());
        event.setDedupKey(dedupKey(name.name(), event.getEventId()));
        Optional<ConversionEvent> existing = events.findByClientIdAndDedupKey(event.getClientId(), event.getDedupKey());
        if (existing.isPresent()) {
            events.incrementDuplicates(existing.get().getId());
            return new Recorded(existing.get(), true);
        }
        Optional<String> metaName = TrackingEventCatalog.metaEventName(name, pixel != null && pixel.isForwardCustomEvents());
        event.setMetaEventName(metaName.orElse(null));
        event.setPixelId(pixel == null ? null : pixel.getId());
        event.setReceivedAt(clock.instant());
        event.setMetaDeliveryStatus(initialDeliveryStatus(pixel, name, event.getConsentStatus(), metaName));
        if ("pending".equals(event.getMetaDeliveryStatus())) {
            event.setNextAttemptAt(clock.instant());
        }
        if (!"granted".equals(event.getConsentStatus()) && !"not_required".equals(event.getConsentStatus())) {
            stripPersonalData(event);
        }
        return new Recorded(events.saveAndFlush(event), false);
    }

    /** Without consent we keep the aggregate signal only: no identifiers, click ids or network data. */
    static void stripPersonalData(ConversionEvent event) {
        event.setUserDataHashes(new java.util.LinkedHashMap<>());
        event.setFbc(null);
        event.setFbp(null);
        event.setFbclid(null);
        event.setClientIpAddress(null);
        event.setClientUserAgent(null);
    }
}
