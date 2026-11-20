package com.aivoice.lead.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversionEventRecorderTest {

    static TrackingPixel pixel(boolean token, boolean custom) {
        TrackingPixel p = new TrackingPixel();
        p.setId(UUID.randomUUID());
        p.setClientId(UUID.randomUUID());
        p.setMetaPixelId("123456789");
        p.setEnabled(true);
        p.setEnabledEvents("PageView,Lead,Contact,QualifiedLead,Purchase");
        p.setForwardCustomEvents(custom);
        p.setAllowedDomains("example.com");
        if (token) {
            p.setCapiTokenCiphertext(new byte[] {1});
            p.setCapiTokenNonce(new byte[] {2});
        }
        return p;
    }

    @Test
    void deliveryStatusRequiresMappingConsentPixelAndToken() {
        TrackingPixel ready = pixel(true, false);
        assertEquals("pending", ConversionEventRecorder.initialDeliveryStatus(ready, EventName.Lead, "granted", Optional.of("Lead")));
        assertEquals("pending", ConversionEventRecorder.initialDeliveryStatus(ready, EventName.Lead, "not_required", Optional.of("Lead")));
        assertEquals("skipped_no_consent", ConversionEventRecorder.initialDeliveryStatus(ready, EventName.Lead, "denied", Optional.of("Lead")));
        assertEquals("skipped_no_consent", ConversionEventRecorder.initialDeliveryStatus(ready, EventName.Lead, "unknown", Optional.of("Lead")));
        assertEquals("not_applicable", ConversionEventRecorder.initialDeliveryStatus(ready, EventName.QualifiedLead, "granted", Optional.empty()));
        assertEquals("disabled", ConversionEventRecorder.initialDeliveryStatus(pixel(false, false), EventName.Lead, "granted", Optional.of("Lead")));
        assertEquals("disabled", ConversionEventRecorder.initialDeliveryStatus(ready, EventName.Schedule, "granted", Optional.of("Schedule")));
        assertEquals("disabled", ConversionEventRecorder.initialDeliveryStatus(null, EventName.Lead, "granted", Optional.of("Lead")));
    }

    @Test
    void duplicateDedupKeyReturnsTheExistingEventAndCountsIt() {
        ConversionEventRepository repo = mock(ConversionEventRepository.class);
        ConversionEvent existing = new ConversionEvent();
        existing.setId(UUID.randomUUID());
        when(repo.findByClientIdAndDedupKey(any(), any())).thenReturn(Optional.of(existing));
        ConversionEventRecorder recorder = new ConversionEventRecorder(repo, Clock.systemUTC());
        ConversionEvent incoming = new ConversionEvent();
        incoming.setClientId(UUID.randomUUID());
        incoming.setEventName("Lead");
        incoming.setEventId("evt-12345678");
        var recorded = recorder.record(incoming, pixel(true, false));
        assertTrue(recorded.duplicate());
        assertEquals("Lead:evt-12345678", incoming.getDedupKey());
        verify(repo).incrementDuplicates(existing.getId());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void eventsWithoutAdConsentAreStoredWithoutPersonalData() {
        ConversionEventRepository repo = mock(ConversionEventRepository.class);
        when(repo.findByClientIdAndDedupKey(any(), any())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        ConversionEventRecorder recorder = new ConversionEventRecorder(repo,
                Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC));
        ConversionEvent e = new ConversionEvent();
        e.setClientId(UUID.randomUUID());
        e.setEventName("Lead");
        e.setEventId("evt-12345678");
        e.setConsentStatus("denied");
        e.setFbc("fb.1.1.x");
        e.setFbp("fb.1.1.1");
        e.setClientIpAddress("203.0.113.1");
        e.setClientUserAgent("UA");
        e.setUserDataHashes(new LinkedHashMap<>(Map.of("em", "h")));
        ConversionEvent saved = recorder.record(e, pixel(true, false)).event();
        assertEquals("skipped_no_consent", saved.getMetaDeliveryStatus());
        assertNull(saved.getFbc());
        assertNull(saved.getClientIpAddress());
        assertTrue(saved.getUserDataHashes().isEmpty());
    }
}
