package com.aivoice.lead.controller;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.TrackingDtos.BrowserEventRequest;
import com.aivoice.lead.dto.TrackingDtos.ConsentRequest;
import com.aivoice.lead.dto.TrackingDtos.ConversionEventDto;
import com.aivoice.lead.dto.TrackingDtos.EventPage;
import com.aivoice.lead.dto.TrackingDtos.EventSummary;
import com.aivoice.lead.dto.TrackingDtos.IngestResult;
import com.aivoice.lead.dto.TrackingDtos.TestEventRequest;
import com.aivoice.lead.service.tracking.ConversionEventService;
import com.aivoice.lead.service.tracking.ConversionEventService.EventFilter;
import com.aivoice.lead.service.tracking.TrackingIngestionService;
import com.aivoice.lead.service.tracking.TrackingIngestionService.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public website ingestion (campex.js) and the dashboard's event monitoring. */
@RestController
public class TrackingEventController {

    private static final int MAX_BODY_BYTES = 16_384;

    private final TrackingIngestionService ingestion;
    private final ConversionEventService events;
    private final ZoneId zone;

    public TrackingEventController(TrackingIngestionService ingestion, ConversionEventService events,
                                   AppProperties props) {
        this.ingestion = ingestion;
        this.events = events;
        this.zone = props.zone();
    }

    // ------------------------------------------------------------------ public (website SDK)

    @PostMapping("/api/tracking/events")
    public ResponseEntity<IngestResult> ingest(HttpServletRequest request, @Valid @RequestBody BrowserEventRequest body) {
        rejectLargeBody(request);
        try {
            IngestResult result = ingestion.ingest(body, context(request));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        } catch (DataIntegrityViolationException e) {
            // Two identical events raced past the dedup check; the unique key kept exactly one.
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IngestResult("duplicate", body.eventId(), null));
        }
    }

    @PostMapping("/api/tracking/consent")
    public ResponseEntity<Map<String, String>> consent(HttpServletRequest request,
                                                       @Valid @RequestBody ConsentRequest body) {
        rejectLargeBody(request);
        ingestion.recordConsent(body, context(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "recorded"));
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/api/clients/{clientId}/tracking/events")
    public EventPage list(@PathVariable String clientId,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(required = false, name = "event_name") String eventName,
                          @RequestParam(required = false) String source,
                          @RequestParam(required = false, name = "delivery_status") String deliveryStatus,
                          @RequestParam(required = false, name = "campaign_id") String campaignId,
                          @RequestParam(required = false, name = "lead_id") String leadId,
                          @RequestParam(defaultValue = "50") int limit,
                          @RequestParam(defaultValue = "0") long offset) {
        EventFilter filter = new EventFilter(Ids.instant(from, zone, false), Ids.instant(to, zone, true), eventName,
                source, deliveryStatus, campaignId, Ids.optionalUuid(leadId, "Lead"));
        return events.list(Ids.uuid(clientId, "Client"), filter, Ids.limit(limit), Math.max(offset, 0));
    }

    @GetMapping("/api/clients/{clientId}/tracking/events/summary")
    public EventSummary summary(@PathVariable String clientId,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        Instant end = to == null ? Instant.now() : Ids.instant(to, zone, true);
        Instant start = from == null ? end.minus(Duration.ofDays(30)) : Ids.instant(from, zone, false);
        return events.summary(Ids.uuid(clientId, "Client"), start, end);
    }

    @GetMapping("/api/clients/{clientId}/tracking/events/{eventId}")
    public ConversionEventDto get(@PathVariable String clientId, @PathVariable String eventId) {
        return events.get(Ids.uuid(clientId, "Client"), Ids.uuid(eventId, "Event"));
    }

    @PostMapping("/api/clients/{clientId}/tracking/events/test")
    public IngestResult test(@PathVariable String clientId, @Valid @RequestBody TestEventRequest body) {
        return events.sendTestEvent(Ids.uuid(clientId, "Client"), body);
    }

    private static void rejectLargeBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Event payload too large");
        }
    }

    /**
     * The client address is the right-most X-Forwarded-For entry (added by the platform's edge proxy, which the
     * browser cannot forge), falling back to the socket address.
     */
    static RequestContext context(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = request.getRemoteAddr();
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String last = hops[hops.length - 1].trim();
            if (last.matches("^[0-9a-fA-F:.]{3,45}$")) {
                ip = last;
            }
        }
        return new RequestContext(ip, request.getHeader("User-Agent"), request.getHeader("Origin"));
    }

}
