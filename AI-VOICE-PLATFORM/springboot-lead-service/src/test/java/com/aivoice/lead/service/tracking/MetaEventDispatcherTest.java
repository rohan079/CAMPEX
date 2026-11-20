package com.aivoice.lead.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.MetaEventDelivery;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.repository.MetaEventDeliveryRepository;
import com.aivoice.lead.repository.TrackingPixelRepository;
import com.aivoice.lead.service.tracking.MetaConversionsApiClient.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Meta API is always mocked here: nothing is ever sent to Meta from tests. */
class MetaEventDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private final Map<UUID, ConversionEvent> store = new HashMap<>();
    private ConversionEventRepository events;
    private MetaEventDeliveryRepository deliveries;
    private MetaConversionsApiClient client;
    private TrackingPixel pixel;
    private MetaEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        events = mock(ConversionEventRepository.class);
        when(events.findById(any())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        deliveries = mock(MetaEventDeliveryRepository.class);
        TrackingPixelRepository pixels = mock(TrackingPixelRepository.class);
        pixel = ConversionEventRecorderTest.pixel(true, false);
        when(pixels.findById(pixel.getId())).thenReturn(Optional.of(pixel));
        TrackingPixelService pixelService = mock(TrackingPixelService.class);
        when(pixelService.capiToken(pixel)).thenReturn(Optional.of("EAA-test-token"));
        client = mock(MetaConversionsApiClient.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TrackingProperties props = new TrackingProperties(true, "http://mock", 10, 50, 3, 30, 3600, 120, 7, 300,
                120, 3000, 16384, 395, 30);
        dispatcher = new MetaEventDispatcher(events, deliveries, pixels, pixelService, client,
                mock(NamedParameterJdbcTemplate.class), props, Clock.fixed(NOW, ZoneOffset.UTC), tm);
    }

    private ConversionEvent claimed(int attempts) {
        ConversionEvent e = new ConversionEvent();
        e.setId(UUID.randomUUID());
        e.setClientId(pixel.getClientId());
        e.setPixelId(pixel.getId());
        e.setEventName("Lead");
        e.setMetaEventName("Lead");
        e.setEventId("evt-" + UUID.randomUUID());
        e.setEventSource("manual_crm");
        e.setEventTime(NOW.minusSeconds(60));
        e.setUserDataHashes(new LinkedHashMap<>(Map.of("ph", UserDataHasher.sha256("919876543210"))));
        e.setPayload(new LinkedHashMap<>());
        e.setMetaDeliveryStatus("sending");
        e.setDeliveryAttempts(attempts);
        store.put(e.getId(), e);
        return e;
    }

    private static Result ok(int n) {
        return new Result(true, false, 200, n, "trace", null, null, null, Map.of());
    }

    private static Result transientError() {
        return new Result(false, true, 500, null, null, 2, null, "temporary", Map.of());
    }

    private static Result permanentError() {
        return new Result(false, false, 400, null, null, 100, null, "Invalid parameter", Map.of());
    }

    @Test
    void successfulDeliveryMarksSentAndRecordsTheAttempt() {
        ConversionEvent e = claimed(1);
        when(client.send(eq("123456789"), eq("EAA-test-token"), anyList(), any())).thenReturn(ok(1));
        dispatcher.deliverGroup(List.of(e));
        assertEquals("sent", e.getMetaDeliveryStatus());
        assertEquals(NOW, e.getSentAt());
        ArgumentCaptor<MetaEventDelivery> d = ArgumentCaptor.forClass(MetaEventDelivery.class);
        verify(deliveries).save(d.capture());
        assertEquals("sent", d.getValue().getOutcome());
        assertEquals(1, d.getValue().getAttemptNumber());
    }

    @Test
    void transientFailureRetriesWithExponentialBackoff() {
        ConversionEvent e = claimed(2);
        when(client.send(any(), any(), anyList(), any())).thenReturn(transientError());
        dispatcher.deliverGroup(List.of(e));
        assertEquals("retry", e.getMetaDeliveryStatus());
        assertEquals(NOW.plusSeconds(60), e.getNextAttemptAt());
        assertEquals(Duration.ofSeconds(30), dispatcher.backoff(1));
        assertEquals(Duration.ofSeconds(240), dispatcher.backoff(4));
        assertEquals(Duration.ofSeconds(3600), dispatcher.backoff(20));
    }

    @Test
    void retriesAreBoundedThenDeadLettered() {
        ConversionEvent e = claimed(3);
        when(client.send(any(), any(), anyList(), any())).thenReturn(transientError());
        dispatcher.deliverGroup(List.of(e));
        assertEquals("failed", e.getMetaDeliveryStatus());
    }

    @Test
    void permanentErrorsAreNotRetriedAndBadEventsAreIsolatedFromTheBatch() {
        ConversionEvent good = claimed(1);
        ConversionEvent bad = claimed(1);
        when(client.send(any(), any(), anyList(), any())).thenReturn(permanentError(), ok(1), permanentError());
        dispatcher.deliverGroup(List.of(good, bad));
        verify(client, times(3)).send(any(), any(), anyList(), any());
        assertEquals("sent", good.getMetaDeliveryStatus());
        assertEquals("failed", bad.getMetaDeliveryStatus());
    }

    @Test
    void expiredAndInvalidEventsFailWithoutCallingMeta() {
        ConversionEvent old = claimed(1);
        old.setEventTime(NOW.minus(Duration.ofDays(8)));
        ConversionEvent noIds = claimed(1);
        noIds.setUserDataHashes(new LinkedHashMap<>());
        dispatcher.deliverGroup(List.of(old, noIds));
        verify(client, never()).send(any(), any(), anyList(), any());
        assertEquals("failed", old.getMetaDeliveryStatus());
        assertEquals("failed", noIds.getMetaDeliveryStatus());
    }

    @Test
    void disabledPixelStopsDelivery() {
        pixel.setEnabled(false);
        ConversionEvent e = claimed(1);
        dispatcher.deliverGroup(List.of(e));
        assertEquals("disabled", e.getMetaDeliveryStatus());
        verify(client, never()).send(any(), any(), anyList(), any());
    }
}
