package com.aivoice.lead.service.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aivoice.lead.dto.AttributionDtos.AttributionInput;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AttributionResolverTest {

    private static AttributionInput input(String campaign, String ad, String fbclid, String utmSource, String metaLead) {
        return new AttributionInput(null, campaign, null, null, null, ad, null, null, metaLead, utmSource, null,
                utmSource == null ? null : "spring", null, null, fbclid, null, null, null, null, null, null);
    }

    private static AttributionResolver.Resolved resolve(String source, AttributionInput in) {
        return AttributionResolver.resolve(source, in, null, id -> Optional.empty());
    }

    @Test
    void followsThePriorityOrder() {
        assertEquals("meta_referral", resolve("ctwa", input(null, "111", "fb1", "facebook", null)).method());
        assertEquals("meta_referral", resolve("meta_lead_form", input(null, null, null, null, "987654321")).method());
        assertEquals("click_id", resolve("website", input("222", null, "fbclid1", "facebook", null)).method());
        assertEquals("campaign_metadata", resolve("ad_popup", input("222", null, null, null, null)).method());
        assertEquals("utm", resolve("website", input(null, null, null, "facebook", null)).method());
        assertEquals("unknown", resolve("website", AttributionInput.empty()).method());
    }

    @Test
    void registeredAdMappingIsOnlyAFallbackAndAddsTheName() {
        var resolved = AttributionResolver.resolve("whatsapp", AttributionInput.empty(), "ad-9",
                id -> "ad-9".equals(id) ? Optional.of("Diwali villas") : Optional.empty());
        assertEquals("registered_ad", resolved.method());
        assertEquals("ad-9", resolved.adId());
        assertEquals("Diwali villas", resolved.adName());
        assertEquals("unknown", AttributionResolver.resolve("whatsapp", AttributionInput.empty(), "unregistered",
                id -> Optional.empty()).method());
    }

    @Test
    void neverInventsIdentifiers() {
        // Meta's referral source_id is the ad id: it must not become a campaign id.
        AttributionInput fromReferral = AttributionResolver.fromWhatsAppReferral(
                Map.of("source_type", "ad", "source_id", "120200009", "source_url", "https://fb.me/x"));
        var resolved = resolve("ctwa", fromReferral);
        assertEquals("120200009", resolved.adId());
        assertNull(resolved.campaignId());
        // Malformed ids are dropped rather than stored.
        assertNull(resolve("website", input("1; drop table", null, null, null, null)).campaignId());
        // UTM campaign names are kept as UTM, never promoted to campaign names/ids.
        var utm = resolve("website", input(null, null, null, "facebook", null));
        assertNull(utm.campaignId());
        assertNull(utm.campaignName());
        assertEquals("spring", utm.utmCampaign());
        // A post (not an ad) referral without ids yields no ad.
        assertNull(AttributionResolver.fromWhatsAppReferral(Map.of("source_type", "post", "source_id", "55")).adId());
    }
}
