package com.aivoice.lead.service.performance;

import com.aivoice.lead.config.MetaOAuthProperties;
import com.aivoice.lead.config.PerformanceProperties;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Read-only Meta Marketing API access for performance analysis: insights (period or daily, optional breakdowns),
 * campaign budgets and ad creative metadata. It never writes to Meta.
 */
@Component
public class MetaPerformanceClient {

    /** Breakdown combinations this client requests; all are supported by the Insights API. */
    public static final Set<String> SUPPORTED_BREAKDOWNS = Set.of(
            "age", "gender", "age,gender", "country", "region", "publisher_platform",
            "publisher_platform,platform_position", "device_platform");

    /** Currencies Meta reports without minor units (budgets are not multiplied by 100). */
    static final Set<String> ZERO_DECIMAL = Set.of("JPY", "KRW", "CLP", "VND", "PYG", "ISK", "HUF", "TWD", "COP", "IDR");

    private static final int MAX_PAGES = 40;

    public record InsightRow(String campaignId, String campaignName, String adsetId, String adsetName, String adId,
                             String adName, LocalDate dateStart, LocalDate dateStop, BigDecimal spend, long impressions,
                             Long reach, BigDecimal frequency, long clicks, long linkClicks, long metaLeads,
                             String currency, Map<String, String> breakdown) {
    }

    public record CampaignInfo(String id, String name, String objective, String status, String effectiveStatus,
                               BigDecimal dailyBudget, BigDecimal lifetimeBudget, String budgetLevel) {
    }

    public record AdInfo(String adId, String adName, String campaignId, String adsetId, String status, String creativeId,
                         String creativeName, String headline, String primaryText, String cta, String imageUrl,
                         String thumbnailUrl, String format) {
    }

    public record AccountInfo(String id, String currency, String timezoneName) {
    }

    private final RestClient client;
    private final ObjectMapper mapper;
    private final PerformanceProperties props;
    private final MetaOAuthProperties meta;

    @Autowired
    public MetaPerformanceClient(RestClient.Builder builder, ObjectMapper mapper, PerformanceProperties props,
                                 MetaOAuthProperties meta) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.graphTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.graphTimeoutSeconds()));
        this.client = builder.clone().requestFactory(factory).build();
        this.mapper = mapper;
        this.props = props;
        this.meta = meta;
    }

    MetaPerformanceClient(RestClient client, ObjectMapper mapper, PerformanceProperties props, MetaOAuthProperties meta) {
        this.client = client;
        this.mapper = mapper;
        this.props = props;
        this.meta = meta;
    }

    public AccountInfo account(String adAccountId, String token) {
        JsonNode body = get(act(adAccountId), Map.of("fields", "id,currency,timezone_name"), token);
        return new AccountInfo(body.path("id").asText(act(adAccountId)), body.path("currency").asText(null),
                body.path("timezone_name").asText(null));
    }

    /**
     * Insights for [since, until] at campaign|adset|ad level. {@code daily} adds time_increment=1. {@code breakdowns}
     * must be one of {@link #SUPPORTED_BREAKDOWNS} or null.
     */
    public List<InsightRow> insights(String adAccountId, LocalDate since, LocalDate until, String level, boolean daily,
                                     String breakdowns, String token) {
        if (!Set.of("account", "campaign", "adset", "ad").contains(level)) {
            throw new IllegalArgumentException("Unsupported level " + level);
        }
        if (breakdowns != null && !SUPPORTED_BREAKDOWNS.contains(breakdowns)) {
            throw new IllegalArgumentException("Unsupported breakdown " + breakdowns);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("level", level);
        query.put("fields", "campaign_id,campaign_name,adset_id,adset_name,ad_id,ad_name,spend,impressions,reach,frequency,"
                + "clicks,inline_link_clicks,actions,account_currency");
        query.put("time_range", "{\"since\":\"" + since + "\",\"until\":\"" + until + "\"}");
        query.put("action_attribution_windows", "[\"" + String.join("\",\"", props.attributionWindows().split(",")) + "\"]");
        query.put("limit", "500");
        if (daily) {
            query.put("time_increment", "1");
        }
        if (breakdowns != null) {
            query.put("breakdowns", breakdowns);
        }
        List<InsightRow> rows = new ArrayList<>();
        for (JsonNode row : paged(act(adAccountId) + "/insights", query, token)) {
            Map<String, String> breakdown = new LinkedHashMap<>();
            if (breakdowns != null) {
                for (String key : breakdowns.split(",")) {
                    breakdown.put(key, row.path(key).asText("unknown"));
                }
            }
            rows.add(new InsightRow(text(row, "campaign_id"), text(row, "campaign_name"), text(row, "adset_id"),
                    text(row, "adset_name"), text(row, "ad_id"), text(row, "ad_name"), date(row, "date_start"),
                    date(row, "date_stop"), decimal(row, "spend"), row.path("impressions").asLong(0),
                    row.hasNonNull("reach") ? row.path("reach").asLong() : null,
                    row.hasNonNull("frequency") ? decimal(row, "frequency") : null, row.path("clicks").asLong(0),
                    row.path("inline_link_clicks").asLong(0), leads(row.path("actions")),
                    text(row, "account_currency"), breakdown));
        }
        return rows;
    }

    /** Campaign budgets. Ad set budgets are summed when the campaign itself has none (ad set budget optimisation). */
    public List<CampaignInfo> campaigns(String adAccountId, String currency, String token) {
        Map<String, BigDecimal[]> adsetBudgets = new LinkedHashMap<>();
        for (JsonNode adset : paged(act(adAccountId) + "/adsets",
                Map.of("fields", "id,campaign_id,daily_budget,lifetime_budget,effective_status", "limit", "500"), token)) {
            if (!"ACTIVE".equals(adset.path("effective_status").asText())) {
                continue;
            }
            BigDecimal[] sums = adsetBudgets.computeIfAbsent(adset.path("campaign_id").asText(),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal d = budget(adset, "daily_budget", currency);
            BigDecimal l = budget(adset, "lifetime_budget", currency);
            if (d != null) sums[0] = sums[0].add(d);
            if (l != null) sums[1] = sums[1].add(l);
        }
        List<CampaignInfo> result = new ArrayList<>();
        for (JsonNode c : paged(act(adAccountId) + "/campaigns", Map.of("fields",
                "id,name,objective,status,effective_status,daily_budget,lifetime_budget", "limit", "500"), token)) {
            BigDecimal daily = budget(c, "daily_budget", currency);
            BigDecimal lifetime = budget(c, "lifetime_budget", currency);
            String level = "campaign";
            if (daily == null && lifetime == null) {
                BigDecimal[] sums = adsetBudgets.get(c.path("id").asText());
                if (sums != null && (sums[0].signum() > 0 || sums[1].signum() > 0)) {
                    daily = sums[0].signum() > 0 ? sums[0] : null;
                    lifetime = sums[1].signum() > 0 ? sums[1] : null;
                    level = "adset";
                } else {
                    level = "none";
                }
            }
            result.add(new CampaignInfo(c.path("id").asText(), text(c, "name"), text(c, "objective"), text(c, "status"),
                    text(c, "effective_status"), daily, lifetime, level));
        }
        return result;
    }

    /** Ads with their creative's structured metadata (no image analysis). */
    public List<AdInfo> ads(String adAccountId, String token) {
        List<AdInfo> result = new ArrayList<>();
        for (JsonNode ad : paged(act(adAccountId) + "/ads", Map.of("fields",
                "id,name,campaign_id,adset_id,effective_status,creative{id,name,title,body,call_to_action_type,"
                        + "image_url,thumbnail_url,object_type}", "limit", "500"), token)) {
            JsonNode cr = ad.path("creative");
            result.add(new AdInfo(ad.path("id").asText(), text(ad, "name"), text(ad, "campaign_id"), text(ad, "adset_id"),
                    text(ad, "effective_status"), text(cr, "id"), text(cr, "name"), text(cr, "title"), text(cr, "body"),
                    text(cr, "call_to_action_type"), text(cr, "image_url"), text(cr, "thumbnail_url"), text(cr, "object_type")));
        }
        return result;
    }

    // ---------------------------------------------------------------- parsing

    /** Meta-reported leads under the fixed attribution windows: the aggregate "lead" action, else its parts. */
    static long leads(JsonNode actions) {
        long aggregate = -1;
        long parts = 0;
        if (actions.isArray()) {
            for (JsonNode action : actions) {
                String type = action.path("action_type").asText("");
                long value = action.path("value").asLong(0);
                if ("lead".equals(type)) {
                    aggregate = value;
                } else if ("onsite_conversion.lead_grouped".equals(type) || "offsite_conversion.fb_pixel_lead".equals(type)) {
                    parts += value;
                }
            }
        }
        return aggregate >= 0 ? aggregate : parts;
    }

    static BigDecimal budget(JsonNode node, String field, String currency) {
        if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) {
            return null;
        }
        BigDecimal minor = new BigDecimal(node.path(field).asText());
        if (minor.signum() <= 0) {
            return null;
        }
        return currency != null && ZERO_DECIMAL.contains(currency.toUpperCase()) ? minor
                : minor.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        try {
            return new BigDecimal(node.path(field).asText("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static LocalDate date(JsonNode node, String field) {
        return node.hasNonNull(field) ? LocalDate.parse(node.path(field).asText()) : null;
    }

    private static String act(String id) {
        return id.startsWith("act_") ? id : "act_" + id;
    }

    // ---------------------------------------------------------------- transport

    private List<JsonNode> paged(String edge, Map<String, String> query, String token) {
        List<JsonNode> rows = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> q = new LinkedHashMap<>(query);
            if (after != null) q.put("after", after);
            JsonNode body = get(edge, q, token);
            body.path("data").forEach(rows::add);
            JsonNode paging = body.path("paging");
            String next = paging.path("cursors").path("after").asText(null);
            if (!paging.hasNonNull("next") || next == null || next.equals(after)) {
                return rows;
            }
            after = next;
        }
        throw new MetaGraphException(502, -1, "pagination_limit");
    }

    private JsonNode get(String edge, Map<String, String> query, String token) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(
                props.graphBaseUrl().replaceAll("/+$", "") + "/" + meta.graphApiVersion() + "/" + edge);
        query.forEach(b::queryParam);
        URI uri = b.build().encode().toUri();
        try {
            // Token in the Authorization header, not the URL, so it never lands in access logs.
            String body = client.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().body(String.class);
            return mapper.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException e) {
            int code = -1;
            String message = null;
            try {
                JsonNode error = mapper.readTree(e.getResponseBodyAsByteArray()).path("error");
                code = error.path("code").asInt(-1);
                message = error.path("message").asText(null);
            } catch (IOException ignored) {
                // keep defaults
            }
            throw new MetaGraphException(e.getStatusCode().value(), code, "http_response", message);
        } catch (RestClientException | IOException e) {
            throw new MetaGraphException(502, -1, "transport_failure:" + e.getClass().getSimpleName());
        }
    }
}
