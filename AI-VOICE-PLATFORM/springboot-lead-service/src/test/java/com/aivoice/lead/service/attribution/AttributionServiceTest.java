package com.aivoice.lead.service.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.dto.AttributionDtos.AttributionInput;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadAttribution;
import com.aivoice.lead.entity.LeadAttributionTouch;
import com.aivoice.lead.repository.AdRepository;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.repository.LeadAttributionRepository;
import com.aivoice.lead.repository.LeadAttributionTouchRepository;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.service.ClientAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AttributionServiceTest {

    private final Map<UUID, LeadAttributionTouch> touchStore = new HashMap<>();
    private LeadAttributionRepository attributions;
    private LeadAttributionTouchRepository touches;
    private AttributionService service;
    private Lead lead;
    private LeadAttribution saved;
    private Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        attributions = mock(LeadAttributionRepository.class);
        touches = mock(LeadAttributionTouchRepository.class);
        when(touches.save(any())).thenAnswer(i -> {
            LeadAttributionTouch t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            touchStore.put(t.getId(), t);
            return t;
        });
        when(touches.findById(any())).thenAnswer(i -> Optional.ofNullable(touchStore.get(i.getArgument(0))));
        when(attributions.save(any())).thenAnswer(i -> saved = i.getArgument(0));
        when(attributions.lockByLeadId(any())).thenAnswer(i -> Optional.ofNullable(saved));
        AdRepository ads = mock(AdRepository.class);
        when(ads.findByExternalId(any())).thenReturn(Optional.empty());
        lead = new Lead();
        lead.setId(UUID.randomUUID());
        lead.setClientId(UUID.randomUUID());
        service = new AttributionService(attributions, touches, mock(ConversionEventRepository.class), ads,
                mock(LeadRepository.class), mock(ClientAccessService.class), mock(NamedParameterJdbcTemplate.class),
                new Clock() {
                    public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                    public Clock withZone(java.time.ZoneId z) { return this; }
                    public Instant instant() { return now; }
                });
    }

    private static AttributionInput campaign(String id) {
        return new AttributionInput(null, id, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    void firstTouchIsPreservedAndLastTouchMoves() {
        LeadAttributionTouch first = service.recordTouch(lead, "ad_popup", campaign("111"));
        now = now.plusSeconds(3600);
        LeadAttributionTouch second = service.recordTouch(lead, "ctwa", campaign("222"));
        now = now.plusSeconds(3600);
        LeadAttributionTouch third = service.recordTouch(lead, "website", campaign("333"));
        assertEquals(first.getId(), saved.getFirstTouchId());
        assertEquals("111", touchStore.get(saved.getFirstTouchId()).getCampaignId());
        assertEquals(third.getId(), saved.getLastTouchId());
        assertEquals(3, saved.getTouchCount());
        assertEquals("ad_popup", saved.getAttributionSource());
        assertEquals("222", second.getCampaignId());
    }

    @Test
    void anUnknownVisitNeverReplacesAKnownLastTouch() {
        service.recordTouch(lead, "ad_popup", campaign("111"));
        now = now.plusSeconds(60);
        service.recordTouch(lead, "whatsapp", AttributionInput.empty());
        assertEquals("111", touchStore.get(saved.getLastTouchId()).getCampaignId());
        assertEquals(2, saved.getTouchCount());
    }

    @Test
    void repeatingTheSameSignalDoesNotGrowHistory() {
        service.recordTouch(lead, "ctwa", campaign("111"));
        service.recordTouch(lead, "ctwa", campaign("111"));
        assertEquals(1, touchStore.size());
        assertEquals(1, saved.getTouchCount());
    }

    @Test
    void unknownAttributionIsStoredAsUnknown() {
        LeadAttributionTouch t = service.recordTouch(lead, "whatsapp", null);
        assertEquals("unknown", t.getResolutionMethod());
        verify(attributions).save(any());
        verify(touches, never()).findByLeadIdOrderByTouchedAtAsc(any());
    }
}
