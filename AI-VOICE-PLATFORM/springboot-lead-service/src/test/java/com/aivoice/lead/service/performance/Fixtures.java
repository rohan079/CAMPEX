package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.entity.ClientBudgetPolicy;
import com.aivoice.lead.service.performance.MetaPerformanceClient.CampaignInfo;
import com.aivoice.lead.service.performance.MetaPerformanceClient.InsightRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class Fixtures {

    private Fixtures() {
    }

    static InsightRow insight(String campaign, String ad, String spend, long impressions, Long reach, long linkClicks, long leads) {
        return new InsightRow(campaign, "Campaign " + campaign, ad == null ? null : "as-" + ad, null, ad,
                ad == null ? null : "Ad " + ad, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), new BigDecimal(spend),
                impressions, reach, reach == null ? null : new BigDecimal("1.5"), linkClicks + 5, linkClicks, leads, "INR", Map.of());
    }

    /** A campaign row built from totals the way PerformanceService does. */
    static PerfRow campaign(String id, String objective, String spend, long impressions, long linkClicks, long metaLeads,
                            long crmLeads, long qualified, long visits, long bookings, String revenue) {
        MetricSet m = new MetricSet();
        m.spend = new BigDecimal(spend);
        m.impressions = impressions;
        m.linkClicks = linkClicks;
        m.metaLeads = metaLeads;
        m.crmLeads = crmLeads;
        m.qualifiedLeads = qualified;
        m.siteVisitsCompleted = visits;
        m.bookings = bookings;
        m.confirmedRevenue = new BigDecimal(revenue);
        return new PerfRow("campaign", id, "Campaign " + id, id, "Campaign " + id, null, objective, "ACTIVE", m.spend,
                m.impressions, null, null, m.linkClicks, m.ctr(), m.cpc(), m.cpm(), m.metaLeads, m.metaCostPerLead(),
                m.conversionRate(), m.crmLeads, m.qualifiedLeads, m.qualifiedRate(), m.costPerQualifiedLead(),
                m.siteVisitsCompleted, m.costPerSiteVisit(), m.bookings, m.costPerBooking(), m.bookingValue,
                m.confirmedRevenue, m.roas(), List.of());
    }

    static PerfRow ad(String id, String campaign, long impressions, long linkClicks, long crmLeads, long qualified) {
        PerfRow c = campaign(id, null, "1000", impressions, linkClicks, crmLeads, crmLeads, qualified, 0, 0, "0");
        return new PerfRow("ad", id, "Ad " + id, campaign, null, "as-" + id, null, null, c.spend(), c.impressions(), null,
                null, c.linkClicks(), c.ctr(), c.cpc(), c.cpm(), c.metaLeads(), c.metaCostPerLead(), c.conversionRate(),
                c.crmLeads(), c.qualifiedLeads(), c.qualifiedRate(), c.costPerQualifiedLead(), 0, null, 0, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, List.of());
    }

    static CampaignInfo info(String id, String objective, String daily) {
        return new CampaignInfo(id, "Campaign " + id, objective, "ACTIVE", "ACTIVE",
                daily == null ? null : new BigDecimal(daily), null, "campaign");
    }

    static ClientBudgetPolicy policy(int minQualified) {
        ClientBudgetPolicy p = new ClientBudgetPolicy();
        p.setMinQualifiedLeads(minQualified);
        return p;
    }
}
