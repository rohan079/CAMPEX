package com.aivoice.lead.service.performance;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.config.PerformanceProperties;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.dto.PerformanceDtos.Overview;
import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.dto.PerformanceDtos.PerformanceReport;
import com.aivoice.lead.dto.PerformanceDtos.TrendPoint;
import com.aivoice.lead.dto.PerformanceDtos.Trends;
import com.aivoice.lead.entity.PerformanceSnapshot;
import com.aivoice.lead.repository.PerformanceSnapshotRepository;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.aivoice.lead.service.performance.MetaPerformanceClient.AccountInfo;
import com.aivoice.lead.service.performance.MetaPerformanceClient.CampaignInfo;
import com.aivoice.lead.service.performance.MetaPerformanceClient.InsightRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Joins Meta-reported delivery with Campex CRM outcomes per campaign, ad set or ad. All numbers are computed here,
 * deterministically; AI services only explain them.
 */
@Service
public class PerformanceService {

    public static final Map<String, String> METRIC_SOURCES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("spend", "impressions", "reach", "frequency", "link_clicks", "meta_leads")) m.put(k, "meta_reported");
        for (String k : List.of("ctr", "cpc", "cpm", "meta_cost_per_lead", "conversion_rate")) m.put(k, "derived");
        m.put("crm_leads", "campex_tracked");
        m.put("qualified_leads", "crm_verified");
        m.put("site_visits", "crm_verified");
        m.put("bookings", "crm_verified");
        m.put("booking_value", "manual_financial");
        m.put("confirmed_revenue", "manual_financial");
        for (String k : List.of("qualified_rate", "cost_per_qualified_lead", "cost_per_site_visit", "cost_per_booking", "roas")) m.put(k, "derived");
        METRIC_SOURCES = java.util.Collections.unmodifiableMap(m);
    }

    /** Raw inputs for one report: Meta rows per object plus campaign metadata and account facts. */
    public record MetaData(boolean available, List<DataNote> notes, List<InsightRow> rows, Map<String, CampaignInfo> campaigns,
                           Map<String, Long> periodReach, Map<String, BigDecimal> periodFrequency, String currency,
                           List<String> adAccounts) {
    }

    private record CacheEntry(Instant at, Object value) {
    }

    private final MetaAccessResolver accessResolver;
    private final MetaPerformanceClient meta;
    private final CrmOutcomeRepository crm;
    private final PerformanceSnapshotRepository snapshots;
    private final ClientAccessService access;
    private final PerformanceProperties props;
    private final Clock clock;
    private final ZoneId zone;
    private final String appCurrency;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PerformanceService(MetaAccessResolver accessResolver, MetaPerformanceClient meta, CrmOutcomeRepository crm,
                              PerformanceSnapshotRepository snapshots, ClientAccessService access,
                              PerformanceProperties props, AppProperties app, Clock clock,
                              @Value("${app.currency:INR}") String appCurrency) {
        this.accessResolver = accessResolver;
        this.meta = meta;
        this.crm = crm;
        this.snapshots = snapshots;
        this.access = access;
        this.props = props;
        this.clock = clock;
        this.zone = app.zone();
        this.appCurrency = appCurrency;
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate today() {
        return clock.instant().atZone(zone).toLocalDate();
    }

    // ---------------------------------------------------------------- public API (access-checked)

    public PerformanceReport report(UUID clientId, LocalDate from, LocalDate to, String level, String campaignId) {
        String email = access.read(clientId).email();
        return build(clientId, email, from, to, level, campaignId);
    }

    public Overview overview(UUID clientId, LocalDate from, LocalDate to, String campaignId) {
        String email = access.read(clientId).email();
        PerformanceReport current = build(clientId, email, from, to, "campaign", campaignId);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        PerformanceReport previous = build(clientId, email, prevFrom, prevTo, "campaign", campaignId);
        return new Overview(current, previous.totals(), prevFrom, prevTo, changes(current.totals(), previous.totals()));
    }

    /** Builds a report without an access check; callers must have checked access (internal engines, scheduler). */
    public PerformanceReport build(UUID clientId, String requesterEmail, LocalDate from, LocalDate to, String level,
                                   String campaignId) {
        validateRange(from, to);
        String lvl = List.of("campaign", "adset", "ad").contains(level) ? level : "campaign";
        MetaData data = metaData(clientId, requesterEmail, from, to, lvl);
        Instant fromI = from.atStartOfDay(zone).toInstant();
        Instant toI = to.plusDays(1).atStartOfDay(zone).toInstant();
        Map<String, CrmOutcome> outcomes = crm.byObject(clientId, lvl, fromI, toI, from, to);
        return assemble(clientId, lvl, from, to, campaignId, data, outcomes);
    }

    /** Pure assembly of Meta rows + CRM outcomes into report rows and totals (unit-testable). */
    PerformanceReport assemble(UUID clientId, String level, LocalDate from, LocalDate to, String campaignFilter,
                               MetaData data, Map<String, CrmOutcome> outcomes) {
        List<DataNote> notes = new ArrayList<>(data.notes());
        Map<String, MetricSet> sets = new LinkedHashMap<>();
        Map<String, InsightRow> labels = new HashMap<>();
        for (InsightRow row : data.rows()) {
            String key = key(row, level);
            if (key == null || (campaignFilter != null && !campaignFilter.equals(row.campaignId()))) continue;
            sets.computeIfAbsent(key, k -> new MetricSet()).addMeta(row);
            labels.putIfAbsent(key, row);
        }
        MetricSet totals = new MetricSet();
        List<PerfRow> rows = new ArrayList<>();
        for (Map.Entry<String, MetricSet> e : sets.entrySet()) {
            MetricSet m = e.getValue();
            CrmOutcome outcome = outcomes.getOrDefault(e.getKey(), CrmOutcome.EMPTY);
            m.addCrm(outcome);
            totals.addAll(m);
            InsightRow label = labels.get(e.getKey());
            CampaignInfo campaign = data.campaigns().get(label.campaignId());
            rows.add(row(level, e.getKey(), name(label, level), label, campaign, m));
        }
        // CRM outcomes for objects Meta returned nothing for (other accounts, deleted ads) stay visible but outside totals.
        MetricSet orphan = new MetricSet();
        for (Map.Entry<String, CrmOutcome> e : outcomes.entrySet()) {
            if (sets.containsKey(e.getKey()) || campaignFilter != null) continue;
            orphan.addCrm(e.getValue());
        }
        if (orphan.crmLeads > 0 || orphan.bookings > 0) {
            notes.add(new DataNote("unattributed_crm", "info", orphan.crmLeads + " CRM lead(s) and " + orphan.bookings
                    + " booking(s) have no matching Meta " + level + " in the mapped ad accounts (unknown, organic or other accounts). "
                    + "They are excluded from totals and ROAS."));
        }
        if (campaignFilter != null && sets.size() == 1) {
            MetricSet only = sets.values().iterator().next();
            totals.setPeriodReach(only.reach, only.frequency);
        } else if (campaignFilter == null && data.periodReach().size() == 1) {
            String account = data.periodReach().keySet().iterator().next();
            totals.setPeriodReach(data.periodReach().get(account), data.periodFrequency().get(account));
        } else if (data.periodReach().size() > 1) {
            notes.add(new DataNote("reach_not_additive", "info",
                    "Reach is not shown in totals across several ad accounts because people can be reached by more than one account."));
        }
        rows.sort(Comparator.comparing(PerfRow::spend).reversed());
        PerfRow totalRow = row(level, "total", "All " + level + "s", null, null, totals);
        PerfRow orphanRow = row(level, "unattributed", "No matching Meta object", null, null, orphan);
        return new PerformanceReport(clientId.toString(), level, from, to, data.currency() == null ? appCurrency : data.currency(),
                props.attributionWindows(), data.available(), notes, METRIC_SOURCES, totalRow, orphanRow, rows);
    }

    PerfRow row(String level, String id, String name, InsightRow label, CampaignInfo campaign, MetricSet m) {
        List<String> flags = new ArrayList<>();
        if (m.impressions < props.minImpressions()) flags.add("low_sample");
        if (m.metaLeads >= 5 && m.crmLeads < m.metaLeads / 2) flags.add("meta_crm_lead_gap");
        if (m.spend.signum() > 0 && m.metaLeads == 0 && m.crmLeads == 0) flags.add("spend_without_leads");
        return new PerfRow(level, id, name, label == null ? null : label.campaignId(), label == null ? null : label.campaignName(),
                label == null ? null : label.adsetId(), campaign == null ? null : campaign.objective(),
                campaign == null ? null : campaign.effectiveStatus(), m.spend, m.impressions, m.reach, m.frequency,
                m.linkClicks, m.ctr(), m.cpc(), m.cpm(), m.metaLeads, m.metaCostPerLead(), m.conversionRate(), m.crmLeads,
                m.qualifiedLeads, m.qualifiedRate(), m.costPerQualifiedLead(), m.siteVisitsCompleted, m.costPerSiteVisit(),
                m.bookings, m.costPerBooking(), m.bookingValue, m.confirmedRevenue, m.roas(), flags);
    }

    /** Fetches (and briefly caches) the Meta side of a report. Missing access becomes a data note, not an error. */
    public MetaData metaData(UUID clientId, String requesterEmail, LocalDate from, LocalDate to, String level) {
        String cacheKey = clientId + "|" + requesterEmail + "|" + from + "|" + to + "|" + level;
        return cached(cacheKey, () -> fetchMeta(clientId, requesterEmail, from, to, level));
    }

    private MetaData fetchMeta(UUID clientId, String requesterEmail, LocalDate from, LocalDate to, String level) {
        List<DataNote> notes = new ArrayList<>();
        MetaAccess metaAccess = accessResolver.resolve(clientId, requesterEmail);
        if (!metaAccess.available()) {
            notes.add(new DataNote("meta_unavailable", "warning", metaAccess.note() + ". Only Campex CRM data is shown."));
            return new MetaData(false, notes, List.of(), Map.of(), Map.of(), Map.of(), null, metaAccess.adAccounts());
        }
        LocalDate provisionalFrom = today().minusDays(props.metaDelayDays());
        if (!to.isBefore(provisionalFrom)) {
            notes.add(new DataNote("provisional", "info", "Meta figures from " + provisionalFrom
                    + " onward may still change (reporting delay and conversion attribution)."));
        }
        List<InsightRow> rows = new ArrayList<>();
        Map<String, CampaignInfo> campaigns = new HashMap<>();
        Map<String, Long> reach = new LinkedHashMap<>();
        Map<String, BigDecimal> frequency = new LinkedHashMap<>();
        String currency = null;
        try {
            for (String account : metaAccess.adAccounts()) {
                AccountInfo info = meta.account(account, metaAccess.token());
                if (info.currency() != null && !info.currency().equalsIgnoreCase(appCurrency)) {
                    notes.add(new DataNote("currency_mismatch", "warning", "Ad account " + account + " reports in "
                            + info.currency() + " but CRM revenue is in " + appCurrency + ". It is excluded from this report."));
                    continue;
                }
                if (info.timezoneName() != null && !ZoneId.of(info.timezoneName()).getRules().getOffset(clock.instant())
                        .equals(zone.getRules().getOffset(clock.instant()))) {
                    notes.add(new DataNote("timezone_difference", "info", "Ad account " + account + " reports days in "
                            + info.timezoneName() + "; CRM days use " + zone.getId() + ". Daily figures can be offset by a few hours."));
                }
                currency = info.currency();
                rows.addAll(meta.insights(account, from, to, level, false, null, metaAccess.token()));
                for (InsightRow r : meta.insights(account, from, to, "account", false, null, metaAccess.token())) {
                    reach.put(account, r.reach());
                    frequency.put(account, r.frequency());
                }
                for (CampaignInfo c : meta.campaigns(account, info.currency(), metaAccess.token())) campaigns.put(c.id(), c);
            }
        } catch (MetaGraphException e) {
            notes.add(new DataNote("meta_error", "warning", "Meta Insights request failed (code " + e.graphCode()
                    + "). Only Campex CRM data is shown."));
            return new MetaData(false, notes, List.of(), Map.of(), Map.of(), Map.of(), null, metaAccess.adAccounts());
        }
        return new MetaData(true, notes, rows, campaigns, reach, frequency, currency, metaAccess.adAccounts());
    }

    // ---------------------------------------------------------------- trends

    public Trends trends(UUID clientId, LocalDate from, LocalDate to, String campaignId) {
        access.read(clientId);
        validateRange(from, to);
        List<DataNote> notes = new ArrayList<>();
        List<PerformanceSnapshot> snaps = snapshots.findByClientIdAndLevelAndDayBetweenOrderByDayAsc(clientId, "campaign", from, to);
        if (snaps.isEmpty()) {
            notes.add(new DataNote("no_snapshots", "warning",
                    "No daily Meta snapshots for this range yet. Use Refresh data, or wait for the nightly sync."));
        }
        Map<LocalDate, MetricSet> byDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d, new MetricSet());
        for (PerformanceSnapshot s : snaps) {
            if (campaignId != null && !campaignId.equals(s.getObjectId())) continue;
            MetricSet m = byDay.get(s.getDay());
            m.spend = m.spend.add(s.getSpend());
            m.impressions += s.getImpressions();
            m.linkClicks += s.getLinkClicks();
            m.metaLeads += s.getMetaLeads();
        }
        for (CrmOutcomeRepository.DailyCrm d : crm.dailyByCampaign(clientId, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(), zone.getId())) {
            if (campaignId != null && !campaignId.equals(d.campaignId())) continue;
            MetricSet m = byDay.get(d.day());
            if (m != null) {
                m.crmLeads += d.leads();
                m.qualifiedLeads += d.qualified();
            }
        }
        LocalDate provisional = today().minusDays(props.metaDelayDays());
        List<TrendPoint> points = new ArrayList<>();
        byDay.forEach((day, m) -> points.add(new TrendPoint(day, m.spend, m.impressions, m.linkClicks, m.ctr(), m.cpc(),
                m.cpm(), m.metaLeads, m.crmLeads, m.qualifiedLeads, !day.isBefore(provisional))));
        return new Trends(clientId.toString(), campaignId, from, to, !snaps.isEmpty(), notes, points);
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, BigDecimal> changes(PerfRow now, PerfRow before) {
        Map<String, BigDecimal> c = new LinkedHashMap<>();
        put(c, "spend", now.spend(), before.spend());
        put(c, "impressions", BigDecimal.valueOf(now.impressions()), BigDecimal.valueOf(before.impressions()));
        put(c, "link_clicks", BigDecimal.valueOf(now.linkClicks()), BigDecimal.valueOf(before.linkClicks()));
        put(c, "ctr", now.ctr(), before.ctr());
        put(c, "cpc", now.cpc(), before.cpc());
        put(c, "cpm", now.cpm(), before.cpm());
        put(c, "meta_leads", BigDecimal.valueOf(now.metaLeads()), BigDecimal.valueOf(before.metaLeads()));
        put(c, "qualified_leads", BigDecimal.valueOf(now.qualifiedLeads()), BigDecimal.valueOf(before.qualifiedLeads()));
        put(c, "cost_per_qualified_lead", now.costPerQualifiedLead(), before.costPerQualifiedLead());
        put(c, "site_visits", BigDecimal.valueOf(now.siteVisits()), BigDecimal.valueOf(before.siteVisits()));
        put(c, "bookings", BigDecimal.valueOf(now.bookings()), BigDecimal.valueOf(before.bookings()));
        put(c, "confirmed_revenue", now.confirmedRevenue(), before.confirmedRevenue());
        put(c, "roas", now.roas(), before.roas());
        return c;
    }

    /** Percentage change; null when there is no previous value to compare with. */
    static void put(Map<String, BigDecimal> c, String key, BigDecimal now, BigDecimal before) {
        if (now == null || before == null || before.signum() == 0) {
            c.put(key, null);
            return;
        }
        c.put(key, now.subtract(before).divide(before.abs(), 4, RoundingMode.HALF_UP));
    }

    static String key(InsightRow row, String level) {
        return switch (level) {
            case "adset" -> row.adsetId();
            case "ad" -> row.adId();
            default -> row.campaignId();
        };
    }

    static String name(InsightRow row, String level) {
        return switch (level) {
            case "adset" -> row.adsetName();
            case "ad" -> row.adName();
            default -> row.campaignName();
        };
    }

    void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 400) {
            throw new IllegalArgumentException("The date range can be at most 400 days");
        }
        if (to.isAfter(today())) {
            throw new IllegalArgumentException("to cannot be in the future");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> loader) {
        Instant now = clock.instant();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.at().plusSeconds(600).isAfter(now)) {
            return (T) entry.value();
        }
        T value = loader.get();
        cache.put(key, new CacheEntry(now, value));
        if (cache.size() > 500) {
            cache.entrySet().removeIf(e -> e.getValue().at().plusSeconds(600).isBefore(now));
        }
        return value;
    }

    public void evict(UUID clientId) {
        cache.keySet().removeIf(k -> k.startsWith(clientId + "|"));
    }

    static boolean same(String a, String b) {
        return Objects.equals(a, b);
    }
}
