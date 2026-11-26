package com.aivoice.lead.service.performance;

import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Every campaign a client can filter Performance AI by: the campaigns in its mapped Meta ad accounts (live), the
 * campaigns Campex published, and any campaign with stored performance history. Read-only.
 */
@Service
public class CampaignOptionsService {

    private static final Logger log = LoggerFactory.getLogger(CampaignOptionsService.class);

    public record CampaignOption(String id, String name, String status, String adAccountId, boolean hasData) {
    }

    public record CampaignOptions(List<CampaignOption> campaigns, String note) {
    }

    private final ClientAccessService access;
    private final MetaAccessResolver metaAccess;
    private final MetaExecutionGateway meta;
    private final JdbcTemplate jdbc;

    public CampaignOptionsService(ClientAccessService access, MetaAccessResolver metaAccess, MetaExecutionGateway meta,
                                  JdbcTemplate jdbc) {
        this.access = access;
        this.metaAccess = metaAccess;
        this.meta = meta;
        this.jdbc = jdbc;
    }

    public CampaignOptions list(UUID clientId) {
        String email = access.read(clientId).email();
        Map<String, CampaignOption> byId = new LinkedHashMap<>();
        String note = null;

        MetaAccess resolved = metaAccess.resolve(clientId, email);
        if (resolved.available()) {
            for (String account : resolved.adAccounts()) {
                try {
                    for (CampaignStatus c : meta.campaignStatuses(resolved.token(), account)) {
                        if (c.campaignId() != null && !c.campaignId().isBlank()) {
                            byId.put(c.campaignId(), new CampaignOption(c.campaignId(), c.name(), c.effectiveStatus() != null
                                    ? c.effectiveStatus() : c.status(), account, false));
                        }
                    }
                } catch (RuntimeException e) {
                    log.info("Could not list Meta campaigns for a mapped ad account ({})", e.getClass().getSimpleName());
                    note = "Meta did not return the live campaign list; showing campaigns Campex already knows.";
                }
            }
        } else {
            note = resolved.note();
        }

        jdbc.query("SELECT meta_campaign_id, name, COALESCE(meta_effective_status, meta_status, status) AS st, ad_account_id "
                        + "FROM campaign_executions WHERE client_id = ? AND meta_campaign_id IS NOT NULL",
                rs -> {
                    String id = rs.getString("meta_campaign_id");
                    byId.putIfAbsent(id, new CampaignOption(id, rs.getString("name"), rs.getString("st"),
                            rs.getString("ad_account_id"), false));
                }, clientId);

        jdbc.query("SELECT object_id, (ARRAY_AGG(name ORDER BY day DESC))[1] AS name FROM performance_snapshots "
                        + "WHERE client_id = ? AND level = 'campaign' GROUP BY object_id",
                rs -> {
                    String id = rs.getString("object_id");
                    CampaignOption known = byId.get(id);
                    byId.put(id, known == null
                            ? new CampaignOption(id, rs.getString("name"), null, null, true)
                            : new CampaignOption(id, known.name() == null ? rs.getString("name") : known.name(),
                                    known.status(), known.adAccountId(), true));
                }, clientId);

        List<CampaignOption> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing((CampaignOption o) -> o.name() == null ? "" : o.name().toLowerCase(Locale.ROOT))
                .thenComparing(CampaignOption::id));
        return new CampaignOptions(out, note);
    }
}
