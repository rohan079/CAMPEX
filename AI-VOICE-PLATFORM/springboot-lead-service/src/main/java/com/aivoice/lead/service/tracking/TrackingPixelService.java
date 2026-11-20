package com.aivoice.lead.service.tracking;

import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.dto.TrackingDtos.PixelDto;
import com.aivoice.lead.dto.TrackingDtos.PixelRequest;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.TrackingPixelRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.MetaTokenCipher;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-client Meta Pixel configuration. The Conversions API token is encrypted at rest and write-only. */
@Service
public class TrackingPixelService {

    private static final Pattern HOSTNAME = Pattern.compile("^(?=.{1,253}$)([a-z0-9](-?[a-z0-9])*)(\\.[a-z0-9](-?[a-z0-9])*)+$");

    private final TrackingPixelRepository pixels;
    private final ClientAccessService access;
    private final AuditService audit;
    private final MetaTokenCipher cipher;
    private final TrackingProperties tracking;
    private final Clock clock;

    public TrackingPixelService(TrackingPixelRepository pixels, ClientAccessService access, AuditService audit,
                                MetaTokenCipher cipher, TrackingProperties tracking, Clock clock) {
        this.pixels = pixels;
        this.access = access;
        this.audit = audit;
        this.cipher = cipher;
        this.tracking = tracking;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PixelDto> list(UUID clientId) {
        access.read(clientId);
        return pixels.findByClientIdAndDeletedAtIsNullOrderByCreatedAtAsc(clientId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PixelDto get(UUID clientId, UUID pixelId) {
        access.read(clientId);
        return toDto(require(clientId, pixelId));
    }

    @Transactional
    public PixelDto create(UUID clientId, PixelRequest request) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        String metaPixelId = request.metaPixelId().trim();
        if (pixels.existsByClientIdAndMetaPixelIdAndDeletedAtIsNull(clientId, metaPixelId)) {
            throw new ConflictException("This Pixel ID is already configured for the client");
        }
        TrackingPixel pixel = new TrackingPixel();
        pixel.setClientId(clientId);
        pixel.setMetaPixelId(metaPixelId);
        pixel.setCreatedBy(actor.email());
        apply(pixel, request, actor);
        pixel = pixels.saveAndFlush(pixel);
        // The token's associated data binds it to this pixel row, so it cannot be replayed onto another pixel.
        storeToken(pixel, request);
        audit.record(clientId, actor.email(), "tracking_pixel", pixel.getId(), "created", auditView(pixel));
        return toDto(pixels.save(pixel));
    }

    @Transactional
    public PixelDto update(UUID clientId, UUID pixelId, PixelRequest request) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        TrackingPixel pixel = require(clientId, pixelId);
        String metaPixelId = request.metaPixelId().trim();
        if (!metaPixelId.equals(pixel.getMetaPixelId())) {
            if (pixels.existsByClientIdAndMetaPixelIdAndDeletedAtIsNull(clientId, metaPixelId)) {
                throw new ConflictException("This Pixel ID is already configured for the client");
            }
            pixel.setMetaPixelId(metaPixelId);
        }
        Map<String, Object> before = auditView(pixel);
        apply(pixel, request, actor);
        storeToken(pixel, request);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("before", before);
        changes.put("after", auditView(pixel));
        audit.record(clientId, actor.email(), "tracking_pixel", pixel.getId(), "updated", changes);
        return toDto(pixels.save(pixel));
    }

    /** Soft delete: past events keep their pixel reference for reporting; the token is wiped. */
    @Transactional
    public void delete(UUID clientId, UUID pixelId) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        TrackingPixel pixel = require(clientId, pixelId);
        pixel.setDeletedAt(clock.instant());
        pixel.setEnabled(false);
        pixel.setCapiTokenCiphertext(null);
        pixel.setCapiTokenNonce(null);
        pixel.setUpdatedBy(actor.email());
        audit.record(clientId, actor.email(), "tracking_pixel", pixel.getId(), "deleted", auditView(pixel));
    }

    /** Decrypted token for the dispatcher only. Never expose this through an API. */
    @Transactional(readOnly = true)
    public Optional<String> capiToken(TrackingPixel pixel) {
        if (pixel.getCapiTokenCiphertext() == null || !cipher.isConfigured()) {
            return Optional.empty();
        }
        return Optional.of(cipher.decrypt(pixel.getCapiTokenCiphertext(), pixel.getCapiTokenNonce(), aad(pixel.getId())));
    }

    TrackingPixel require(UUID clientId, UUID pixelId) {
        return pixels.findByIdAndClientIdAndDeletedAtIsNull(pixelId, clientId)
                .orElseThrow(() -> new NotFoundException("Pixel not found"));
    }

    private void apply(TrackingPixel pixel, PixelRequest request, Actor actor) {
        pixel.setName(request.name().trim());
        pixel.setAllowedDomains(String.join(",", normalizeDomains(request.allowedDomains())));
        pixel.setEnabled(request.enabled() == null || request.enabled());
        List<String> errors = new ArrayList<>();
        String events = TrackingEventCatalog.parseEventList(
                request.enabledEvents() == null ? null : String.join(",", request.enabledEvents()),
                TrackingEventCatalog.DEFAULT_PIXEL_EVENTS, errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        pixel.setEnabledEvents(events);
        pixel.setForwardCustomEvents(Boolean.TRUE.equals(request.forwardCustomEvents()));
        pixel.setConsentMode(request.consentMode() == null ? "required" : request.consentMode());
        pixel.setTestEventCode(request.testEventCode() == null || request.testEventCode().isBlank()
                ? null : request.testEventCode().trim());
        pixel.setUpdatedBy(actor.email());
    }

    private void storeToken(TrackingPixel pixel, PixelRequest request) {
        if (Boolean.TRUE.equals(request.clearCapiToken())) {
            pixel.setCapiTokenCiphertext(null);
            pixel.setCapiTokenNonce(null);
            return;
        }
        String token = request.capiAccessToken() == null ? "" : request.capiAccessToken().trim();
        if (token.isEmpty()) {
            return;
        }
        if (!cipher.isConfigured()) {
            throw new IllegalStateException("META_TOKEN_ENCRYPTION_KEY must be configured to store a Conversions API token");
        }
        MetaTokenCipher.EncryptedValue encrypted = cipher.encrypt(token, aad(pixel.getId()));
        pixel.setCapiTokenCiphertext(encrypted.ciphertext());
        pixel.setCapiTokenNonce(encrypted.nonce());
    }

    static String aad(UUID pixelId) {
        return "capi:" + pixelId;
    }

    static List<String> normalizeDomains(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String host = entry.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("[/:].*$", "");
            if (!HOSTNAME.matcher(host).matches()) {
                throw new IllegalArgumentException("Not a valid domain: " + entry.trim());
            }
            if (!result.contains(host)) {
                result.add(host);
            }
        }
        return result;
    }

    /** True when {@code host} equals an allowed domain or is a subdomain of one. */
    public static boolean hostAllowed(String allowedCsv, String host) {
        if (host == null || allowedCsv == null || allowedCsv.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return Arrays.stream(allowedCsv.split(",")).map(String::trim).filter(d -> !d.isEmpty())
                .anyMatch(d -> h.equals(d) || h.endsWith("." + d));
    }

    private Map<String, Object> auditView(TrackingPixel pixel) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("meta_pixel_id", pixel.getMetaPixelId());
        view.put("name", pixel.getName());
        view.put("enabled", pixel.isEnabled());
        view.put("allowed_domains", pixel.getAllowedDomains());
        view.put("enabled_events", pixel.getEnabledEvents());
        view.put("forward_custom_events", pixel.isForwardCustomEvents());
        view.put("consent_mode", pixel.getConsentMode());
        view.put("test_event_code_set", pixel.getTestEventCode() != null);
        view.put("capi_token_configured", pixel.getCapiTokenCiphertext() != null);
        return view;
    }

    PixelDto toDto(TrackingPixel p) {
        return new PixelDto(p.getId().toString(), p.getClientId().toString(), p.getMetaPixelId(), p.getName(),
                p.getAllowedDomains() == null || p.getAllowedDomains().isBlank() ? List.of()
                        : List.of(p.getAllowedDomains().split(",")),
                p.isEnabled(),
                TrackingEventCatalog.eventSet(p.getEnabledEvents()).stream().map(Enum::name).toList(),
                p.isForwardCustomEvents(), p.getConsentMode(), p.getTestEventCode(),
                p.getCapiTokenCiphertext() != null, tracking.capiEnabled(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
