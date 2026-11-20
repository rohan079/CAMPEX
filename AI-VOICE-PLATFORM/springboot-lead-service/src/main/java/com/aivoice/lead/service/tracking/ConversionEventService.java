package com.aivoice.lead.service.tracking;

import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.dto.TrackingDtos.ConversionEventDto;
import com.aivoice.lead.dto.TrackingDtos.DayCount;
import com.aivoice.lead.dto.TrackingDtos.DeliveryDto;
import com.aivoice.lead.dto.TrackingDtos.EventPage;
import com.aivoice.lead.dto.TrackingDtos.EventSummary;
import com.aivoice.lead.dto.TrackingDtos.IngestResult;
import com.aivoice.lead.dto.TrackingDtos.NameCount;
import com.aivoice.lead.dto.TrackingDtos.TestEventRequest;
import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadAttribution;
import com.aivoice.lead.entity.LeadAttributionTouch;
import com.aivoice.lead.entity.MetaEventDelivery;
import com.aivoice.lead.entity.TrackingConsent;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.repository.LeadAttributionRepository;
import com.aivoice.lead.repository.LeadAttributionTouchRepository;
import com.aivoice.lead.repository.MetaEventDeliveryRepository;
import com.aivoice.lead.repository.OffsetPageRequest;
import com.aivoice.lead.repository.TrackingConsentRepository;
import com.aivoice.lead.repository.TrackingPixelRepository;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.Source;
import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Conversion events: CRM/server-side emission, monitoring queries, delivery summary and test events. */
@Service
public class ConversionEventService {

    /** Filters for the monitoring list and summary. All optional. */
    public record EventFilter(Instant from, Instant to, String eventName, String source, String deliveryStatus,
                              String campaignId, UUID leadId) {
    }

    private final ConversionEventRepository events;
    private final MetaEventDeliveryRepository deliveries;
    private final TrackingPixelRepository pixels;
    private final TrackingConsentRepository consents;
    private final LeadAttributionRepository attributions;
    private final LeadAttributionTouchRepository touches;
    private final ConversionEventRecorder recorder;
    private final ClientAccessService access;
    private final NamedParameterJdbcTemplate jdbc;
    private final TrackingProperties props;
    private final Clock clock;

    public ConversionEventService(ConversionEventRepository events, MetaEventDeliveryRepository deliveries,
                                  TrackingPixelRepository pixels, TrackingConsentRepository consents,
                                  LeadAttributionRepository attributions, LeadAttributionTouchRepository touches,
                                  ConversionEventRecorder recorder, ClientAccessService access,
                                  NamedParameterJdbcTemplate jdbc, TrackingProperties props, Clock clock) {
        this.events = events;
        this.deliveries = deliveries;
        this.pixels = pixels;
        this.consents = consents;
        this.attributions = attributions;
        this.touches = touches;
        this.recorder = recorder;
        this.access = access;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ CRM / server-side events

    /**
     * Records a server-side event for a lead (Contact, QualifiedLead, Schedule, SiteVisitCompleted, Purchase).
     * {@code eventId} must be deterministic for the business fact (e.g. "visit-{id}-completed") so repeats dedupe.
     * Runs inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConversionEvent recordForLead(Lead lead, EventName name, String eventId, Source source,
                                         Map<String, Object> customData) {
        TrackingPixel pixel = pixelFor(lead.getClientId(), name).orElse(null);
        ConversionEvent event = new ConversionEvent();
        event.setClientId(lead.getClientId());
        event.setLeadId(lead.getId());
        event.setEventName(name.name());
        event.setEventId(eventId);
        event.setEventSource(source.name());
        event.setEventTime(clock.instant());

        Map<String, Object> hashes = new LinkedHashMap<>();
        putHash(hashes, "ph", lead.getPhoneNumber());
        putHash(hashes, "em", lead.getEmail());
        putHash(hashes, "fn", lead.getFirstName());
        hashes.put("external_id", UserDataHasher.sha256("campex-lead:" + lead.getId()));

        Optional<LeadAttribution> attribution = attributions.findByLeadId(lead.getId());
        if (attribution.isPresent()) {
            List<LeadAttributionTouch> history = touches.findByLeadIdOrderByTouchedAtAsc(lead.getId());
            LeadAttributionTouch last = history.stream()
                    .filter(t -> t.getId().equals(attribution.get().getLastTouchId())).findFirst().orElse(null);
            if (last != null) {
                event.setCampaignId(last.getCampaignId());
                event.setAdsetId(last.getAdsetId());
                event.setAdId(last.getAdId());
            }
            // Click ids from the most recent touch that has them.
            for (int i = history.size() - 1; i >= 0; i--) {
                LeadAttributionTouch touch = history.get(i);
                if (event.getFbc() == null && touch.getFbc() != null) event.setFbc(touch.getFbc());
                if (event.getFbp() == null && touch.getFbp() != null) event.setFbp(touch.getFbp());
            }
            history.stream().map(LeadAttributionTouch::getMetaLeadId).filter(id -> id != null && !id.isBlank())
                    .findFirst().ifPresent(id -> hashes.put("lead_id", id));
        }
        event.setUserDataHashes(hashes);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (customData != null && !customData.isEmpty()) {
            payload.put("custom_data", customData);
        }
        payload.put("lead_event_source", "Campex CRM");
        event.setPayload(payload);
        event.setConsentStatus(leadConsentStatus(lead, pixel));
        return recorder.record(event, pixel).event();
    }

    /**
     * Consent for sending a lead's data back to Meta: the pixel's consent mode, else the latest decision recorded for
     * any website visitor linked to the lead or recorded by staff for the lead itself.
     */
    public String leadConsentStatus(Lead lead, TrackingPixel pixel) {
        if (pixel != null && "not_required".equals(pixel.getConsentMode())) {
            return "not_required";
        }
        List<String> visitorIds = new ArrayList<>(events.visitorIdsForLead(lead.getId()));
        visitorIds.add(leadVisitorId(lead.getId()));
        TrackingConsent latest = null;
        for (String visitorId : visitorIds) {
            Optional<TrackingConsent> consent = consents.findFirstByClientIdAndVisitorIdOrderByRecordedAtDesc(
                    lead.getClientId(), visitorId);
            if (consent.isPresent() && (latest == null || consent.get().getRecordedAt().isAfter(latest.getRecordedAt()))) {
                latest = consent.get();
            }
        }
        if (latest == null) {
            return "unknown";
        }
        return "granted".equals(latest.getAdStorage()) && "granted".equals(latest.getAdUserData()) ? "granted" : "denied";
    }

    public static String leadVisitorId(UUID leadId) {
        return "lead:" + leadId;
    }

    /** Staff records the lead's own ad-data consent (e.g. captured on a signed form or a recorded call). */
    @Transactional
    public void recordLeadConsent(Lead lead, boolean adDataGranted, String policyVersion, Actor actor) {
        TrackingConsent consent = new TrackingConsent();
        consent.setClientId(lead.getClientId());
        consent.setVisitorId(leadVisitorId(lead.getId()));
        String value = adDataGranted ? "granted" : "denied";
        consent.setAnalyticsStorage(value);
        consent.setAdStorage(value);
        consent.setAdUserData(value);
        consent.setSource("crm");
        consent.setPolicyVersion(policyVersion);
        consent.setRecordedAt(clock.instant());
        consents.save(consent);
    }

    /** First enabled pixel with a CAPI token that accepts the event; else any live pixel (status will be disabled). */
    Optional<TrackingPixel> pixelFor(UUID clientId, EventName name) {
        List<TrackingPixel> live = pixels.findByClientIdAndDeletedAtIsNullOrderByCreatedAtAsc(clientId);
        return live.stream()
                .filter(p -> p.isEnabled() && p.getCapiTokenCiphertext() != null
                        && TrackingEventCatalog.eventSet(p.getEnabledEvents()).contains(name))
                .findFirst()
                .or(() -> live.stream().findFirst());
    }

    private static void putHash(Map<String, Object> hashes, String key, String raw) {
        String hash = UserDataHasher.hash(key, raw);
        if (hash != null) {
            hashes.put(key, hash);
        }
    }

    // ------------------------------------------------------------------ test events

    @Transactional
    public IngestResult sendTestEvent(UUID clientId, TestEventRequest request) {
        access.require(clientId, Permission.MANAGE);
        UUID pixelId;
        try {
            pixelId = UUID.fromString(request.pixelId());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Pixel not found");
        }
        TrackingPixel pixel = pixels.findByIdAndClientIdAndDeletedAtIsNull(pixelId, clientId)
                .orElseThrow(() -> new NotFoundException("Pixel not found"));
        if (pixel.getTestEventCode() == null) {
            throw new IllegalArgumentException("Set a test event code on the pixel (Events Manager → Test events) first");
        }
        EventName name = TrackingEventCatalog.parse(request.eventName() == null ? "Lead" : request.eventName())
                .filter(e -> e.category() == TrackingEventCatalog.Category.META_STANDARD)
                .orElseThrow(() -> new IllegalArgumentException("Test events must be standard Meta events"));
        ConversionEvent event = new ConversionEvent();
        event.setClientId(clientId);
        event.setEventName(name.name());
        event.setEventId("test-" + UUID.randomUUID());
        event.setEventSource(Source.backend_api.name());
        event.setEventTime(clock.instant());
        // Synthetic identifier so Meta's Test Events tool can show the event; it matches no real person.
        event.setUserDataHashes(new LinkedHashMap<>(Map.of("external_id",
                UserDataHasher.sha256("campex-test:" + event.getEventId()))));
        event.setPayload(new LinkedHashMap<>(Map.of("test", true)));
        event.setConsentStatus("not_required");
        event.setTest(true);
        event.setTestEventCode(pixel.getTestEventCode());
        ConversionEvent saved = recorder.record(event, pixel).event();
        return new IngestResult("accepted", saved.getEventId(), saved.getMetaDeliveryStatus());
    }

    // ------------------------------------------------------------------ monitoring

    @Transactional(readOnly = true)
    public EventPage list(UUID clientId, EventFilter filter, int limit, long offset) {
        access.read(clientId);
        Specification<ConversionEvent> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("clientId"), clientId));
            if (filter.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("eventTime"), filter.from()));
            if (filter.to() != null) p.add(cb.lessThan(root.get("eventTime"), filter.to()));
            if (hasText(filter.eventName())) p.add(cb.equal(root.get("eventName"), filter.eventName()));
            if (hasText(filter.source())) p.add(cb.equal(root.get("eventSource"), filter.source()));
            if (hasText(filter.deliveryStatus())) p.add(cb.equal(root.get("metaDeliveryStatus"), filter.deliveryStatus()));
            if (hasText(filter.campaignId())) p.add(cb.equal(root.get("campaignId"), filter.campaignId()));
            if (filter.leadId() != null) p.add(cb.equal(root.get("leadId"), filter.leadId()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<ConversionEvent> page = events.findAll(spec,
                new OffsetPageRequest(offset, limit, Sort.by(Sort.Direction.DESC, "eventTime")));
        return new EventPage(page.getContent().stream().map(e -> toDto(e, null)).toList(),
                page.getTotalElements(), limit, offset);
    }

    @Transactional(readOnly = true)
    public ConversionEventDto get(UUID clientId, UUID eventId) {
        access.read(clientId);
        ConversionEvent event = events.findByIdAndClientId(eventId, clientId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        return toDto(event, deliveries.findByConversionEventIdOrderByAttemptNumberAsc(event.getId()));
    }

    @Transactional(readOnly = true)
    public EventSummary summary(UUID clientId, Instant from, Instant to) {
        access.read(clientId);
        MapSqlParameterSource params = new MapSqlParameterSource("cid", clientId)
                .addValue("f", Timestamp.from(from)).addValue("t", Timestamp.from(to));
        String where = " from conversion_events where client_id = :cid and event_time >= :f and event_time < :t and not is_test";
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("select meta_delivery_status s, count(*) n" + where + " group by s", params,
                rs -> { byStatus.put(rs.getString("s"), rs.getLong("n")); });
        List<NameCount> byName = jdbc.query("select event_name s, count(*) n" + where + " group by s order by n desc",
                params, (rs, i) -> new NameCount(rs.getString("s"), rs.getLong("n")));
        List<NameCount> bySource = jdbc.query("select event_source s, count(*) n" + where + " group by s order by n desc",
                params, (rs, i) -> new NameCount(rs.getString("s"), rs.getLong("n")));
        Long dupes = jdbc.queryForObject("select coalesce(sum(duplicate_count), 0)" + where, params, Long.class);
        List<DayCount> byDay = jdbc.query("""
                select to_char(date_trunc('day', event_time at time zone 'UTC'), 'YYYY-MM-DD') d, count(*) n,
                       count(*) filter (where meta_delivery_status = 'sent') sent,
                       count(*) filter (where meta_delivery_status = 'failed') failed""" + " " + where + " " + """
                 group by d order by d""", params,
                (rs, i) -> new DayCount(rs.getString("d"), rs.getLong("n"), rs.getLong("sent"), rs.getLong("failed")));

        long received = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long sent = byStatus.getOrDefault("sent", 0L);
        long failed = byStatus.getOrDefault("failed", 0L);
        long pending = byStatus.getOrDefault("pending", 0L) + byStatus.getOrDefault("retry", 0L)
                + byStatus.getOrDefault("sending", 0L);
        Double rate = sent + failed == 0 ? null : Math.round((double) sent / (sent + failed) * 10_000) / 10_000.0;
        List<NameCount> statusRows = byStatus.entrySet().stream()
                .map(e -> new NameCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(NameCount::count).reversed()).toList();
        return new EventSummary(received, sent, failed, pending, byStatus.getOrDefault("skipped_no_consent", 0L),
                byStatus.getOrDefault("not_applicable", 0L) + byStatus.getOrDefault("disabled", 0L),
                dupes == null ? 0 : dupes, rate, byName, bySource, statusRows, byDay, props.capiEnabled());
    }

    static ConversionEventDto toDto(ConversionEvent e, List<MetaEventDelivery> attempts) {
        List<String> identifiers = e.getUserDataHashes() == null ? List.of()
                : e.getUserDataHashes().keySet().stream().sorted().toList();
        return new ConversionEventDto(e.getId().toString(), e.getClientId().toString(), e.getEventId(),
                e.getEventName(), e.getEventCategory(), e.getMetaEventName(),
                e.getLeadId() == null ? null : e.getLeadId().toString(), e.getVisitorId(), e.getSessionId(),
                e.getPixelId() == null ? null : e.getPixelId().toString(), e.getCampaignId(), e.getAdsetId(),
                e.getAdId(), e.getEventSource(), e.getEventTime(), e.getReceivedAt(), e.getEventSourceUrl(),
                e.getPayload(), identifiers, e.getFbc() != null, e.getFbp() != null, e.getConsentStatus(),
                e.getMetaDeliveryStatus(), e.getDeliveryAttempts(), e.getNextAttemptAt(), e.getLastError(),
                e.getSentAt(), e.getDedupKey(), e.getDuplicateCount(), e.isTest(),
                attempts == null ? null : attempts.stream().map(d -> new DeliveryDto(d.getAttemptNumber(),
                        d.getHttpStatus(), d.getEventsReceived(), d.getFbtraceId(), d.getErrorCode(),
                        d.getErrorSubcode(), d.getErrorMessage(), d.isRetryable(), d.getOutcome(),
                        d.getAttemptedAt())).toList());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
