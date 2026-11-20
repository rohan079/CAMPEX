package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.service.performance.Finding.Evidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Campaign- and ad-level optimization rules. Each rule needs a minimum sample, compares against peers with the same
 * objective over the same window, and returns its evidence. No rule fires on missing data.
 */
public final class OptimizationRules {

    private OptimizationRules() {
    }

    public static List<Finding> campaignFindings(List<PerfRow> campaigns, int minConversions, int minClicks,
                                                 String currency, String period) {
        List<Finding> out = new ArrayList<>();
        for (PerfRow row : campaigns) {
            List<PerfRow> peers = campaigns.stream().filter(p -> !p.id().equals(row.id())
                    && Objects.equals(p.objective(), row.objective())).toList();
            // Poor lead quality: enough CRM leads, qualified rate under half the peer rate.
            BigDecimal peerQualifiedRate = rate(peers, true, minConversions);
            if (row.crmLeads() >= minConversions && row.qualifiedRate() != null && peerQualifiedRate != null
                    && row.qualifiedRate().compareTo(peerQualifiedRate.multiply(new BigDecimal("0.5"))) < 0) {
                out.add(new Finding("optimization", "improve_lead_quality", row.id(), null, null, row.name(),
                        "Lead quality is low in " + row.name(),
                        row.qualifiedLeads() + " of " + row.crmLeads() + " CRM leads qualified (" + pct(row.qualifiedRate())
                                + ") in " + period + ".",
                        "Same-objective campaigns qualify " + pct(peerQualifiedRate) + " of their leads; this campaign is below half of that.",
                        "Estimate: lifting the qualified rate to the peer level would mean roughly "
                                + row.crmLeads() * scaledPct(peerQualifiedRate) / 100 + " qualified leads from the same lead volume.",
                        Finding.confidence(row.crmLeads(), minConversions), "low",
                        "Review targeting and the lead form (add budget/location/timeline questions) and state price and location in the ad.",
                        null, List.of(
                                ev("crm_leads", "CRM leads", row.crmLeads(), "count", "campex_tracked"),
                                ev("qualified_leads", "Qualified leads", row.qualifiedLeads(), "count", "crm_verified"),
                                new Evidence("qualified_rate", "Qualified rate", row.qualifiedRate(), "ratio", "derived", "campaign"),
                                new Evidence("peer_qualified_rate", "Peer qualified rate", peerQualifiedRate, "ratio", "derived", "same-objective campaigns")),
                        "optimization:improve_lead_quality:" + row.id()));
            }
            // Landing page: many link clicks, conversion rate under half the peer rate.
            BigDecimal peerConversion = rate(peers, false, minConversions);
            if (row.linkClicks() >= 2L * minClicks && row.conversionRate() != null && peerConversion != null
                    && row.conversionRate().compareTo(peerConversion.multiply(new BigDecimal("0.5"))) < 0) {
                out.add(new Finding("optimization", "improve_landing_page", row.id(), null, null, row.name(),
                        "Clicks are not turning into leads in " + row.name(),
                        row.linkClicks() + " link clicks produced " + row.metaLeads() + " Meta-reported leads ("
                                + pct(row.conversionRate()) + ") in " + period + ".",
                        "Same-objective campaigns convert " + pct(peerConversion) + " of link clicks; this is under half of that, so the landing page or form is the likely bottleneck.",
                        "Estimate: matching the peer conversion rate would mean about "
                                + row.linkClicks() * scaledPct(peerConversion) / 100 + " leads from the same clicks.",
                        Finding.confidence(row.linkClicks() / 10, minConversions), "low",
                        "Check landing-page speed and mobile layout, shorten the form, and make price, location and the call to action visible above the fold.",
                        null, List.of(
                                ev("link_clicks", "Link clicks", row.linkClicks(), "count", "meta_reported"),
                                ev("meta_leads", "Meta-reported leads", row.metaLeads(), "count", "meta_reported"),
                                new Evidence("conversion_rate", "Click-to-lead rate", row.conversionRate(), "ratio", "derived", "campaign"),
                                new Evidence("peer_conversion_rate", "Peer click-to-lead rate", peerConversion, "ratio", "derived", "same-objective campaigns")),
                        "optimization:improve_landing_page:" + row.id()));
            }
            // Meta vs CRM gap: Meta reports leads that never reach the CRM.
            if (row.metaLeads() >= minConversions && row.crmLeads() * 2 < row.metaLeads()) {
                out.add(new Finding("optimization", "check_lead_capture", row.id(), null, null, row.name(),
                        "Meta reports more leads than reached the CRM for " + row.name(),
                        "Meta reported " + row.metaLeads() + " leads; " + row.crmLeads() + " were attributed in the CRM (" + period + ").",
                        "Fewer than half of Meta-reported leads arrived in Campex with this campaign's attribution, so CRM-based costs may be overstated.",
                        "Estimate: none until the gap is explained; fixing capture improves the accuracy of every cost metric.",
                        Finding.confidence(row.metaLeads(), minConversions), "low",
                        "Check that the lead form/popup forwards campaign attribution (Campex.getAttribution()) and that instant-form leads are imported.",
                        null, List.of(ev("meta_leads", "Meta-reported leads", row.metaLeads(), "count", "meta_reported"),
                                ev("crm_leads", "CRM leads", row.crmLeads(), "count", "campex_tracked")),
                        "optimization:check_lead_capture:" + row.id()));
            }
        }
        return out;
    }

    /** Ad-level creative findings: low CTR, and high CTR with poor lead quality. */
    public static List<Finding> adFindings(List<PerfRow> ads, int minImpressions, String period) {
        List<PerfRow> eligible = ads.stream().filter(a -> a.impressions() >= minImpressions && a.ctr() != null).toList();
        List<Finding> out = new ArrayList<>();
        if (eligible.size() < 3) {
            return out;
        }
        BigDecimal median = median(eligible.stream().map(PerfRow::ctr).toList());
        long leads = eligible.stream().mapToLong(PerfRow::crmLeads).sum();
        long qualified = eligible.stream().mapToLong(PerfRow::qualifiedLeads).sum();
        BigDecimal accountQualified = leads == 0 ? null
                : BigDecimal.valueOf(qualified).divide(BigDecimal.valueOf(leads), 6, RoundingMode.HALF_UP);
        for (PerfRow ad : eligible) {
            if (ad.ctr().compareTo(median.multiply(new BigDecimal("0.6"))) < 0) {
                out.add(new Finding("creative", "test_new_creative", ad.campaignId(), ad.adsetId(), ad.id(), ad.name(),
                        "Test a new creative for " + ad.name(),
                        "Link CTR " + pct(ad.ctr()) + " on " + ad.impressions() + " impressions (" + period + ").",
                        "The median link CTR across " + eligible.size() + " comparable ads is " + pct(median) + "; this ad is below 60% of it.",
                        "Estimate: reaching the median CTR would give about " + ad.impressions() * scaledPct(median) / 100
                                + " link clicks from the same impressions.",
                        Finding.confidence(ad.linkClicks() / 10, 10), "low",
                        "Create a variant with a new image or headline (Creative Intelligence → Generate drafts) and test it against this ad.",
                        null, List.of(ev("impressions", "Impressions", ad.impressions(), "count", "meta_reported"),
                                new Evidence("ctr", "Link CTR", ad.ctr(), "ratio", "derived", "ad"),
                                new Evidence("median_ctr", "Median link CTR of comparable ads", median, "ratio", "derived", "ads with enough impressions")),
                        "creative:test_new_creative:" + ad.id()));
            } else if (ad.ctr().compareTo(median.multiply(new BigDecimal("1.3"))) >= 0 && ad.crmLeads() >= 5
                    && accountQualified != null && ad.qualifiedRate() != null
                    && ad.qualifiedRate().compareTo(accountQualified.multiply(new BigDecimal("0.5"))) < 0) {
                out.add(new Finding("creative", "clarify_messaging", ad.campaignId(), ad.adsetId(), ad.id(), ad.name(),
                        "High clicks but few qualified leads from " + ad.name(),
                        "Link CTR " + pct(ad.ctr()) + " (median " + pct(median) + "), but " + ad.qualifiedLeads() + " of "
                                + ad.crmLeads() + " leads qualified (" + period + ").",
                        "The ad attracts clicks above the median, while its qualified rate is under half of the "
                                + pct(accountQualified) + " seen across ads — the message may attract the wrong buyers.",
                        "Estimate: clearer qualifying information usually lowers clicks but raises the share of qualified leads.",
                        Finding.confidence(ad.crmLeads(), 10), "low",
                        "Test messaging that states price range, exact location and availability so unsuitable buyers self-select out.",
                        null, List.of(new Evidence("ctr", "Link CTR", ad.ctr(), "ratio", "derived", "ad"),
                                new Evidence("median_ctr", "Median link CTR", median, "ratio", "derived", "comparable ads"),
                                ev("crm_leads", "CRM leads", ad.crmLeads(), "count", "campex_tracked"),
                                ev("qualified_leads", "Qualified leads", ad.qualifiedLeads(), "count", "crm_verified")),
                        "creative:clarify_messaging:" + ad.id()));
            }
        }
        return out;
    }

    static BigDecimal rate(List<PerfRow> rows, boolean qualified, int minimum) {
        long num = 0;
        long den = 0;
        for (PerfRow r : rows) {
            long d = qualified ? r.crmLeads() : r.linkClicks();
            long n = qualified ? r.qualifiedLeads() : r.metaLeads();
            if ((qualified ? d : n) < minimum) continue;
            num += n;
            den += d;
        }
        return den == 0 ? null : BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 6, RoundingMode.HALF_UP);
    }

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    static Evidence ev(String metric, String label, long value, String unit, String source) {
        return new Evidence(metric, label, BigDecimal.valueOf(value), unit, source, "object");
    }

    static String pct(BigDecimal ratio) {
        return ratio == null ? "—" : ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    /** Ratio as a whole-number percentage for integer estimates. */
    static long scaledPct(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
