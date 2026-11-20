package com.aivoice.lead.service.tracking;

import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.Source;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds one entry of the Conversions API {@code data} array from a stored event. Only allow-listed custom_data keys
 * are sent, so CRM metadata (notes, names) can never leak into the payload.
 */
public final class MetaCapiPayloadBuilder {

    /** Thrown when an event lacks fields Meta requires for its action source; such events are not retried. */
    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(String message) {
            super(message);
        }
    }

    static final Set<String> CUSTOM_DATA_KEYS = Set.of("value", "currency", "content_name", "content_category",
            "content_ids", "content_type", "num_items", "status", "predicted_ltv", "search_string");

    private MetaCapiPayloadBuilder() {
    }

    public static Map<String, Object> build(ConversionEvent event) {
        if (event.getMetaEventName() == null) {
            throw new InvalidEventException("Event has no Meta event name");
        }
        EventName name = TrackingEventCatalog.parse(event.getEventName())
                .orElseThrow(() -> new InvalidEventException("Unknown event " + event.getEventName()));
        Source source = Source.parse(event.getEventSource())
                .orElseThrow(() -> new InvalidEventException("Unknown source " + event.getEventSource()));
        String actionSource = TrackingEventCatalog.actionSource(name, source);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event_name", event.getMetaEventName());
        data.put("event_time", event.getEventTime().getEpochSecond());
        data.put("event_id", event.getEventId());
        data.put("action_source", actionSource);
        if (event.getEventSourceUrl() != null && !event.getEventSourceUrl().isBlank()) {
            data.put("event_source_url", event.getEventSourceUrl());
        }

        Map<String, Object> userData = userData(event);
        if ("website".equals(actionSource)) {
            if (!data.containsKey("event_source_url")) {
                throw new InvalidEventException("Website events need event_source_url");
            }
            if (!userData.containsKey("client_user_agent")) {
                throw new InvalidEventException("Website events need the client user agent");
            }
        }
        if (userData.isEmpty()) {
            throw new InvalidEventException("Event has no customer information parameters");
        }
        data.put("user_data", userData);

        Map<String, Object> customData = customData(event);
        if (!customData.isEmpty()) {
            data.put("custom_data", customData);
        }
        return data;
    }

    static Map<String, Object> userData(ConversionEvent event) {
        Map<String, Object> userData = new LinkedHashMap<>();
        Map<String, Object> hashes = event.getUserDataHashes() == null ? Map.of() : event.getUserDataHashes();
        for (String key : UserDataHasher.HASHED_KEYS) {
            Object value = hashes.get(key);
            if (value instanceof String s && UserDataHasher.isSha256(s)) {
                // Meta accepts arrays for multi-valued identifiers.
                userData.put(key, List.of(s));
            }
        }
        putIfText(userData, "fbc", event.getFbc());
        putIfText(userData, "fbp", event.getFbp());
        putIfText(userData, "client_ip_address", event.getClientIpAddress());
        putIfText(userData, "client_user_agent", event.getClientUserAgent());
        Object metaLeadId = hashes.get("lead_id");
        if (metaLeadId instanceof String s && s.matches("^[0-9]{6,32}$")) {
            // Meta's lead id (instant forms) is sent unhashed, as a number-like string.
            userData.put("lead_id", s);
        }
        return userData;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> customData(ConversionEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object raw = event.getPayload() == null ? null : event.getPayload().get("custom_data");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (CUSTOM_DATA_KEYS.contains(key) && entry.getValue() != null) {
                    result.put(key, entry.getValue());
                }
            }
        }
        Optional.ofNullable(event.getPayload()).map(p -> p.get("lead_event_source"))
                .ifPresent(v -> result.put("lead_event_source", String.valueOf(v)));
        if (!Source.parse(event.getEventSource()).map(Source::isBrowser).orElse(true)) {
            result.putIfAbsent("event_source", "crm");
        }
        return result;
    }

    private static void putIfText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
