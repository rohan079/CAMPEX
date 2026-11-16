package com.aivoice.lead.repository;

import com.aivoice.lead.dto.ClientDtos.ReachRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Read-side aggregation for the dashboard charts. Plain SQL: these are group-bys over date buckets
 * in the business timezone, which are clearer here than in JPQL.
 * {@code clientId == null} means "all clients".
 */
@Repository
public class AnalyticsRepository {

    public record Range(LocalDate from, LocalDate to, Instant fromInstant, Instant toInstant, String zone) {
    }

    public record DayCount(LocalDate day, long count) { }
    public record DaySales(LocalDate day, String channel, long count, BigDecimal amount) { }
    public record DayCalls(LocalDate day, long calls, long connected) { }
    public record AdLeads(String adId, String name, long leads) { }
    public record AdSalesRow(String adId, String channel, long count, BigDecimal amount) { }
    public record AdLocation(String adId, String adName, String location, long reach) { }
    public record ClientTotals(UUID clientId, long count, BigDecimal amount) { }
    public record ClientChannelTotals(UUID clientId, String channel, long count, BigDecimal amount) { }

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String clientClause(UUID clientId, String column) {
        return clientId == null ? "" : " and " + column + " = :cid";
    }

    private static MapSqlParameterSource params(Range range, UUID clientId) {
        MapSqlParameterSource source = new MapSqlParameterSource()
                .addValue("tz", range.zone())
                .addValue("f", Timestamp.from(range.fromInstant()))
                .addValue("t", Timestamp.from(range.toInstant()))
                .addValue("fd", range.from())
                .addValue("td", range.to());
        if (clientId != null) {
            source.addValue("cid", clientId);
        }
        return source;
    }

    public List<DayCount> leadsByDay(Range range, UUID clientId) {
        return jdbc.query("""
                select cast(created_at at time zone :tz as date) as d, count(*) as n
                from leads
                where created_at >= :f and created_at < :t""" + clientClause(clientId, "client_id") + """

                group by d""", params(range, clientId),
                (rs, i) -> new DayCount(rs.getDate("d").toLocalDate(), rs.getLong("n")));
    }

    public long adLeads(Range range, UUID clientId) {
        Long value = jdbc.queryForObject("""
                select count(*) from leads
                where created_at >= :f and created_at < :t
                  and source in ('ad_popup', 'ctwa')""" + clientClause(clientId, "client_id"),
                params(range, clientId), Long.class);
        return value == null ? 0 : value;
    }

    public List<DaySales> salesByDay(Range range, UUID clientId) {
        return jdbc.query("""
                select cast(sold_at at time zone :tz as date) as d, channel, count(*) as n, sum(amount) as amt
                from sales
                where sold_at >= :f and sold_at < :t""" + clientClause(clientId, "client_id") + """

                group by d, channel""", params(range, clientId),
                (rs, i) -> new DaySales(rs.getDate("d").toLocalDate(), rs.getString("channel"),
                        rs.getLong("n"), rs.getBigDecimal("amt")));
    }

    /** Calls that were actually attempted; queued, cancelled and blocked ones never reached the phone. */
    public List<DayCalls> callsByDay(Range range, UUID clientId) {
        return jdbc.query("""
                select cast(c.created_at at time zone :tz as date) as d,
                       count(*) as total,
                       count(*) filter (where c.status = 'completed') as connected
                from voice_calls c join leads l on l.id = c.lead_id
                where c.created_at >= :f and c.created_at < :t
                  and c.status not in ('queued', 'cancelled', 'blocked_no_consent')""" + clientClause(clientId, "l.client_id") + """

                group by d""", params(range, clientId),
                (rs, i) -> new DayCalls(rs.getDate("d").toLocalDate(), rs.getLong("total"), rs.getLong("connected")));
    }

    public List<AdLeads> leadsByAd(Range range, UUID clientId) {
        return jdbc.query("""
                select coalesce(ad_id, '') as ad, max(ad_headline) as name, count(*) as n
                from leads
                where created_at >= :f and created_at < :t""" + clientClause(clientId, "client_id") + """

                group by ad""", params(range, clientId),
                (rs, i) -> new AdLeads(rs.getString("ad"), rs.getString("name"), rs.getLong("n")));
    }

    public List<AdSalesRow> salesByAd(Range range, UUID clientId) {
        return jdbc.query("""
                select coalesce(ad_id, '') as ad, channel, count(*) as n, sum(amount) as amt
                from sales
                where sold_at >= :f and sold_at < :t""" + clientClause(clientId, "client_id") + """

                group by ad, channel""", params(range, clientId),
                (rs, i) -> new AdSalesRow(rs.getString("ad"), rs.getString("channel"),
                        rs.getLong("n"), rs.getBigDecimal("amt")));
    }

    public List<AdLocation> reachByAdAndLocation(Range range, UUID clientId) {
        return jdbc.query("""
                select ad_id, max(ad_name) as name, location, sum(reach) as r
                from ad_reach
                where day between :fd and :td""" + clientClause(clientId, "client_id") + """

                group by ad_id, location""", params(range, clientId),
                (rs, i) -> new AdLocation(rs.getString("ad_id"), rs.getString("name"),
                        rs.getString("location"), rs.getLong("r")));
    }

    public List<String[]> adNames(UUID clientId) {
        String sql = "select external_id, name from ads" + (clientId == null ? "" : " where client_id = :cid");
        SqlParameterSource source = clientId == null ? new MapSqlParameterSource()
                : new MapSqlParameterSource("cid", clientId);
        return jdbc.query(sql, source, (rs, i) -> new String[] {rs.getString("external_id"), rs.getString("name")});
    }

    // ---- per-client comparison

    public List<ClientTotals> leadsPerClient(Range range) {
        return jdbc.query("""
                select client_id, count(*) as n from leads
                where created_at >= :f and created_at < :t group by client_id""", params(range, null),
                (rs, i) -> new ClientTotals(rs.getObject("client_id", UUID.class), rs.getLong("n"), BigDecimal.ZERO));
    }

    public List<ClientTotals> callsPerClient(Range range) {
        return jdbc.query("""
                select l.client_id, count(*) as n
                from voice_calls c join leads l on l.id = c.lead_id
                where c.created_at >= :f and c.created_at < :t
                  and c.status not in ('queued', 'cancelled', 'blocked_no_consent')
                group by l.client_id""", params(range, null),
                (rs, i) -> new ClientTotals(rs.getObject("client_id", UUID.class), rs.getLong("n"), BigDecimal.ZERO));
    }

    public List<ClientChannelTotals> salesPerClient(Range range) {
        return jdbc.query("""
                select client_id, channel, count(*) as n, sum(amount) as amt from sales
                where sold_at >= :f and sold_at < :t group by client_id, channel""", params(range, null),
                (rs, i) -> new ClientChannelTotals(rs.getObject("client_id", UUID.class), rs.getString("channel"),
                        rs.getLong("n"), rs.getBigDecimal("amt")));
    }

    public List<ClientTotals> reachPerClient(Range range) {
        return jdbc.query("""
                select client_id, sum(reach) as n from ad_reach
                where day between :fd and :td group by client_id""", params(range, null),
                (rs, i) -> new ClientTotals(rs.getObject("client_id", UUID.class), rs.getLong("n"), BigDecimal.ZERO));
    }

    public List<ClientTotals> leadCountsPerClient() {
        return jdbc.query("select client_id, count(*) as n from leads group by client_id",
                new MapSqlParameterSource(),
                (rs, i) -> new ClientTotals(rs.getObject("client_id", UUID.class), rs.getLong("n"), BigDecimal.ZERO));
    }

    // ---- reach import

    /** Inserts or replaces one row per (client, ad, location, day). */
    public int upsertReach(UUID clientId, List<ReachRow> rows) {
        List<SqlParameterSource> batch = new ArrayList<>();
        for (ReachRow row : rows) {
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("cid", clientId)
                    .addValue("ad", row.adId().trim())
                    .addValue("name", row.adName() == null || row.adName().isBlank() ? null : row.adName().trim())
                    .addValue("loc", row.location().trim())
                    .addValue("reach", row.reach())
                    .addValue("imps", row.impressions())
                    .addValue("day", row.day()));
        }
        jdbc.batchUpdate("""
                insert into ad_reach (id, client_id, ad_id, ad_name, location, reach, impressions, day)
                values (:id, :cid, :ad, :name, :loc, :reach, :imps, :day)
                on conflict (client_id, ad_id, location, day)
                do update set reach = excluded.reach, impressions = excluded.impressions,
                              ad_name = coalesce(excluded.ad_name, ad_reach.ad_name)""",
                batch.toArray(new SqlParameterSource[0]));
        return rows.size();
    }
}
