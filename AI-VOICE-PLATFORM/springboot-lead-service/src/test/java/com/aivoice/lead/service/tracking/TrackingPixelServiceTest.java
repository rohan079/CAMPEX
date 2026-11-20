package com.aivoice.lead.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.dto.TrackingDtos.PixelRequest;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.TrackingPixelRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.ForbiddenException;
import com.aivoice.lead.service.MetaTokenCipher;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrackingPixelServiceTest {

    private final UUID clientId = UUID.randomUUID();
    private TrackingPixelRepository pixels;
    private ClientAccessService access;
    private TrackingPixelService service;
    private MetaTokenCipher cipher;

    @BeforeEach
    void setUp() {
        pixels = mock(TrackingPixelRepository.class);
        access = mock(ClientAccessService.class);
        when(access.require(eq(clientId), any())).thenReturn(new Actor("owner@example.com", false));
        when(pixels.saveAndFlush(any())).thenAnswer(i -> {
            TrackingPixel p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(pixels.save(any())).thenAnswer(i -> i.getArgument(0));
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        cipher = new MetaTokenCipher(new MetaOAuthProperties("a", "b", "c", "v27.0", key, "f", "", List.of()));
        service = new TrackingPixelService(pixels, access, mock(AuditService.class), cipher,
                TrackingProperties.defaults(), Clock.systemUTC());
    }

    private PixelRequest request(String token) {
        return new PixelRequest("123456789", "Main", List.of("https://WWW.Example.com/path", "example.com"), true,
                List.of("PageView", "Lead"), false, null, "TEST1", token, null);
    }

    @Test
    void createStoresEncryptedTokenAndNeverReturnsIt() {
        var dto = service.create(clientId, request("EAA-secret"));
        assertTrue(dto.capiTokenConfigured());
        assertEquals(List.of("www.example.com", "example.com"), dto.allowedDomains());
        assertEquals(List.of("PageView", "Lead"), dto.enabledEvents());
        assertEquals("required", dto.consentMode());
        assertFalse(dto.toString().contains("EAA-secret"));
        verify(access).require(clientId, Permission.MANAGE);
    }

    @Test
    void tokenIsBoundToItsPixelAndRoundTrips() {
        TrackingPixel stored = new TrackingPixel();
        org.mockito.Mockito.doAnswer(i -> {
            TrackingPixel p = i.getArgument(0);
            p.setId(UUID.randomUUID());
            stored.setId(p.getId());
            return p;
        }).when(pixels).saveAndFlush(any());
        org.mockito.Mockito.doAnswer(i -> {
            TrackingPixel p = i.getArgument(0);
            stored.setCapiTokenCiphertext(p.getCapiTokenCiphertext());
            stored.setCapiTokenNonce(p.getCapiTokenNonce());
            return p;
        }).when(pixels).save(any());
        service.create(clientId, request("EAA-secret"));
        assertEquals("EAA-secret", service.capiToken(stored).orElseThrow());
    }

    @Test
    void rejectsDuplicatePixelsUnknownEventsAndBadDomains() {
        when(pixels.existsByClientIdAndMetaPixelIdAndDeletedAtIsNull(clientId, "123456789")).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.create(clientId, request(null)));
        when(pixels.existsByClientIdAndMetaPixelIdAndDeletedAtIsNull(clientId, "123456789")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.create(clientId, new PixelRequest("123456789", "M",
                List.of("example.com"), true, List.of("Hack"), false, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.create(clientId, new PixelRequest("123456789", "M",
                List.of("not a domain"), true, null, false, null, null, null, null)));
    }

    @Test
    void deleteIsSoftAndWipesTheToken() {
        TrackingPixel p = ConversionEventRecorderTest.pixel(true, false);
        p.setClientId(clientId);
        when(pixels.findByIdAndClientIdAndDeletedAtIsNull(p.getId(), clientId)).thenReturn(Optional.of(p));
        service.delete(clientId, p.getId());
        assertNotNull(p.getDeletedAt());
        assertNull(p.getCapiTokenCiphertext());
        assertFalse(p.isEnabled());
    }

    @Test
    void accessDenialPropagates() {
        when(access.require(eq(clientId), eq(Permission.MANAGE))).thenThrow(new ForbiddenException("no"));
        assertThrows(ForbiddenException.class, () -> service.create(clientId, request(null)));
    }

    @Test
    void hostMatchingCoversSubdomainsOnly() {
        assertTrue(TrackingPixelService.hostAllowed("example.com", "www.example.com"));
        assertTrue(TrackingPixelService.hostAllowed("example.com", "example.com"));
        assertFalse(TrackingPixelService.hostAllowed("example.com", "evilexample.com"));
        assertFalse(TrackingPixelService.hostAllowed("", "example.com"));
    }
}
