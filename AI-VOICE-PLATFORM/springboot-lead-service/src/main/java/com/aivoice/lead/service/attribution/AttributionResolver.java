package com.aivoice.lead.service.attribution;

import com.aivoice.lead.dto.AttributionDtos.AttributionInput;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns raw attribution evidence into one touch, recording which rule produced it. Priority:
 * <ol>
 *   <li>{@code meta_referral} – Meta's own referral/ad data (click-to-WhatsApp referral, instant-form lead).</li>
 *   <li>{@code click_id} – a valid fbclid/_fbc together with campaign/ad ids captured on the landing page.</li>
 *   <li>{@code campaign_metadata} – campaign/ad ids supplied without a click id (e.g. popup parameters).</li>
 *   <li>{@code utm} – only UTM parameters.</li>
 *   <li>{@code registered_ad} – nothing supplied, but the lead's stored ad id is registered to the client.</li>
 *   <li>{@code unknown} – no evidence. Nothing is guessed or invented.</li>
 * </ol>
 */
public final class AttributionResolver {

    private AttributionResolver() {
    }

    public record Resolved(
            String source,
            String method,
            String campaignId,
            String campaignName,
            String adsetId,
            String adsetName,
            String adId,
            String adName,
            String metaFormId,
            String metaLeadId,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmContent,
            String utmTerm,
            String fbclid,
            String fbc,
            String fbp,
            String landingPage,
            String referrer) {

        public boolean isUnknown() {
            return "unknown".equals(method);
        }
    }

    /**
     * @param source       where the lead came from (meta_lead_form, website, ctwa, whatsapp_referral, …)
     * @param input        evidence; never null
     * @param fallbackAdId the lead's already-stored ad id, consulted only for registered-ad mapping
     * @param registeredAdName name of a registered ad for this client, if the id is registered
     */
    public static Resolved resolve(String source, AttributionInput input, String fallbackAdId,
                                   Function<String, Optional<String>> registeredAdName) {
        String campaignId = id(input.campaignId());
        String adsetId = id(input.adsetId());
        String adId = id(input.adId());
        String formId = id(input.metaFormId());
        String metaLeadId = id(input.metaLeadId());
        String fbclid = clean(input.fbclid());
        String fbc = clean(input.fbc());
        boolean hasIds = campaignId != null || adsetId != null || adId != null;
        boolean hasClick = fbclid != null || fbc != null;
        boolean hasUtm = clean(input.utmSource()) != null || clean(input.utmCampaign()) != null
                || clean(input.utmMedium()) != null;
        boolean metaSource = "ctwa".equals(source) || "whatsapp_referral".equals(source) || "meta_lead_form".equals(source);

        String method;
        if (metaSource && (hasIds || metaLeadId != null)) {
            method = "meta_referral";
        } else if (hasClick && hasIds) {
            method = "click_id";
        } else if (hasIds) {
            method = "campaign_metadata";
        } else if (hasUtm) {
            method = "utm";
        } else if (id(fallbackAdId) != null && registeredAdName.apply(fallbackAdId.trim()).isPresent()) {
            method = "registered_ad";
            adId = fallbackAdId.trim();
        } else {
            method = "unknown";
        }

        String adName = clean(input.adName());
        if (adName == null && adId != null) {
            adName = registeredAdName.apply(adId).orElse(null);
        }
        return new Resolved(source, method, campaignId, clean(input.campaignName()), adsetId,
                clean(input.adsetName()), adId, adName, formId, metaLeadId, clean(input.utmSource()),
                clean(input.utmMedium()), clean(input.utmCampaign()), clean(input.utmContent()),
                clean(input.utmTerm()), fbclid, fbc, clean(input.fbp()), clean(input.landingPage()),
                clean(input.referrer()));
    }

    /** Evidence from a WhatsApp "referral" block. For ads Meta's {@code source_id} is the ad id, never a campaign id. */
    public static AttributionInput fromWhatsAppReferral(Map<String, Object> referral) {
        if (referral == null || referral.isEmpty()) {
            return AttributionInput.empty();
        }
        String sourceType = String.valueOf(referral.getOrDefault("source_type", "")).toLowerCase();
        String sourceId = text(referral.get("source_id"));
        String adId = text(referral.get("ad_id"));
        if (adId == null && "ad".equals(sourceType)) {
            adId = sourceId;
        }
        return new AttributionInput(null, null, null, null, null, adId, null, null, null, null, null, null, null,
                null, null, null, null, text(referral.get("source_url")), null, null, null);
    }

    /** Ids must look like Meta object ids or registered external ids; anything else is dropped, not guessed. */
    static String id(String value) {
        String v = clean(value);
        return v != null && v.matches("^[A-Za-z0-9_-]{1,128}$") ? v : null;
    }

    static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Object value) {
        return value == null ? null : clean(String.valueOf(value));
    }
}
