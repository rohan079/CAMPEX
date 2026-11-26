package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.CampaignExecutionDtos.CampaignConfig;
import com.aivoice.lead.dto.CampaignExecutionDtos.ValidationIssue;
import com.aivoice.lead.dto.CampaignExecutionDtos.ValidationReport;
import java.math.BigDecimal;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, deterministic campaign checks. Limits mirror what the publish path actually accepts (the FastAPI
 * {@code /api/meta/publish} schema and the {@code create_paused_campaign} tool), so a configuration that passes here
 * is not rejected for shape reasons later. Meta can still refuse it (e.g. an ambiguous location); that is reported
 * from the publish step.
 */
public final class CampaignValidator {

    public static final Set<String> OBJECTIVES = Set.of("leads", "traffic", "sales", "awareness");
    public static final Set<String> PLATFORMS = Set.of("facebook", "instagram", "whatsapp_ads");
    public static final Set<String> CTAS = Set.of("LEARN_MORE", "SIGN_UP", "CONTACT_US", "MESSAGE_PAGE", "BOOK_NOW",
            "GET_QUOTE", "APPLY_NOW");
    static final int NAME_MAX = 140;
    static final long MIN_DAILY_BUDGET_MINOR = 100;
    private static final java.util.regex.Pattern LOCATION_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> LOCATION_TYPES = Set.of("city", "region", "country");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern AD_ACCOUNT = Pattern.compile("^act_[0-9]{3,32}$");

    /**
     * What the validator needs from the database.
     *
     * @param mappedAdAccounts ad accounts mapped to the client
     * @param dailySpendLimit  the client's daily limit in major currency units, or null when no policy is set
     */
    public record Context(Set<String> mappedAdAccounts, BigDecimal dailySpendLimit,
                          java.util.Map<String, ApprovedCreative> approvedCreatives) {
        public Context(Set<String> mappedAdAccounts, BigDecimal dailySpendLimit) {
            this(mappedAdAccounts, dailySpendLimit, java.util.Map.of());
        }
    }

    /** The approved version of a creative and the copy that was approved with it. */
    public record ApprovedCreative(int version, String headline, String primaryText) {
    }

    private CampaignValidator() {
    }

    public static ValidationReport validate(CampaignConfig c, Context ctx) {
        List<ValidationIssue> errors = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();

        String name = trim(c.name());
        if (name.isEmpty() || name.length() > NAME_MAX) {
            errors.add(issue("name", "length", "Campaign name must be 1-" + NAME_MAX + " characters."));
        }
        if (!OBJECTIVES.contains(trim(c.objective()))) {
            errors.add(issue("objective", "unsupported", "Objective must be one of leads, traffic, sales, awareness."));
        } else if ("sales".equals(trim(c.objective()))) {
            errors.add(issue("objective", "needs_pixel", "Sales campaigns need a Meta Pixel conversion event, which Campex "
                    + "does not set up yet. Use Leads or Traffic."));
        } else if ("leads".equals(trim(c.objective())) && trim(c.pageId()).isEmpty()) {
            errors.add(issue("page_id", "required", "Leads campaigns need a Facebook Page. Select the Page the ads run as."));
        }
        String account = trim(c.adAccountId());
        if (!AD_ACCOUNT.matcher(account).matches()) {
            errors.add(issue("ad_account_id", "format", "Ad account must look like act_1234567890."));
        } else if (!ctx.mappedAdAccounts().contains(account)) {
            errors.add(issue("ad_account_id", "not_mapped",
                    "This ad account is not linked to this client. Link it in Campaign Studio (Goal step) or Tracking → Ad accounts."));
        }

        List<String> platforms = c.platforms() == null ? List.of() : c.platforms();
        if (platforms.isEmpty() || !PLATFORMS.containsAll(platforms)) {
            errors.add(issue("platforms", "invalid", "Choose at least one of Facebook, Instagram, WhatsApp ads."));
        }
        String location = trim(c.location());
        if (location.isEmpty() || location.length() > 160) {
            errors.add(issue("location", "required", "Target location is required (max 160 characters)."));
        }
        String locationKey = trim(c.locationKey());
        if (!locationKey.isEmpty()) {
            if (!LOCATION_KEY.matcher(locationKey).matches() || !LOCATION_TYPES.contains(trim(c.locationType()))) {
                errors.add(issue("location", "invalid_key", "Pick the location again from Meta's suggestions."));
            }
        } else if (!location.isEmpty()) {
            warnings.add(issue("location", "unverified", "Location was typed, not picked from Meta's suggestions. "
                    + "Meta may not match \"" + location + "\"; pick it from the list so the publish can't fail on location."));
        }
        Integer ageMin = c.ageMin();
        Integer ageMax = c.ageMax();
        if (ageMin == null || ageMax == null || ageMin < 18 || ageMax > 65 || ageMax < ageMin) {
            errors.add(issue("age", "range", "Age range must be within 18-65, with maximum not below minimum."));
        }

        Long budget = c.dailyBudgetMinor();
        if (budget == null || budget < MIN_DAILY_BUDGET_MINOR) {
            errors.add(issue("daily_budget_minor", "minimum", "Daily budget must be at least 100 minor units (e.g. ₹1)."));
        } else if (ctx.dailySpendLimit() != null
                && BigDecimal.valueOf(budget).movePointLeft(2).compareTo(ctx.dailySpendLimit()) > 0) {
            errors.add(issue("daily_budget_minor", "over_policy", "Daily budget exceeds the client's daily spend limit of "
                    + ctx.dailySpendLimit().stripTrailingZeros().toPlainString() + "."));
        }

        String headline = trim(c.headline());
        if (headline.isEmpty() || headline.length() > 80) {
            errors.add(issue("headline", "length", "Headline is required (max 80 characters)."));
        } else if (headline.length() > 40) {
            warnings.add(issue("headline", "long", "Headlines over 40 characters are often truncated in feeds."));
        }
        String text = trim(c.primaryText());
        if (text.isEmpty() || text.length() > 1500) {
            errors.add(issue("primary_text", "length", "Primary text is required (max 1500 characters)."));
        } else if (text.length() > 125) {
            warnings.add(issue("primary_text", "long", "Primary text over 125 characters is cut off behind \"See more\"."));
        }
        if (trim(c.description()).length() > 255) {
            errors.add(issue("description", "length", "Description must be at most 255 characters."));
        }
        String cta = trim(c.cta()).toUpperCase(Locale.ROOT);
        if (!CTAS.contains(cta)) {
            errors.add(issue("cta", "unsupported", "Call to action must be one of " + String.join(", ", CTAS.stream().sorted().toList()) + "."));
        }

        String url = trim(c.destinationUrl());
        boolean needsUrl = "traffic".equals(c.objective()) || "sales".equals(c.objective());
        if (url.isEmpty()) {
            if (needsUrl) {
                errors.add(issue("destination_url", "required", "A destination URL is required for traffic and sales campaigns."));
            }
        } else {
            String problem = urlProblem(url);
            if (problem != null) {
                errors.add(issue("destination_url", "unsafe", problem));
            }
        }

        String creativeId = trim(c.creativeAssetId());
        if (creativeId.isEmpty()) {
            warnings.add(issue("creative", "none", "No approved creative is attached; the copy has not been through creative review."));
        } else {
            ApprovedCreative approved = ctx.approvedCreatives().get(creativeId);
            if (approved == null) {
                errors.add(issue("creative", "not_approved", "The attached creative is not approved for this client."));
            } else if (c.creativeVersion() == null || c.creativeVersion() != approved.version()) {
                errors.add(issue("creative", "stale_version", "The attached creative version is not the approved one (v"
                        + approved.version() + "). Re-attach it."));
            } else if (!trim(approved.headline()).equals(headline) || !trim(approved.primaryText()).equals(text)) {
                errors.add(issue("creative", "copy_changed", "Headline and primary text must match the approved creative."));
            }
        }

        List<com.aivoice.lead.dto.CampaignExecutionDtos.CreativeRef> extras = c.extraCreatives() == null ? List.of() : c.extraCreatives();
        if (!extras.isEmpty()) {
            if (creativeId.isEmpty()) {
                errors.add(issue("extra_creatives", "needs_main", "Attach the main creative before adding more ads."));
            }
            if (extras.size() > 5) {
                errors.add(issue("extra_creatives", "too_many", "A campaign can have at most 6 ads (the main creative and 5 more)."));
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            seen.add(creativeId);
            int n = 2;
            for (var ref : extras) {
                String id = ref == null ? "" : trim(ref.assetId());
                ApprovedCreative approved = id.isEmpty() ? null : ctx.approvedCreatives().get(id);
                if (approved == null) {
                    errors.add(issue("extra_creatives", "not_approved", "Ad " + n + ": the creative is not approved for this client."));
                } else if (ref.version() == null || ref.version() != approved.version()) {
                    errors.add(issue("extra_creatives", "stale_version", "Ad " + n + ": attach the approved version (v" + approved.version() + ")."));
                }
                if (!id.isEmpty() && !seen.add(id)) {
                    errors.add(issue("extra_creatives", "duplicate", "Ad " + n + " uses a creative that is already in this campaign."));
                }
                n++;
            }
        }

        if (!creativeId.isEmpty() && (url.isEmpty() || trim(c.pageId()).isEmpty())) {
            warnings.add(issue("ad", "not_created", "Campex creates the ad from the approved creative only with a destination URL "
                    + "and a Facebook Page. Without them, add the ad in Ads Manager."));
        } else if (creativeId.isEmpty()) {
            warnings.add(issue("ad", "not_created", "Campex creates the campaign and ad set, both paused. Attach an approved "
                    + "creative (image or video) and Campex also creates the ad, paused."));
        }
        return new ValidationReport(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    /** Null when the URL is an https link to a public host. */
    static String urlProblem(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return "Destination URL is not a valid URL.";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "Destination URL must start with https://.";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "Destination URL has no host.";
        }
        if (uri.getUserInfo() != null) {
            return "Destination URL must not contain credentials.";
        }
        String h = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || IPV4.matcher(h).matches() || h.startsWith("[") || h.contains(":") || !h.contains(".")) {
            return "Destination URL must point to a public website, not a local or IP address.";
        }
        return null;
    }

    private static ValidationIssue issue(String field, String code, String message) {
        return new ValidationIssue(field, code, message);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
