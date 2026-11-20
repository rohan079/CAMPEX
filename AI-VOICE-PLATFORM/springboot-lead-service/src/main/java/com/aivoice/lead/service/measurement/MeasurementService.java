package com.aivoice.lead.service.measurement;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.AttributionDtos.CampaignReport;
import com.aivoice.lead.dto.AttributionDtos.CampaignRow;
import com.aivoice.lead.dto.MeasurementDtos.FunnelStep;
import com.aivoice.lead.dto.MeasurementDtos.KpiReport;
import com.aivoice.lead.dto.MeasurementDtos.Metric;
import com.aivoice.lead.dto.MeasurementDtos.TrackedEventCount;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.MetaGraphClient.InsightRow;
import com.aivoice.lead.service.crm.FunnelSql;
import com.aivoice.lead.service.measurement.MetaSpendService.Spend;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard KPIs and campaign ROI/ROAS. Definitions:
 * <ul>
 *   <li>Lead funnel metrics use the <b>cohort of leads created in the range</b> (a lead counts once per stage it
 *       reached, even if it later became LOST).</li>
 *   <li>Visits count by visit date, bookings by booking date, confirmed revenue by confirmation date.</li>
 *   <li>Spend and impressions are Meta-reported for the client's mapped ad accounts over the same dates.</li>
 *   <li>ROAS = confirmed revenue ÷ Meta spend. ROI = (confirmed revenue − spend) ÷ spend. Booking value is shown but
 *       never treated as revenue.</li>
 * </ul>
 */
@Service
public class MeasurementService {

    public record KpiFilter(String campaignId, UUID agentId, UUID projectId, String adAccountId) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ClientAccessService access;
    private final MetaSpendService spendService;
    private final ZoneId zone;
    private final String currency;

    public MeasurementService(NamedParameterJdbcTemplate jdbc, ClientAccessService access, MetaSpendService spendService,
                              AppProperties props, @Value("${app.currency:INR}") String currency) {
        this.jdbc = jdbc;
        this.access = access;
        this.spendService = spendService;
        this.zone = props.zone();
        this.currency = currency;
    }

    // ---------------------------------------------------------------- KPIs

    @Transactional(readOnly = true)
    public KpiReport kpis(UUID clientId, Instant from, Instant to, KpiFilter filter) {
        Actor actor = access.read(clientId);
        MapSqlParameterSource p = params(clientId, from, to);
        String leadFilter = leadFilters(filter, p, "l", "ft");

        Map<String, Object> cohort = jdbc.queryForMap("""
                with cohort as (
                  select l.* from leads l
                  left join lead_attributions a on a.lead_id = l.id
                  left join lead_attribution_touches ft on ft.id = a.first_touch_id
                  where l.client_id = :cid and l.created_at >= :f and l.created_at < :t""" + " " + leadFilter + " " + """
                )
                select count(*) total,
                       count(*) filter (where """ + " " + FunnelSql.contacted("c") + " " + """
                ) contacted,
                       count(*) filter (where """ + " " + FunnelSql.qualified("c") + " " + """
                ) qualified,
                       count(*) filter (where exists (select 1 from site_visits v where v.lead_id = c.id and v.status <> 'RESCHEDULED')) visit_scheduled,
                       count(*) filter (where exists (select 1 from site_visits v where v.lead_id = c.id and v.status = 'COMPLETED')) visit_completed,
                       count(*) filter (where exists (select 1 from site_visits v where v.lead_id = c.id and v.status = 'COMPLETED')
                                          and exists (select 1 from sales s where s.lead_id = c.id and s.deleted_at is null
                                                      and s.status in """ + " " + FunnelSql.BOOKED_STATUSES + " " + """
                )) visit_and_booked,
                       count(*) filter (where exists (select 1 from sales s where s.lead_id = c.id and s.deleted_at is null
                                                      and s.status in """ + " " + FunnelSql.BOOKED_STATUSES + " " + """
                )) booked
                from cohort c""", p);

        String visitFilter = activityFilters(filter, p, "v.agent_id", "v.project_id", "v.lead_id");
        Map<String, Object> visits = jdbc.queryForMap("""
                select count(*) filter (where v.status <> 'RESCHEDULED' and v.scheduled_at >= :f and v.scheduled_at < :t) scheduled,
                       count(*) filter (where v.status = 'COMPLETED' and v.completed_at >= :f and v.completed_at < :t) completed
                from site_visits v where v.client_id = :cid""" + " " + visitFilter, p);

        String saleFilter = saleFilters(filter, p);
        Map<String, Object> sales = jdbc.queryForMap("""
                select count(*) filter (where s.status in """ + " " + FunnelSql.BOOKED_STATUSES + " " + """
                 and s.booking_date >= :fd and s.booking_date <= :td) bookings,
                       coalesce(sum(s.booking_value) filter (where s.status in """ + " " + FunnelSql.BOOKED_STATUSES + " " + """
                 and s.booking_date >= :fd and s.booking_date <= :td), 0) booking_value,
                       coalesce(sum(s.amount_collected - s.refunded_amount) filter (where s.booking_date >= :fd and s.booking_date <= :td), 0) collected,
                       coalesce(sum(s.confirmed_revenue) filter (where s.sale_confirmed_at >= :f and s.sale_confirmed_at < :t), 0) revenue
                from sales s where s.client_id = :cid and s.deleted_at is null""" + " " + saleFilter, p);

        List<TrackedEventCount> tracked = jdbc.query("""
                select event_name, count(*) n from conversion_events
                where client_id = :cid and event_time >= :f and event_time < :t and not is_test
                group by event_name order by n desc""", p,
                (rs, i) -> new TrackedEventCount(rs.getString("event_name"), rs.getLong("n")));

        // Spend cannot be split by agent or project: those filters hide cost metrics instead of guessing.
        boolean spendSplittable = filter.agentId() == null && filter.projectId() == null;
        Spend spend = spendSplittable
                ? spendService.spend(clientId, actor.email(), day(from), day(to.minusSeconds(1)), "campaign", filter.adAccountId())
                : new Spend(false, "Meta spend cannot be attributed to a sales agent or project", List.of(), List.of());
        BigDecimal spendValue = null;
        BigDecimal impressions = null;
        if (spend.available()) {
            List<InsightRow> rows = spend.rows().stream()
                    .filter(r -> filter.campaignId() == null || filter.campaignId().equals(r.campaignId())).toList();
            spendValue = rows.stream().map(InsightRow::spend).reduce(BigDecimal.ZERO, BigDecimal::add);
            impressions = BigDecimal.valueOf(rows.stream().mapToLong(InsightRow::impressions).sum());
        }

        long total = num(cohort, "total");
        long qualified = num(cohort, "qualified");
        long visitCompletedCohort = num(cohort, "visit_completed");
        long scheduledPeriod = num(visits, "scheduled");
        long completedPeriod = num(visits, "completed");
        long bookings = num(sales, "bookings");
        BigDecimal revenue = money(sales, "revenue");

        List<Metric> metrics = new ArrayList<>();
        metrics.add(count("total_leads", "Total leads", total, "campex_tracked", "Leads created in the period"));
        metrics.add(count("contacted_leads", "Contacted leads", num(cohort, "contacted"), "crm_verified", "Of the period's leads"));
        metrics.add(count("qualified_leads", "Qualified leads", qualified, "crm_verified", "Of the period's leads"));
        metrics.add(count("site_visits_scheduled", "Site visits scheduled", scheduledPeriod, "crm_verified", "Visits dated in the period"));
        metrics.add(count("site_visits_completed", "Site visits completed", completedPeriod, "crm_verified", "Completed in the period"));
        metrics.add(count("bookings", "Bookings", bookings, "crm_verified", "Booking date in the period"));
        metrics.add(new Metric("booking_value", "Booking value", money(sales, "booking_value"), currency, "manual_financial",
                "Agreed value of bookings; not revenue"));
        metrics.add(new Metric("confirmed_revenue", "Confirmed revenue", revenue, currency, "manual_financial",
                "Collected minus refunded on sales confirmed in the period"));
        metrics.add(new Metric("amount_collected", "Amount collected", money(sales, "collected"), currency, "manual_financial",
                "Net of refunds, for bookings in the period"));
        metrics.add(new Metric("meta_spend", "Meta spend", spendValue, currency, "meta_reported", spend.note()));
        metrics.add(new Metric("meta_impressions", "Meta impressions", impressions, "count", "meta_reported", spend.note()));
        metrics.add(derived("cost_per_lead", "Cost per lead", divide(spendValue, total)));
        metrics.add(derived("cost_per_qualified_lead", "Cost per qualified lead", divide(spendValue, qualified)));
        metrics.add(derived("cost_per_site_visit", "Cost per site visit", divide(spendValue, completedPeriod)));
        metrics.add(derived("cost_per_booking", "Cost per booking", divide(spendValue, bookings)));
        metrics.add(rate("lead_to_qualified_rate", "Lead → qualified", qualified, total));
        metrics.add(rate("lead_to_site_visit_rate", "Lead → site visit", visitCompletedCohort, total));
        metrics.add(rate("site_visit_to_booking_rate", "Site visit → booking", num(cohort, "visit_and_booked"), visitCompletedCohort));
        metrics.add(new Metric("roas", "ROAS", spendValue == null || spendValue.signum() == 0 ? null
                : revenue.divide(spendValue, 4, RoundingMode.HALF_UP), "ratio", "derived",
                "Confirmed revenue ÷ Meta spend"));

        List<FunnelStep> funnel = List.of(
                new FunnelStep("Leads", total, "campex_tracked"),
                new FunnelStep("Contacted", num(cohort, "contacted"), "crm_verified"),
                new FunnelStep("Qualified", qualified, "crm_verified"),
                new FunnelStep("Site visit scheduled", num(cohort, "visit_scheduled"), "crm_verified"),
                new FunnelStep("Site visit completed", visitCompletedCohort, "crm_verified"),
                new FunnelStep("Booked", num(cohort, "booked"), "crm_verified"));
        return new KpiReport(clientId.toString(), from, to, currency, spend.available(), spend.note(), spend.adAccounts(),
                metrics, funnel, tracked);
    }

    // ---------------------------------------------------------------- campaign report

    @Transactional(readOnly = true)
    public CampaignReport campaignReport(UUID clientId, Instant from, Instant to, String model, String groupBy,
                                         String adAccountId) {
        Actor actor = access.read(clientId);
        String touch = "last_touch".equals(model) ? "last_touch_id" : "first_touch_id";
        String level = switch (groupBy == null ? "campaign" : groupBy) {
            case "adset" -> "adset";
            case "ad" -> "ad";
            default -> "campaign";
        };
        MapSqlParameterSource p = params(clientId, from, to);
        // Column names come from the fixed switch above, never from user input.
        String keyExpr = "coalesce(t." + level + "_id, case when t.utm_campaign is not null then 'utm:' || t.utm_campaign end, 'unknown')";
        String labelExpr = "coalesce(t." + level + "_name, t." + level + "_id, t.utm_campaign)";
        Map<String, long[]> leadCounts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> campaignOf = new LinkedHashMap<>();
        jdbc.query("""
                select k, max(label) label, max(touch_campaign_id) campaign_id, count(*) leads,
                       count(*) filter (where """ + " " + FunnelSql.qualified("x") + " " + """
                ) qualified,
                       count(*) filter (where exists (select 1 from site_visits v where v.lead_id = x.id and v.status <> 'RESCHEDULED')) sched,
                       count(*) filter (where exists (select 1 from site_visits v where v.lead_id = x.id and v.status = 'COMPLETED')) done
                from (
                  select l.*, """ + " " + keyExpr + " k, " + labelExpr + " " + """
                 label, t.campaign_id as touch_campaign_id
                  from leads l
                  left join lead_attributions a on a.lead_id = l.id
                  left join lead_attribution_touches t on t.id = a.""" + " " + touch + " " + """

                  where l.client_id = :cid and l.created_at >= :f and l.created_at < :t
                ) x group by k""", p, rs -> {
            String key = rs.getString("k");
            leadCounts.put(key, new long[] {rs.getLong("leads"), rs.getLong("qualified"), rs.getLong("sched"), rs.getLong("done")});
            labels.put(key, rs.getString("label"));
            campaignOf.put(key, rs.getString("campaign_id"));
        });

        // Sales: first-touch uses the sale's own (overridable) association; last-touch re-maps lead sales to the lead's last touch.
        String saleKey = "last_touch".equals(model)
                ? "coalesce(t." + level + "_id, case when t.id is null then s." + level + "_id end, case when t.utm_campaign is not null then 'utm:' || t.utm_campaign end, 'unknown')"
                : "coalesce(s." + level + "_id, 'unknown')";
        Map<String, Object[]> money = new LinkedHashMap<>();
        jdbc.query("""
                select """ + " " + saleKey + " " + """
                 k,
                       count(*) filter (where s.status in """ + " " + FunnelSql.BOOKED_STATUSES + " " + """
                 and s.booking_date >= :fd and s.booking_date <= :td) bookings,
                       coalesce(sum(s.booking_value) filter (where s.status in """ + " " + FunnelSql.BOOKED_STATUSES + " " + """
                 and s.booking_date >= :fd and s.booking_date <= :td), 0) booking_value,
                       coalesce(sum(s.confirmed_revenue) filter (where s.sale_confirmed_at >= :f and s.sale_confirmed_at < :t), 0) revenue,
                       coalesce(sum(s.amount_collected - s.refunded_amount) filter (where s.booking_date >= :fd and s.booking_date <= :td), 0) collected
                from sales s
                left join lead_attributions a on a.lead_id = s.lead_id
                left join lead_attribution_touches t on t.id = a.last_touch_id
                where s.client_id = :cid and s.deleted_at is null
                group by k""", p, rs -> {
            money.put(rs.getString("k"), new Object[] {rs.getLong("bookings"), rs.getBigDecimal("booking_value"),
                    rs.getBigDecimal("revenue"), rs.getBigDecimal("collected")});
        });

        Spend spend = spendService.spend(clientId, actor.email(), day(from), day(to.minusSeconds(1)), level, adAccountId);
        Map<String, BigDecimal> spendBy = new LinkedHashMap<>();
        Map<String, Long> impressionsBy = new LinkedHashMap<>();
        for (InsightRow row : spend.rows()) {
            String key = switch (level) {
                case "adset" -> row.adsetId();
                case "ad" -> row.adId();
                default -> row.campaignId();
            };
            if (key == null) continue;
            spendBy.merge(key, row.spend(), BigDecimal::add);
            impressionsBy.merge(key, row.impressions(), Long::sum);
            labels.putIfAbsent(key, switch (level) {
                case "adset" -> row.adsetName();
                case "ad" -> row.adName();
                default -> row.campaignName();
            });
            campaignOf.putIfAbsent(key, row.campaignId());
        }

        List<String> keys = new ArrayList<>(leadCounts.keySet());
        money.keySet().stream().filter(k -> !keys.contains(k)).forEach(keys::add);
        spendBy.keySet().stream().filter(k -> !keys.contains(k)).forEach(keys::add);
        List<CampaignRow> rows = new ArrayList<>();
        for (String key : keys) {
            long[] c = leadCounts.getOrDefault(key, new long[4]);
            Object[] m = money.getOrDefault(key, new Object[] {0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal s = spend.available() ? spendBy.getOrDefault(key, "unknown".equals(key) || key.startsWith("utm:") ? null : BigDecimal.ZERO) : null;
            BigDecimal revenue = (BigDecimal) m[2];
            String label = "unknown".equals(key) ? "Unknown / direct"
                    : key.startsWith("utm:") ? key.substring(4) + " (UTM only)"
                    : labels.getOrDefault(key, key) == null ? key : labels.getOrDefault(key, key);
            rows.add(new CampaignRow(key, "unknown".equals(key) || key.startsWith("utm:") ? null : campaignOf.get(key),
                    label, c[0], c[1], c[2], c[3], (Long) m[0], (BigDecimal) m[1], revenue, (BigDecimal) m[3], s,
                    impressionsBy.get(key), divide(s, c[0]), divide(s, c[1]), divide(s, c[3]), divide(s, (Long) m[0]),
                    s == null || s.signum() == 0 ? null : revenue.divide(s, 4, RoundingMode.HALF_UP).doubleValue(),
                    s == null || s.signum() == 0 ? null : revenue.subtract(s).divide(s, 4, RoundingMode.HALF_UP).doubleValue()));
        }
        rows.sort(Comparator.comparing((CampaignRow r) -> r.confirmedRevenue()).reversed()
                .thenComparing(Comparator.comparingLong(CampaignRow::leads).reversed()));
        return new CampaignReport("last_touch".equals(model) ? "last_touch" : "first_touch", level, currency, from, to,
                spend.available(), spend.note(), rows);
    }

    // ---------------------------------------------------------------- helpers

    private MapSqlParameterSource params(UUID clientId, Instant from, Instant to) {
        return new MapSqlParameterSource("cid", clientId)
                .addValue("f", Timestamp.from(from)).addValue("t", Timestamp.from(to))
                .addValue("fd", Date.valueOf(day(from))).addValue("td", Date.valueOf(day(to.minusSeconds(1))));
    }

    private static String leadFilters(KpiFilter filter, MapSqlParameterSource p, String leadAlias, String touchAlias) {
        StringBuilder sql = new StringBuilder();
        if (filter.campaignId() != null && !filter.campaignId().isBlank()) {
            sql.append(" and ").append(touchAlias).append(".campaign_id = :camp");
            p.addValue("camp", filter.campaignId());
        }
        if (filter.agentId() != null) {
            sql.append(" and ").append(leadAlias).append(".assigned_agent_id = :agent");
            p.addValue("agent", filter.agentId());
        }
        if (filter.projectId() != null) {
            sql.append(" and ").append(leadAlias).append(".project_id = :project");
            p.addValue("project", filter.projectId());
        }
        return sql.toString();
    }

    private static String activityFilters(KpiFilter filter, MapSqlParameterSource p, String agentCol, String projectCol,
                                          String leadCol) {
        StringBuilder sql = new StringBuilder();
        if (filter.agentId() != null) sql.append(" and ").append(agentCol).append(" = :agent");
        if (filter.projectId() != null) sql.append(" and ").append(projectCol).append(" = :project");
        if (filter.campaignId() != null && !filter.campaignId().isBlank()) {
            sql.append(" and exists (select 1 from lead_attributions a join lead_attribution_touches ft on ft.id = a.first_touch_id")
                    .append(" where a.lead_id = ").append(leadCol).append(" and ft.campaign_id = :camp)");
        }
        return sql.toString();
    }

    private static String saleFilters(KpiFilter filter, MapSqlParameterSource p) {
        StringBuilder sql = new StringBuilder();
        if (filter.campaignId() != null && !filter.campaignId().isBlank()) sql.append(" and s.campaign_id = :camp");
        if (filter.agentId() != null) sql.append(" and s.agent_id = :agent");
        if (filter.projectId() != null) sql.append(" and s.project_id = :project");
        return sql.toString();
    }

    private LocalDate day(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    private static long num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static BigDecimal money(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal divide(BigDecimal spend, long count) {
        return spend == null || count == 0 ? null : spend.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private Metric count(String key, String label, long value, String source, String note) {
        return new Metric(key, label, BigDecimal.valueOf(value), "count", source, note);
    }

    private Metric derived(String key, String label, BigDecimal value) {
        return new Metric(key, label, value, currency, "derived", value == null ? "Needs Meta spend and a non-zero count" : "Meta spend ÷ CRM count");
    }

    private static Metric rate(String key, String label, long part, long whole) {
        return new Metric(key, label, whole == 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP),
                "ratio", "derived", "Cohort of leads created in the period");
    }
}
