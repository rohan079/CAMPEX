package com.aivoice.lead.service.performance;

import com.aivoice.lead.service.crm.FunnelSql;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * CRM outcomes per Meta object, using the same rules as the Phase 1 measurement KPIs: leads by first touch for the
 * lead cohort created in the period; bookings by booking date; confirmed revenue by confirmation date, by the
 * sale's campaign/ad set/ad association. Keys are Meta object ids; leads without one are under "unknown".
 */
@Repository
public class CrmOutcomeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CrmOutcomeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DailyCrm(LocalDate day, String campaignId, long leads, long qualified) {
    }

    public record SegmentCount(String dimension, String segment, long leads, long qualified, long visitsCompleted, long bookings) {
    }

    public Map<String, CrmOutcome> byObject(UUID clientId, String level, Instant from, Instant to, LocalDate fromDay,
                                            LocalDate toDay) {
        String col = column(level);
        MapSqlParameterSource p = params(clientId, from, to, fromDay, toDay);
        Map<String, long[]> leads = new HashMap<>();
        jdbc.query("select coalesce(t." + col + ", 'unknown') k, count(*) n,"
                + " count(*) filter (where " + FunnelSql.qualified("l") + ") q,"
                + " count(*) filter (where exists (select 1 from site_visits v where v.lead_id = l.id and v.status <> 'RESCHEDULED')) vs,"
                + " count(*) filter (where exists (select 1 from site_visits v where v.lead_id = l.id and v.status = 'COMPLETED')) vc"
                + " from leads l left join lead_attributions a on a.lead_id = l.id"
                + " left join lead_attribution_touches t on t.id = a.first_touch_id"
                + " where l.client_id = :cid and l.created_at >= :f and l.created_at < :t group by k", p,
                rs -> { leads.put(rs.getString("k"), new long[] {rs.getLong("n"), rs.getLong("q"), rs.getLong("vs"), rs.getLong("vc")}); });
        Map<String, Object[]> sales = new HashMap<>();
        jdbc.query("select coalesce(s." + col + ", 'unknown') k,"
                + " count(*) filter (where s.status in " + FunnelSql.BOOKED_STATUSES + " and s.booking_date between :fd and :td) b,"
                + " coalesce(sum(s.booking_value) filter (where s.status in " + FunnelSql.BOOKED_STATUSES
                + " and s.booking_date between :fd and :td), 0) bv,"
                + " coalesce(sum(s.confirmed_revenue) filter (where s.sale_confirmed_at >= :f and s.sale_confirmed_at < :t), 0) rev"
                + " from sales s where s.client_id = :cid and s.deleted_at is null group by k", p,
                rs -> { sales.put(rs.getString("k"), new Object[] {rs.getLong("b"), rs.getBigDecimal("bv"), rs.getBigDecimal("rev")}); });
        Map<String, CrmOutcome> result = new LinkedHashMap<>();
        for (String key : union(leads.keySet(), sales.keySet())) {
            long[] l = leads.getOrDefault(key, new long[4]);
            Object[] s = sales.getOrDefault(key, new Object[] {0L, BigDecimal.ZERO, BigDecimal.ZERO});
            result.put(key, new CrmOutcome(l[0], l[1], l[2], l[3], (Long) s[0], (BigDecimal) s[1], (BigDecimal) s[2]));
        }
        return result;
    }

    /** Leads and qualified leads per lead-creation day and first-touch campaign (business time zone). */
    public List<DailyCrm> dailyByCampaign(UUID clientId, Instant from, Instant to, String zone) {
        MapSqlParameterSource p = params(clientId, from, to, null, null).addValue("tz", zone);
        return jdbc.query("select cast(l.created_at at time zone :tz as date) d, coalesce(t.campaign_id, 'unknown') c,"
                + " count(*) n, count(*) filter (where " + FunnelSql.qualified("l") + ") q"
                + " from leads l left join lead_attributions a on a.lead_id = l.id"
                + " left join lead_attribution_touches t on t.id = a.first_touch_id"
                + " where l.client_id = :cid and l.created_at >= :f and l.created_at < :t group by d, c order by d", p,
                (rs, i) -> new DailyCrm(rs.getDate("d").toLocalDate(), rs.getString("c"), rs.getLong("n"), rs.getLong("q")));
    }

    /**
     * CRM distributions for real-estate segments (preferred location, timeline, purpose, project). Only leads that
     * have the field captured are counted; nothing is inferred.
     */
    public List<SegmentCount> crmSegments(UUID clientId, Instant from, Instant to) {
        MapSqlParameterSource p = params(clientId, from, to, null, null);
        String visit = "exists (select 1 from site_visits v where v.lead_id = l.id and v.status = 'COMPLETED')";
        String booked = "exists (select 1 from sales s where s.lead_id = l.id and s.deleted_at is null and s.status in "
                + FunnelSql.BOOKED_STATUSES + ")";
        String agg = " count(*) n, count(*) filter (where " + FunnelSql.qualified("l") + ") q, count(*) filter (where "
                + visit + ") v, count(*) filter (where " + booked + ") b";
        String base = " from leads l join lead_qualifications lq on lq.lead_id = l.id"
                + " where l.client_id = :cid and l.created_at >= :f and l.created_at < :t";
        String sql = "select 'preferred_location' dim, lower(trim(lq.preferred_location)) seg," + agg + base
                + " and lq.preferred_location is not null group by seg"
                + " union all select 'purchase_timeline', lq.purchase_timeline," + agg + base
                + " and lq.purchase_timeline is not null group by lq.purchase_timeline"
                + " union all select 'purpose', lq.purpose," + agg + base + " and lq.purpose is not null group by lq.purpose"
                + " union all select 'project', p.name," + agg + " from leads l join lead_qualifications lq on lq.lead_id = l.id"
                + " join client_projects p on p.id = l.project_id where l.client_id = :cid and l.created_at >= :f"
                + " and l.created_at < :t group by p.name";
        return jdbc.query(sql, p, (rs, i) -> new SegmentCount(rs.getString("dim"), rs.getString("seg"), rs.getLong("n"),
                rs.getLong("q"), rs.getLong("v"), rs.getLong("b")));
    }

    /** Leads whose preferred location matches their project's location, and how they convert. */
    public Map<String, long[]> locationFit(UUID clientId, Instant from, Instant to) {
        MapSqlParameterSource p = params(clientId, from, to, null, null);
        Map<String, long[]> out = new LinkedHashMap<>();
        jdbc.query("select case when lower(p.location) like '%' || lower(trim(lq.preferred_location)) || '%'"
                + " or lower(trim(lq.preferred_location)) like '%' || lower(p.location) || '%' then 'match' else 'mismatch' end fit,"
                + " count(*) n, count(*) filter (where exists (select 1 from site_visits v where v.lead_id = l.id and v.status = 'COMPLETED')) v,"
                + " count(*) filter (where exists (select 1 from sales s where s.lead_id = l.id and s.deleted_at is null and s.status in "
                + FunnelSql.BOOKED_STATUSES + ")) b"
                + " from leads l join lead_qualifications lq on lq.lead_id = l.id join client_projects p on p.id = l.project_id"
                + " where l.client_id = :cid and l.created_at >= :f and l.created_at < :t"
                + " and lq.preferred_location is not null and p.location is not null group by fit", p,
                rs -> { out.put(rs.getString("fit"), new long[] {rs.getLong("n"), rs.getLong("v"), rs.getLong("b")}); });
        return out;
    }

    static String column(String level) {
        return switch (level) {
            case "adset" -> "adset_id";
            case "ad" -> "ad_id";
            default -> "campaign_id";
        };
    }

    private static MapSqlParameterSource params(UUID clientId, Instant from, Instant to, LocalDate fd, LocalDate td) {
        MapSqlParameterSource p = new MapSqlParameterSource("cid", clientId).addValue("f", Timestamp.from(from))
                .addValue("t", Timestamp.from(to));
        if (fd != null) p.addValue("fd", Date.valueOf(fd)).addValue("td", Date.valueOf(td));
        return p;
    }

    private static List<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
