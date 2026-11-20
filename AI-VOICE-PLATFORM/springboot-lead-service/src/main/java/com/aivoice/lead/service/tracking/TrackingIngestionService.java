package com.aivoice.lead.service.tracking;

import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.dto.TrackingDtos.BrowserEventRequest;
import com.aivoice.lead.dto.TrackingDtos.Consent;
import com.aivoice.lead.dto.TrackingDtos.ConsentRequest;
import com.aivoice.lead.dto.TrackingDtos.IngestResult;
import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.TrackingConsent;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.TrackingConsentRepository;
import com.aivoice.lead.repository.TrackingPixelRepository;
import com.aivoice.lead.service.Exceptions.ForbiddenException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.tracking.ConversionEventRecorder.Recorded;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public website events: rate limit → pixel and origin check → validation → consent → dedup → persist.
 * The browser only ever supplies public identifiers (client id, Pixel ID); no service keys are involved.
 */
@Service
public class TrackingIngestionService {

    /** Network facts the controller extracts from the HTTP request. */
    public record RequestContext(String clientIp, String userAgent, String origin) {
    }

    private final TrackingPixelRepository pixels;
    private final TrackingConsentRepository consents;
    private final ConversionEventRecorder recorder;
    private final TrackingRateLimiter limiter;
    private final TrackingProperties props;
    private final Clock clock;

    public TrackingIngestionService(TrackingPixelRepository pixels, TrackingConsentRepository consents,
                                    ConversionEventRecorder recorder, TrackingRateLimiter limiter,
                                    TrackingProperties props, Clock clock) {
        this.pixels = pixels;
        this.consents = consents;
        this.recorder = recorder;
        this.limiter = limiter;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public IngestResult ingest(BrowserEventRequest request, RequestContext context) {
        limiter.check("ip:" + UserDataHasher.sha256(String.valueOf(context.clientIp())), props.rateLimitPerMinutePerIp());
        TrackingPixel pixel = resolvePixel(request.clientId(), request.pixelId());
        limiter.check("pixel:" + pixel.getId(), props.rateLimitPerMinutePerPixel());
        requireAllowedOrigin(pixel, context.origin(), request.eventSourceUrl());

        EventName name = TrackingEventCatalog.parse(request.eventName())
                .filter(EventName::browserAllowed)
                .orElseThrow(() -> new IllegalArgumentException("Event not accepted from the browser: " + request.eventName()));
        if (!TrackingEventCatalog.eventSet(pixel.getEnabledEvents()).contains(name)) {
            throw new IllegalArgumentException("Event not enabled for this pixel: " + name);
        }
        Instant eventTime = validateEventTime(request.eventTime());

        String consentStatus = consentStatus(pixel, request.visitorId(), request.consent());
        if ("denied_analytics".equals(consentStatus)) {
            // No analytics consent: nothing is stored at all.
            return new IngestResult("ignored_no_consent", request.eventId(), null);
        }

        ConversionEvent event = new ConversionEvent();
        event.setClientId(pixel.getClientId());
        event.setEventId(request.eventId());
        event.setEventName(name.name());
        event.setVisitorId(blankToNull(request.visitorId()));
        event.setSessionId(blankToNull(request.sessionId()));
        event.setEventSource(request.eventSource() == null ? "website" : request.eventSource());
        event.setEventTime(eventTime);
        event.setEventSourceUrl(stripFragment(request.eventSourceUrl()));
        event.setReferrerUrl(stripFragment(request.referrerUrl()));
        event.setCampaignId(blankToNull(request.campaignId()));
        event.setAdsetId(blankToNull(request.adsetId()));
        event.setAdId(blankToNull(request.adId()));
        event.setConsentStatus(consentStatus);
        event.setFbclid(blankToNull(request.fbclid()));
        event.setFbp(validFbp(request.fbp()));
        event.setFbc(validFbc(request.fbc()).orElseGet(() -> derivedFbc(request.fbclid(), eventTime)));
        event.setClientIpAddress(blankToNull(context.clientIp()));
        event.setClientUserAgent(truncate(context.userAgent(), 512));
        event.setUserDataHashes(UserDataHasher.hashAll(request.userData()));
        event.setPayload(payload(request));
        Recorded recorded = recorder.record(event, pixel);
        return new IngestResult(recorded.duplicate() ? "duplicate" : "accepted", request.eventId(),
                recorded.event().getMetaDeliveryStatus());
    }

    @Transactional
    public void recordConsent(ConsentRequest request, RequestContext context) {
        limiter.check("ip:" + UserDataHasher.sha256(String.valueOf(context.clientIp())), props.rateLimitPerMinutePerIp());
        TrackingPixel pixel = resolvePixel(request.clientId(), request.pixelId());
        requireAllowedOrigin(pixel, context.origin(), null);
        TrackingConsent consent = new TrackingConsent();
        consent.setClientId(pixel.getClientId());
        consent.setPixelId(pixel.getId());
        consent.setVisitorId(request.visitorId());
        consent.setAnalyticsStorage(request.analyticsStorage());
        consent.setAdStorage(request.adStorage());
        consent.setAdUserData(request.adUserData());
        consent.setSource("banner");
        consent.setPolicyVersion(blankToNull(request.policyVersion()));
        consent.setRecordedAt(clock.instant());
        consents.save(consent);
    }

    TrackingPixel resolvePixel(String clientIdText, String metaPixelId) {
        UUID clientId;
        try {
            clientId = UUID.fromString(clientIdText);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown pixel");
        }
        return pixels.findByClientIdAndMetaPixelIdAndDeletedAtIsNull(clientId, metaPixelId.trim())
                .filter(TrackingPixel::isEnabled)
                .orElseThrow(() -> new NotFoundException("Unknown pixel"));
    }

    /**
     * The browser's Origin (or, for beacons without one, the page URL) must belong to a domain configured on the
     * pixel. A pixel without allowed domains accepts no browser traffic.
     */
    static void requireAllowedOrigin(TrackingPixel pixel, String origin, String pageUrl) {
        String host = host(origin).or(() -> host(pageUrl)).orElse(null);
        if (!TrackingPixelService.hostAllowed(pixel.getAllowedDomains(), host)) {
            throw new ForbiddenException("This site is not allowed to send events for the pixel");
        }
    }

    Instant validateEventTime(Long epochSeconds) {
        Instant now = clock.instant();
        if (epochSeconds == null) {
            return now;
        }
        Instant time = Instant.ofEpochSecond(epochSeconds);
        if (time.isAfter(now.plusSeconds(props.maxFutureSkewSeconds()))) {
            throw new IllegalArgumentException("event_time is in the future");
        }
        if (time.isBefore(now.minus(Duration.ofDays(props.maxEventAgeDays())))) {
            // Also blocks replay of old captured events.
            throw new IllegalArgumentException("event_time is too old");
        }
        return time;
    }

    /**
     * Effective consent. Inline consent from the SDK wins; otherwise the visitor's latest stored decision.
     * Returns {@code denied_analytics} when the event must not be stored at all.
     */
    String consentStatus(TrackingPixel pixel, String visitorId, Consent inline) {
        if ("not_required".equals(pixel.getConsentMode())) {
            return "not_required";
        }
        String analytics;
        String ads;
        String adUserData;
        if (inline != null && inline.analyticsStorage() != null) {
            analytics = inline.analyticsStorage();
            ads = inline.adStorage();
            adUserData = inline.adUserData();
        } else if (visitorId != null && !visitorId.isBlank()) {
            Optional<TrackingConsent> stored = consents.findFirstByClientIdAndVisitorIdOrderByRecordedAtDesc(
                    pixel.getClientId(), visitorId);
            if (stored.isEmpty()) {
                return "denied_analytics";
            }
            analytics = stored.get().getAnalyticsStorage();
            ads = stored.get().getAdStorage();
            adUserData = stored.get().getAdUserData();
        } else {
            return "denied_analytics";
        }
        if (!"granted".equals(analytics)) {
            return "denied_analytics";
        }
        return "granted".equals(ads) && "granted".equals(adUserData) ? "granted" : "denied";
    }

    private static Map<String, Object> payload(BrowserEventRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> utm = new LinkedHashMap<>();
        putIfText(utm, "utm_source", request.utmSource());
        putIfText(utm, "utm_medium", request.utmMedium());
        putIfText(utm, "utm_campaign", request.utmCampaign());
        putIfText(utm, "utm_content", request.utmContent());
        putIfText(utm, "utm_term", request.utmTerm());
        if (!utm.isEmpty()) {
            payload.put("utm", utm);
        }
        putIfText(payload, "landing_page", stripFragment(request.landingPage()));
        if (request.customData() != null && !request.customData().isEmpty()) {
            Map<String, Object> custom = new LinkedHashMap<>();
            request.customData().forEach((key, value) -> {
                if (MetaCapiPayloadBuilder.CUSTOM_DATA_KEYS.contains(key) && isSimple(value)) {
                    custom.put(key, value);
                }
            });
            if (!custom.isEmpty()) {
                payload.put("custom_data", custom);
            }
        }
        return payload;
    }

    private static boolean isSimple(Object value) {
        if (value instanceof String s) {
            return s.length() <= 255;
        }
        if (value instanceof java.util.List<?> list) {
            return list.size() <= 50 && list.stream().allMatch(v -> v instanceof String s && s.length() <= 100);
        }
        return value instanceof Number || value instanceof Boolean;
    }

    /** Meta's documented _fbp format: fb.{subdomainIndex}.{creationTime}.{random}. */
    static String validFbp(String fbp) {
        return fbp != null && fbp.matches("^fb\\.[0-2]\\.[0-9]{10,13}\\.[0-9]{1,20}$") ? fbp : null;
    }

    static Optional<String> validFbc(String fbc) {
        return Optional.ofNullable(fbc).filter(v -> v.matches("^fb\\.[0-2]\\.[0-9]{10,13}\\.[A-Za-z0-9_-]{1,230}$"));
    }

    /** _fbc derived from an fbclid when the cookie was not available: fb.1.{ms}.{fbclid}. */
    static String derivedFbc(String fbclid, Instant eventTime) {
        if (fbclid == null || !fbclid.matches("^[A-Za-z0-9_-]{1,230}$")) {
            return null;
        }
        return "fb.1." + eventTime.toEpochMilli() + "." + fbclid;
    }

    static Optional<String> host(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(URI.create(url.trim()).getHost());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static String stripFragment(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int hash = url.indexOf('#');
        return truncate(hash >= 0 ? url.substring(0, hash) : url, 2048);
    }

    private static void putIfText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
