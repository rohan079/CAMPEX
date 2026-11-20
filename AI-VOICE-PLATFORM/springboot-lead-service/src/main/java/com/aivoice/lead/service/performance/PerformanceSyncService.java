package com.aivoice.lead.service.performance;

import com.aivoice.lead.config.PerformanceProperties;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.dto.PerformanceDtos.SyncResult;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.aivoice.lead.service.performance.MetaPerformanceClient.AccountInfo;
import com.aivoice.lead.service.performance.MetaPerformanceClient.InsightRow;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores daily Meta delivery per campaign and per ad. Existing days are replaced (Meta revises recent days), so the
 * sync is idempotent. Only delivery metrics are stored; CRM data stays in the CRM tables.
 */
@Service
public class PerformanceSyncService {

    private final MetaAccessResolver accessResolver;
    private final MetaPerformanceClient meta;
    private final NamedParameterJdbcTemplate jdbc;
    private final PerformanceProperties props;
    private final Clock clock;

    public PerformanceSyncService(MetaAccessResolver accessResolver, MetaPerformanceClient meta,
                                  NamedParameterJdbcTemplate jdbc, PerformanceProperties props, Clock clock) {
        this.accessResolver = accessResolver;
        this.meta = meta;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public SyncResult sync(UUID clientId, String requesterEmail, LocalDate from, LocalDate to) {
        List<DataNote> notes = new ArrayList<>();
        MetaAccess metaAccess = accessResolver.resolve(clientId, requesterEmail);
        if (!metaAccess.available()) {
            notes.add(new DataNote("meta_unavailable", "warning", metaAccess.note()));
            return new SyncResult(0, metaAccess.adAccounts(), notes);
        }
        int stored = 0;
        Instant now = clock.instant();
        for (String account : metaAccess.adAccounts()) {
            try {
                AccountInfo info = meta.account(account, metaAccess.token());
                for (String level : List.of("campaign", "ad")) {
                    List<InsightRow> rows = meta.insights(account, from, to, level, true, null, metaAccess.token());
                    stored += replace(clientId, account, level, from, to, rows, info.currency(), now);
                }
            } catch (MetaGraphException e) {
                notes.add(new DataNote("meta_error", "warning", "Sync of " + account + " failed (Meta code "
                        + e.graphCode() + "). Existing snapshots were kept."));
            }
        }
        return new SyncResult(stored, metaAccess.adAccounts(), notes);
    }

    int replace(UUID clientId, String account, String level, LocalDate from, LocalDate to, List<InsightRow> rows,
                String currency, Instant now) {
        jdbc.update("delete from performance_snapshots where client_id = :cid and ad_account_id = :acc and level = :lvl"
                + " and day between :f and :t", new MapSqlParameterSource("cid", clientId).addValue("acc", account)
                .addValue("lvl", level).addValue("f", Date.valueOf(from)).addValue("t", Date.valueOf(to)));
        List<SqlParameterSource> batch = new ArrayList<>();
        for (InsightRow r : rows) {
            String objectId = "ad".equals(level) ? r.adId() : r.campaignId();
            if (objectId == null || r.dateStart() == null) continue;
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID()).addValue("cid", clientId).addValue("acc", account)
                    .addValue("lvl", level).addValue("oid", objectId).addValue("camp", r.campaignId())
                    .addValue("adset", r.adsetId()).addValue("name", "ad".equals(level) ? r.adName() : r.campaignName())
                    .addValue("day", Date.valueOf(r.dateStart())).addValue("spend", r.spend())
                    .addValue("imp", r.impressions()).addValue("reach", r.reach()).addValue("freq", r.frequency())
                    .addValue("clicks", r.clicks()).addValue("link", r.linkClicks()).addValue("leads", r.metaLeads())
                    .addValue("cur", currency).addValue("attr", props.attributionWindows())
                    .addValue("at", Timestamp.from(now)));
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into performance_snapshots (id, client_id, ad_account_id, level, object_id, campaign_id, adset_id,
                      name, day, spend, impressions, reach, frequency, clicks, link_clicks, meta_leads, currency,
                      attribution_setting, fetched_at)
                    values (:id, :cid, :acc, :lvl, :oid, :camp, :adset, :name, :day, :spend, :imp, :reach, :freq, :clicks,
                      :link, :leads, :cur, :attr, :at)
                    on conflict (client_id, level, object_id, day) do update set spend = excluded.spend,
                      impressions = excluded.impressions, reach = excluded.reach, frequency = excluded.frequency,
                      clicks = excluded.clicks, link_clicks = excluded.link_clicks, meta_leads = excluded.meta_leads,
                      name = excluded.name, fetched_at = excluded.fetched_at""", batch.toArray(new SqlParameterSource[0]));
        }
        return batch.size();
    }

}
