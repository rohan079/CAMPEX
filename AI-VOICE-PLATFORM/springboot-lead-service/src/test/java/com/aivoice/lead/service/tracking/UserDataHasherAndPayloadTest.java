package com.aivoice.lead.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.service.tracking.MetaCapiPayloadBuilder.InvalidEventException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserDataHasherAndPayloadTest {

    @Test
    void normalizesBeforeHashingLikeMeta() {
        // sha256("person@example.com")
        String expected = UserDataHasher.sha256("person@example.com");
        assertEquals(expected, UserDataHasher.hash("em", "  Person@Example.COM "));
        assertEquals(UserDataHasher.sha256("919876543210"), UserDataHasher.hash("ph", "+91 98765-43210"));
        assertEquals(UserDataHasher.sha256("919876543210"), UserDataHasher.hash("ph", "9876543210"));
        assertEquals(UserDataHasher.sha256("mary"), UserDataHasher.hash("fn", "Mary."));
        assertNull(UserDataHasher.hash("em", "not-an-email"));
        assertNull(UserDataHasher.hash("ph", "123"));
    }

    @Test
    void alreadyHashedValuesPassThroughAndUnknownKeysAreDropped() {
        String hashed = UserDataHasher.sha256("x@y.com");
        Map<String, Object> out = UserDataHasher.hashAll(Map.of("em", hashed.toUpperCase(), "password", "secret"));
        assertEquals(hashed, out.get("em"));
        assertFalse(out.containsKey("password"));
    }

    private static ConversionEvent websiteLead() {
        ConversionEvent e = new ConversionEvent();
        e.setEventName("Lead");
        e.setMetaEventName("Lead");
        e.setEventId("evt-12345678");
        e.setEventSource("website");
        e.setEventTime(Instant.ofEpochSecond(1_800_000_000L));
        e.setEventSourceUrl("https://example.com/landing");
        e.setClientUserAgent("Mozilla/5.0");
        e.setClientIpAddress("203.0.113.9");
        e.setFbc("fb.1.1700000000000.IwAR");
        e.setFbp("fb.1.1700000000000.123");
        e.setUserDataHashes(new LinkedHashMap<>(Map.of("em", UserDataHasher.sha256("a@b.co"), "lead_id", "123456789")));
        Map<String, Object> custom = new LinkedHashMap<>(Map.of("value", 10, "currency", "INR", "notes", "private"));
        e.setPayload(new LinkedHashMap<>(Map.of("custom_data", custom)));
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsCapiEventWithRequiredFieldsAndOnlyAllowListedCustomData() {
        Map<String, Object> data = MetaCapiPayloadBuilder.build(websiteLead());
        assertEquals("Lead", data.get("event_name"));
        assertEquals(1_800_000_000L, data.get("event_time"));
        assertEquals("evt-12345678", data.get("event_id"));
        assertEquals("website", data.get("action_source"));
        assertEquals("https://example.com/landing", data.get("event_source_url"));
        Map<String, Object> user = (Map<String, Object>) data.get("user_data");
        assertEquals(List.of(UserDataHasher.sha256("a@b.co")), user.get("em"));
        assertEquals("fb.1.1700000000000.IwAR", user.get("fbc"));
        assertEquals("203.0.113.9", user.get("client_ip_address"));
        assertEquals("123456789", user.get("lead_id"));
        Map<String, Object> custom = (Map<String, Object>) data.get("custom_data");
        assertEquals(10, custom.get("value"));
        assertFalse(custom.containsKey("notes"));
    }

    @Test
    void websiteEventsWithoutUserAgentAreRejectedNotSent() {
        ConversionEvent e = websiteLead();
        e.setClientUserAgent(null);
        assertThrows(InvalidEventException.class, () -> MetaCapiPayloadBuilder.build(e));
    }

    @Test
    void crmEventsUseSystemGeneratedAndSiteVisitsPhysicalStore() {
        assertEquals("system_generated", TrackingEventCatalog.actionSource(
                TrackingEventCatalog.EventName.QualifiedLead, TrackingEventCatalog.Source.manual_crm));
        assertEquals("physical_store", TrackingEventCatalog.actionSource(
                TrackingEventCatalog.EventName.SiteVisitCompleted, TrackingEventCatalog.Source.manual_crm));
        assertEquals("chat", TrackingEventCatalog.actionSource(
                TrackingEventCatalog.EventName.Contact, TrackingEventCatalog.Source.whatsapp));
    }

    @Test
    void internalEventsAreNeverSentAsStandardEvents() {
        assertTrue(TrackingEventCatalog.metaEventName(TrackingEventCatalog.EventName.QualifiedLead, false).isEmpty());
        assertEquals("QualifiedLead",
                TrackingEventCatalog.metaEventName(TrackingEventCatalog.EventName.QualifiedLead, true).orElseThrow());
        assertEquals(TrackingEventCatalog.Category.CAMPEX_INTERNAL,
                TrackingEventCatalog.EventName.SiteVisitCompleted.category());
        assertFalse(TrackingEventCatalog.EventName.Purchase.browserAllowed());
    }
}
