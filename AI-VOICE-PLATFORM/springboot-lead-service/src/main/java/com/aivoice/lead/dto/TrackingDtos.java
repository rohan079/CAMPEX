package com.aivoice.lead.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Pixel configuration, public event ingestion and event monitoring shapes. */
public final class TrackingDtos {

    private TrackingDtos() {
    }

    /** What React sees. The CAPI access token is never returned: only whether one is stored. */
    public record PixelDto(
            String id,
            String clientId,
            String metaPixelId,
            String name,
            List<String> allowedDomains,
            boolean enabled,
            List<String> enabledEvents,
            boolean forwardCustomEvents,
            String consentMode,
            String testEventCode,
            boolean capiTokenConfigured,
            boolean capiDispatchEnabled,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PixelRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{5,32}$", message = "meta_pixel_id must be the numeric Pixel ID") String metaPixelId,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 20) List<@Size(max = 253) String> allowedDomains,
            Boolean enabled,
            @Size(max = 12) List<@Size(max = 50) String> enabledEvents,
            Boolean forwardCustomEvents,
            @Pattern(regexp = "required|not_required") String consentMode,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]*$") String testEventCode,
            /** Write-only. Blank keeps the stored token; use {@code clear_capi_token} to remove it. */
            @Size(max = 1024) String capiAccessToken,
            Boolean clearCapiToken) {
    }

    public record Consent(
            @Pattern(regexp = "granted|denied") String analyticsStorage,
            @Pattern(regexp = "granted|denied") String adStorage,
            @Pattern(regexp = "granted|denied") String adUserData) {
    }

    /** Body posted by campex.js to the public ingestion endpoint. */
    public record BrowserEventRequest(
            @NotBlank @Size(max = 64) String clientId,
            @NotBlank @Size(max = 32) String pixelId,
            @NotBlank @Size(max = 50) String eventName,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{8,100}$") String eventId,
            Long eventTime,
            @Pattern(regexp = "website|landing_page") String eventSource,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]*$") String visitorId,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]*$") String sessionId,
            @Size(max = 2048) String eventSourceUrl,
            @Size(max = 2048) String referrerUrl,
            @Size(max = 2048) String landingPage,
            @Size(max = 255) String fbclid,
            @Size(max = 255) String fbc,
            @Size(max = 255) String fbp,
            @Size(max = 255) String utmSource,
            @Size(max = 255) String utmMedium,
            @Size(max = 255) String utmCampaign,
            @Size(max = 255) String utmContent,
            @Size(max = 255) String utmTerm,
            @Size(max = 128) @Pattern(regexp = "^[0-9]*$") String campaignId,
            @Size(max = 128) @Pattern(regexp = "^[0-9]*$") String adsetId,
            @Size(max = 128) @Pattern(regexp = "^[0-9]*$") String adId,
            @Valid Consent consent,
            /** Already-hashed (SHA-256 hex) or raw identifiers; raw values are hashed on arrival and not stored. */
            @Size(max = 12) Map<@Size(max = 20) String, @Size(max = 320) String> userData,
            @Size(max = 20) Map<@Size(max = 40) String, Object> customData) {
    }

    public record ConsentRequest(
            @NotBlank @Size(max = 64) String clientId,
            @NotBlank @Size(max = 32) String pixelId,
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String visitorId,
            @NotBlank @Pattern(regexp = "granted|denied") String analyticsStorage,
            @NotBlank @Pattern(regexp = "granted|denied") String adStorage,
            @NotBlank @Pattern(regexp = "granted|denied") String adUserData,
            @Size(max = 40) String policyVersion) {
    }

    public record IngestResult(String status, String eventId, String deliveryStatus) {
    }

    public record DeliveryDto(
            int attemptNumber,
            Integer httpStatus,
            Integer eventsReceived,
            String fbtraceId,
            Integer errorCode,
            Integer errorSubcode,
            String errorMessage,
            boolean retryable,
            String outcome,
            Instant attemptedAt) {
    }

    public record ConversionEventDto(
            String id,
            String clientId,
            String eventId,
            String eventName,
            String eventCategory,
            String metaEventName,
            String leadId,
            String visitorId,
            String sessionId,
            String pixelId,
            String campaignId,
            String adsetId,
            String adId,
            String eventSource,
            Instant eventTime,
            Instant receivedAt,
            String eventSourceUrl,
            Map<String, Object> payload,
            List<String> identifiersPresent,
            boolean fbcPresent,
            boolean fbpPresent,
            String consentStatus,
            String metaDeliveryStatus,
            int deliveryAttempts,
            Instant nextAttemptAt,
            String lastError,
            Instant sentAt,
            String dedupKey,
            int duplicateCount,
            boolean test,
            List<DeliveryDto> deliveries) {
    }

    public record EventPage(List<ConversionEventDto> events, long total, int limit, long offset) {
    }

    public record NameCount(String name, long count) {
    }

    public record DayCount(String day, long received, long sent, long failed) {
    }

    public record EventSummary(
            long received,
            long sentToMeta,
            long failed,
            long pending,
            long skippedNoConsent,
            long notForwarded,
            long deduplicated,
            Double deliverySuccessRate,
            List<NameCount> byEventName,
            List<NameCount> bySource,
            List<NameCount> byDeliveryStatus,
            List<DayCount> byDay,
            boolean capiDispatchEnabled) {
    }

    public record TestEventRequest(
            @NotBlank @Size(max = 64) String pixelId,
            @Size(max = 50) String eventName) {
    }
}
