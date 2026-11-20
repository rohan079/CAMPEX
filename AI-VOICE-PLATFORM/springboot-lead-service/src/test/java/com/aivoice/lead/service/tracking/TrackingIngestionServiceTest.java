package com.aivoice.lead.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.dto.TrackingDtos.BrowserEventRequest;
import com.aivoice.lead.dto.TrackingDtos.Consent;
import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.repository.TrackingConsentRepository;
import com.aivoice.lead.repository.TrackingPixelRepository;
import com.aivoice.lead.service.Exceptions.ForbiddenException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.Exceptions.RateLimitedException;
import com.aivoice.lead.service.tracking.TrackingIngestionService.RequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TrackingIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private TrackingPixelRepository pixels;
    private ConversionEventRepository events;
    private TrackingIngestionService service;
    private TrackingPixel pixel;
    private final RequestContext ctx = new RequestContext("203.0.113.5", "Mozilla/5.0", "https://www.example.com");

    @BeforeEach
    void setUp() {
        pixels = mock(TrackingPixelRepository.class);
        events = mock(ConversionEventRepository.class);
        pixel = ConversionEventRecorderTest.pixel(true, false);
        when(pixels.findByClientIdAndMetaPixelIdAndDeletedAtIsNull(pixel.getClientId(), "123456789"))
                .thenReturn(Optional.of(pixel));
        when(events.findByClientIdAndDedupKey(any(), any())).thenReturn(Optional.empty());
        when(events.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        service = new TrackingIngestionService(pixels, mock(TrackingConsentRepository.class),
                new ConversionEventRecorder(events, clock), new TrackingRateLimiter(clock),
                TrackingProperties.defaults(), clock);
    }

    private BrowserEventRequest event(String name, Long time, Consent consent) {
        return new BrowserEventRequest(pixel.getClientId().toString(), "123456789", name, "evt-abcdef12", time,
                "website", "visitor-1", "session-1", "https://www.example.com/p", null, null, "IwAR_abc", null, null,
                "facebook", "paid", "diwali", null, null, "120200001", null, "120200009", consent,
                Map.of("em", "Person@Example.com"), Map.of("value", 5, "evil", "x"));
    }

    private static final Consent GRANTED = new Consent("granted", "granted", "granted");

    @Test
    void acceptsValidEventHashesIdentifiersDerivesFbcAndQueuesForMeta() {
        var result = service.ingest(event("Lead", NOW.getEpochSecond(), GRANTED), ctx);
        assertEquals("accepted", result.status());
        assertEquals("pending", result.deliveryStatus());
        ArgumentCaptor<ConversionEvent> saved = ArgumentCaptor.forClass(ConversionEvent.class);
        verify(events).saveAndFlush(saved.capture());
        ConversionEvent e = saved.getValue();
        assertEquals(UserDataHasher.sha256("person@example.com"), e.getUserDataHashes().get("em"));
        assertEquals("fb.1." + NOW.toEpochMilli() + ".IwAR_abc", e.getFbc());
        assertEquals("120200001", e.getCampaignId());
        assertEquals("Lead:evt-abcdef12", e.getDedupKey());
        assertFalse(((Map<?, ?>) e.getPayload().get("custom_data")).containsKey("evil"));
    }

    @Test
    void rejectsServerOnlyEventsAndEventsNotEnabledOnThePixel() {
        assertThrows(IllegalArgumentException.class, () -> service.ingest(event("Purchase", null, GRANTED), ctx));
        assertThrows(IllegalArgumentException.class, () -> service.ingest(event("QualifiedLead", null, GRANTED), ctx));
        assertThrows(IllegalArgumentException.class, () -> service.ingest(event("Schedule", null, GRANTED), ctx));
    }

    @Test
    void rejectsReplayedOldAndFutureEvents() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(event("Lead", NOW.minusSeconds(8 * 86400).getEpochSecond(), GRANTED), ctx));
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(event("Lead", NOW.plusSeconds(3600).getEpochSecond(), GRANTED), ctx));
    }

    @Test
    void enforcesAllowedDomainsAndUnknownPixels() {
        assertThrows(ForbiddenException.class, () -> service.ingest(event("Lead", null, GRANTED),
                new RequestContext("203.0.113.5", "UA", "https://evil.example.net")));
        assertThrows(ForbiddenException.class, () -> service.ingest(event("Lead", null, GRANTED),
                new RequestContext("203.0.113.5", "UA", "https://notexample.com")));
        var other = new BrowserEventRequest(pixel.getClientId().toString(), "999999", "Lead", "evt-abcdef12", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                GRANTED, null, null);
        assertThrows(NotFoundException.class, () -> service.ingest(other, ctx));
    }

    @Test
    void withoutAnalyticsConsentNothingIsStored() {
        var result = service.ingest(event("Lead", null, new Consent("denied", "denied", "denied")), ctx);
        assertEquals("ignored_no_consent", result.status());
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void analyticsOnlyConsentStoresEventButNeverSendsToMeta() {
        var result = service.ingest(event("Lead", null, new Consent("granted", "denied", "denied")), ctx);
        assertEquals("skipped_no_consent", result.deliveryStatus());
        ArgumentCaptor<ConversionEvent> saved = ArgumentCaptor.forClass(ConversionEvent.class);
        verify(events).saveAndFlush(saved.capture());
        assertNull(saved.getValue().getFbc());
        assertTrue(saved.getValue().getUserDataHashes().isEmpty());
    }

    @Test
    void duplicateBrowserEventIsReportedAsDuplicate() {
        ConversionEvent existing = new ConversionEvent();
        existing.setId(java.util.UUID.randomUUID());
        existing.setMetaDeliveryStatus("sent");
        when(events.findByClientIdAndDedupKey(pixel.getClientId(), "Lead:evt-abcdef12")).thenReturn(Optional.of(existing));
        assertEquals("duplicate", service.ingest(event("Lead", null, GRANTED), ctx).status());
        verify(events).incrementDuplicates(existing.getId());
    }

    @Test
    void rateLimitsPerIp() {
        TrackingProperties tight = new TrackingProperties(false, "x", 10, 50, 8, 30, 21600, 120, 7, 300, 2, 3000,
                16384, 395, 30);
        TrackingIngestionService limited = new TrackingIngestionService(pixels, mock(TrackingConsentRepository.class),
                new ConversionEventRecorder(events, clock), new TrackingRateLimiter(clock), tight, clock);
        limited.ingest(event("Lead", null, GRANTED), ctx);
        limited.ingest(event("Lead", null, GRANTED), ctx);
        assertThrows(RateLimitedException.class, () -> limited.ingest(event("Lead", null, GRANTED), ctx));
    }

    @Test
    void validatesClickIdentifierFormats() {
        assertNull(TrackingIngestionService.validFbp("not-a-cookie"));
        assertEquals("fb.1.1700000000000.123", TrackingIngestionService.validFbp("fb.1.1700000000000.123"));
        assertTrue(TrackingIngestionService.validFbc("fb.1.1700000000000.IwAR-x_y").isPresent());
        assertNull(TrackingIngestionService.derivedFbc("bad id!", NOW));
    }
}
