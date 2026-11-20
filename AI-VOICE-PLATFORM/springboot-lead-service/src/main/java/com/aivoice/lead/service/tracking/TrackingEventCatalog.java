package com.aivoice.lead.service.tracking;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The events Campex understands and how each maps to Meta. Standard events go to Meta under their standard name.
 * Campex internal CRM events are never sent as standard events; they are only forwarded as Meta *custom* events
 * when the pixel opts in ({@code forward_custom_events}).
 */
public final class TrackingEventCatalog {

    private TrackingEventCatalog() {
    }

    public enum Category { META_STANDARD, CAMPEX_INTERNAL }

    public enum EventName {
        PageView(Category.META_STANDARD, true),
        ViewContent(Category.META_STANDARD, true),
        Lead(Category.META_STANDARD, true),
        Contact(Category.META_STANDARD, true),
        CompleteRegistration(Category.META_STANDARD, true),
        Schedule(Category.META_STANDARD, true),
        Purchase(Category.META_STANDARD, false),
        QualifiedLead(Category.CAMPEX_INTERNAL, false),
        SiteVisitCompleted(Category.CAMPEX_INTERNAL, false);

        private final Category category;
        private final boolean browserAllowed;

        EventName(Category category, boolean browserAllowed) {
            this.category = category;
            this.browserAllowed = browserAllowed;
        }

        public Category category() {
            return category;
        }

        /** Whether the public website SDK may send this event. CRM outcomes and purchases are server-side only. */
        public boolean browserAllowed() {
            return browserAllowed;
        }

        public String categoryValue() {
            return category == Category.META_STANDARD ? "meta_standard" : "campex_internal";
        }
    }

    /** Funnel order shown in the dashboard. */
    public static final List<EventName> LIFECYCLE = List.of(
            EventName.PageView, EventName.ViewContent, EventName.Lead, EventName.Contact, EventName.QualifiedLead,
            EventName.Schedule, EventName.SiteVisitCompleted, EventName.Purchase);

    public static final Set<EventName> DEFAULT_PIXEL_EVENTS = EnumSet.of(
            EventName.PageView, EventName.ViewContent, EventName.Lead, EventName.Contact,
            EventName.CompleteRegistration, EventName.Schedule, EventName.Purchase);

    public enum Source {
        website, landing_page, whatsapp, manual_crm, backend_api, meta_webhook;

        public static Optional<Source> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            return Arrays.stream(values()).filter(s -> s.name().equals(value.trim().toLowerCase(Locale.ROOT))).findFirst();
        }

        public boolean isBrowser() {
            return this == website || this == landing_page;
        }
    }

    public static Optional<EventName> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Arrays.stream(EventName.values()).filter(e -> e.name().equals(name.trim())).findFirst();
    }

    /**
     * The name to send to Meta, or empty when this event must not be sent. Standard events keep their name; internal
     * events become custom events only when the pixel allows custom forwarding.
     */
    public static Optional<String> metaEventName(EventName event, boolean forwardCustomEvents) {
        if (event.category() == Category.META_STANDARD) {
            return Optional.of(event.name());
        }
        return forwardCustomEvents ? Optional.of(event.name()) : Optional.empty();
    }

    /** Meta {@code action_source} for where the conversion actually happened. */
    public static String actionSource(EventName event, Source source) {
        if (event == EventName.SiteVisitCompleted) {
            return "physical_store";
        }
        return switch (source) {
            case website, landing_page -> "website";
            case whatsapp -> "chat";
            case manual_crm, backend_api, meta_webhook -> "system_generated";
        };
    }

    public static String parseEventList(String csv, Set<EventName> fallback, List<String> errors) {
        if (csv == null || csv.isBlank()) {
            return String.join(",", fallback.stream().map(Enum::name).toList());
        }
        List<String> names = Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        for (String name : names) {
            if (parse(name).isEmpty()) {
                errors.add("Unknown event: " + name);
            }
        }
        return String.join(",", names);
    }

    public static Set<EventName> eventSet(String csv) {
        Set<EventName> result = EnumSet.noneOf(EventName.class);
        if (csv == null) {
            return result;
        }
        for (String name : csv.split(",")) {
            parse(name.trim()).ifPresent(result::add);
        }
        return result;
    }
}
