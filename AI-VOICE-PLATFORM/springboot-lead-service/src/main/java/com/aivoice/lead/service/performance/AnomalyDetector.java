package com.aivoice.lead.service.performance;

import com.aivoice.lead.entity.PerformanceSnapshot;
import com.aivoice.lead.service.performance.CrmOutcomeRepository.DailyCrm;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic anomaly detection over daily campaign snapshots. The current window (last 3 complete days) is
 * compared with a 14-day baseline. An anomaly needs (a) a minimum sample in both windows, (b) a percentage change
 * past the metric's threshold and (c) when the baseline has ≥7 days of data, a z-score of the current daily mean
 * against the baseline days of at least 2 (3 for CRITICAL). Changes past the threshold that are not statistically
 * unusual are reported as INFO.
 */
public final class AnomalyDetector {

    public static final int CURRENT_DAYS = 3;
    public static final int BASELINE_DAYS = 14;

    public record Detected(String campaignId, String campaignName, String metric, String severity, BigDecimal previous,
                           BigDecimal current, BigDecimal pctChange, BigDecimal zScore, LocalDate baselineFrom,
                           LocalDate baselineTo, LocalDate currentFrom, LocalDate currentTo, String reason, String action) {
    }

    /** direction +1 = increase is bad, -1 = decrease is bad. */
    record Rule(String metric, int direction, double warn, double critical) {
    }

    static final List<Rule> RULES = List.of(
            new Rule("spend", 1, 0.5, 1.0),
            new Rule("ctr", -1, 0.25, 0.5),
            new Rule("cpc", 1, 0.3, 0.6),
            new Rule("cpm", 1, 0.3, 0.6),
            new Rule("meta_leads", -1, 0.4, 0.7),
            new Rule("conversion_rate", -1, 0.3, 0.6),
            new Rule("cost_per_qualified_lead", 1, 0.4, 0.8),
            new Rule("frequency", 1, 0.25, 0.6));

    private static final class Day {
        BigDecimal spend = BigDecimal.ZERO;
        long impressions;
        long linkClicks;
        long leads;
        long qualified;
        BigDecimal freqWeighted = BigDecimal.ZERO;
        long freqImpressions;
    }

    private AnomalyDetector() {
    }

    public static List<Detected> detect(List<PerformanceSnapshot> snapshots, List<DailyCrm> crm, LocalDate asOf,
                                        int minImpressions, int minClicks, int minConversions) {
        LocalDate curFrom = asOf.minusDays(CURRENT_DAYS - 1);
        LocalDate baseTo = curFrom.minusDays(1);
        LocalDate baseFrom = baseTo.minusDays(BASELINE_DAYS - 1);
        Map<String, Map<LocalDate, Day>> byCampaign = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (PerformanceSnapshot s : snapshots) {
            if (!"campaign".equals(s.getLevel()) || s.getDay().isBefore(baseFrom) || s.getDay().isAfter(asOf)) continue;
            Day d = byCampaign.computeIfAbsent(s.getObjectId(), k -> new HashMap<>()).computeIfAbsent(s.getDay(), k -> new Day());
            d.spend = d.spend.add(s.getSpend());
            d.impressions += s.getImpressions();
            d.linkClicks += s.getLinkClicks();
            d.leads += s.getMetaLeads();
            if (s.getFrequency() != null) {
                d.freqWeighted = d.freqWeighted.add(s.getFrequency().multiply(BigDecimal.valueOf(s.getImpressions())));
                d.freqImpressions += s.getImpressions();
            }
            names.putIfAbsent(s.getObjectId(), s.getName());
        }
        for (DailyCrm c : crm) {
            Map<LocalDate, Day> days = byCampaign.get(c.campaignId());
            if (days == null || c.day().isBefore(baseFrom) || c.day().isAfter(asOf)) continue;
            days.computeIfAbsent(c.day(), k -> new Day()).qualified += c.qualified();
        }
        List<Detected> out = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDate, Day>> e : byCampaign.entrySet()) {
            Day base = sum(e.getValue(), baseFrom, baseTo);
            Day cur = sum(e.getValue(), curFrom, asOf);
            int baseDays = (int) e.getValue().keySet().stream().filter(d -> !d.isAfter(baseTo)).count();
            if (base.spend.signum() == 0 || baseDays == 0) continue;
            for (Rule rule : RULES) {
                Double prev = value(rule.metric(), base, BASELINE_DAYS);
                Double now = value(rule.metric(), cur, CURRENT_DAYS);
                if (prev == null || now == null || !enoughSample(rule.metric(), base, cur, minImpressions, minClicks, minConversions)) continue;
                if (prev == 0) continue;
                double change = (now - prev) / Math.abs(prev);
                double bad = change * rule.direction();
                if (bad < rule.warn()) continue;
                Double z = zScore(rule.metric(), e.getValue(), baseFrom, baseTo, now);
                String severity;
                if (z != null && Math.abs(z) < 2) {
                    severity = "INFO";
                } else if (bad >= rule.critical() && (z == null || Math.abs(z) >= 3)) {
                    severity = "CRITICAL";
                } else {
                    severity = "WARNING";
                }
                if ("frequency".equals(rule.metric()) && now < 3 && !"INFO".equals(severity)) {
                    severity = "INFO";
                }
                out.add(new Detected(e.getKey(), names.get(e.getKey()), rule.metric(), severity, bd(prev), bd(now),
                        bd(change), z == null ? null : bd(z), baseFrom, baseTo, curFrom, asOf,
                        reason(rule.metric(), prev, now, change, z), action(rule.metric())));
            }
            // A campaign that spends without any qualified lead where the baseline had them.
            if (base.qualified >= 5 && cur.qualified == 0 && cur.spend.signum() > 0) {
                BigDecimal baseCpql = base.spend.divide(BigDecimal.valueOf(base.qualified), 2, RoundingMode.HALF_UP);
                if (cur.spend.compareTo(baseCpql.multiply(BigDecimal.valueOf(2))) >= 0) {
                    out.add(new Detected(e.getKey(), names.get(e.getKey()), "qualified_leads", "CRITICAL",
                            BigDecimal.valueOf(base.qualified), BigDecimal.ZERO, BigDecimal.valueOf(-1), null, baseFrom, baseTo,
                            curFrom, asOf, "No qualified leads in the last " + CURRENT_DAYS + " days despite spending "
                            + cur.spend + " (at least twice the baseline cost per qualified lead of " + baseCpql + ").",
                            action("cost_per_qualified_lead")));
                }
            }
        }
        return out;
    }

    static boolean enoughSample(String metric, Day base, Day cur, int minImpressions, int minClicks, int minConversions) {
        return switch (metric) {
            case "ctr", "cpm", "frequency" -> base.impressions >= minImpressions && cur.impressions >= minImpressions / 3;
            case "cpc" -> base.linkClicks >= minClicks && cur.linkClicks >= minClicks / 3;
            case "meta_leads" -> base.leads >= minConversions;
            case "conversion_rate" -> base.linkClicks >= minClicks && cur.linkClicks >= minClicks / 3 && base.leads >= minConversions;
            case "cost_per_qualified_lead" -> base.qualified >= 5 && cur.qualified >= 1;
            default -> true;
        };
    }

    /** Daily-average for volume metrics; ratio of totals for rates and costs. */
    static Double value(String metric, Day d, int days) {
        return switch (metric) {
            case "spend" -> d.spend.doubleValue() / days;
            case "meta_leads" -> (double) d.leads / days;
            case "ctr" -> d.impressions == 0 ? null : (double) d.linkClicks / d.impressions;
            case "cpc" -> d.linkClicks == 0 ? null : d.spend.doubleValue() / d.linkClicks;
            case "cpm" -> d.impressions == 0 ? null : d.spend.doubleValue() * 1000 / d.impressions;
            case "conversion_rate" -> d.linkClicks == 0 ? null : (double) d.leads / d.linkClicks;
            case "cost_per_qualified_lead" -> d.qualified == 0 ? null : d.spend.doubleValue() / d.qualified;
            case "frequency" -> d.freqImpressions == 0 ? null : d.freqWeighted.doubleValue() / d.freqImpressions;
            default -> null;
        };
    }

    /** z of the current value against the baseline's per-day values (needs ≥7 baseline days and non-zero spread). */
    static Double zScore(String metric, Map<LocalDate, Day> days, LocalDate from, LocalDate to, double current) {
        List<Double> values = new ArrayList<>();
        for (Map.Entry<LocalDate, Day> e : days.entrySet()) {
            if (e.getKey().isBefore(from) || e.getKey().isAfter(to)) continue;
            Double v = value(metric, e.getValue(), 1);
            if (v != null) values.add(v);
        }
        if (values.size() < 7) return null;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (values.size() - 1);
        double sd = Math.sqrt(var);
        return sd == 0 ? null : (current - mean) / sd;
    }

    private static Day sum(Map<LocalDate, Day> days, LocalDate from, LocalDate to) {
        Day total = new Day();
        days.forEach((day, d) -> {
            if (day.isBefore(from) || day.isAfter(to)) return;
            total.spend = total.spend.add(d.spend);
            total.impressions += d.impressions;
            total.linkClicks += d.linkClicks;
            total.leads += d.leads;
            total.qualified += d.qualified;
            total.freqWeighted = total.freqWeighted.add(d.freqWeighted);
            total.freqImpressions += d.freqImpressions;
        });
        return total;
    }

    static String reason(String metric, double prev, double now, double change, Double z) {
        String name = metric.replace('_', ' ');
        String dir = change > 0 ? "rose" : "fell";
        return name.substring(0, 1).toUpperCase() + name.substring(1) + " " + dir + " " + Math.round(Math.abs(change) * 100)
                + "% (" + fmt(metric, prev) + " → " + fmt(metric, now) + ") over the last " + CURRENT_DAYS
                + " complete days vs the previous " + BASELINE_DAYS + " days"
                + (z == null ? "." : String.format(java.util.Locale.ROOT, " (z = %.1f).", z));
    }

    static String fmt(String metric, double v) {
        return switch (metric) {
            case "ctr", "conversion_rate" -> String.format(java.util.Locale.ROOT, "%.2f%%", v * 100);
            case "meta_leads", "frequency" -> String.format(java.util.Locale.ROOT, "%.2f", v);
            default -> String.format(java.util.Locale.ROOT, "%.2f", v);
        };
    }

    static String action(String metric) {
        return switch (metric) {
            case "spend" -> "Check for budget edits, new ad sets or a changed bid strategy in Ads Manager's change history.";
            case "ctr" -> "Check creative fatigue (frequency) and recent creative or audience changes; prepare a fresh creative test.";
            case "cpc", "cpm" -> "Check auction competition and audience size; review placement and audience breakdowns for the cost increase.";
            case "meta_leads" -> "Check delivery status, rejected ads, the lead form and the landing page; confirm tracking still fires.";
            case "conversion_rate" -> "Test the landing page and lead form on mobile; check for site errors or form changes.";
            case "cost_per_qualified_lead", "qualified_leads" -> "Review recent lead quality in the CRM pipeline and audience changes before spending more.";
            case "frequency" -> "Audience may be saturating: refresh creatives or broaden the audience.";
            default -> "Review the campaign.";
        };
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
