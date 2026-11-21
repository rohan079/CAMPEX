package com.aivoice.lead.service.automation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure rule evaluation over daily campaign snapshots. No I/O. Every rule refuses to decide when the observation
 * window has too little data (Rule E: no premature optimization decisions).
 */
public final class RuleEvaluator {

    /** One campaign-day of Meta-reported delivery (minor units are not used: spend is in major currency units). */
    public record Day(String campaignId, String name, LocalDate day, BigDecimal spend, long impressions, long linkClicks,
                      long metaLeads) {
    }

    public record Rule(String type, String campaignId, BigDecimal threshold, int observationDays, int minSample) {
    }

    public record CampaignStatus(String campaignId, String name, String status, String effectiveStatus) {
    }

    public enum Outcome { TRIGGERED, NOT_TRIGGERED, INSUFFICIENT_DATA }

    public record Finding(String objectId, String objectName, Outcome outcome, String message, Map<String, Object> observed) {
    }

    public static final Set<String> DELIVERY_ISSUES = Set.of("DISAPPROVED", "WITH_ISSUES", "PENDING_BILLING_INFO");

    private RuleEvaluator() {
    }

    /**
     * @param lastCompleteDay last day whose Meta figures are treated as settled
     * @param latestDay       most recent day with (possibly provisional) data, used only for the spend alert
     */
    public static List<Finding> evaluate(Rule rule, List<Day> days, List<CampaignStatus> statuses,
                                         LocalDate lastCompleteDay, LocalDate latestDay) {
        List<Day> scoped = days.stream().filter(d -> rule.campaignId() == null || rule.campaignId().equals(d.campaignId())).toList();
        return switch (rule.type()) {
            case "spend_over_limit" -> spend(rule, scoped, latestDay);
            case "ctr_decline" -> perCampaign(rule, scoped, lastCompleteDay, true);
            case "cpl_above_threshold" -> perCampaign(rule, scoped, lastCompleteDay, false);
            case "delivery_issue" -> delivery(rule, statuses);
            default -> List.of();
        };
    }

    private static List<Finding> spend(Rule rule, List<Day> days, LocalDate day) {
        BigDecimal total = days.stream().filter(d -> d.day().equals(day)).map(Day::spend).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> observed = obs("day", day.toString(), "spend", total, "limit", rule.threshold());
        if (rule.threshold() == null) {
            return List.of(new Finding(rule.campaignId(), null, Outcome.INSUFFICIENT_DATA, "No spend limit is set for this rule.", observed));
        }
        boolean over = total.compareTo(rule.threshold()) > 0;
        return List.of(new Finding(rule.campaignId(), null, over ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                over ? "Spend on " + day + " was " + plain(total) + ", above the limit of " + plain(rule.threshold())
                        + ". Recent days can still be revised by Meta."
                        : "Spend on " + day + " was " + plain(total) + ", within the limit.", observed));
    }

    private static List<Finding> perCampaign(Rule rule, List<Day> days, LocalDate end, boolean ctr) {
        int n = rule.observationDays();
        LocalDate currentFrom = end.minusDays(n - 1L);
        LocalDate previousFrom = currentFrom.minusDays(n);
        Map<String, List<Day>> byCampaign = new LinkedHashMap<>();
        for (Day d : days) byCampaign.computeIfAbsent(d.campaignId(), k -> new ArrayList<>()).add(d);
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, List<Day>> e : byCampaign.entrySet()) {
            String name = e.getValue().getLast().name();
            Window cur = window(e.getValue(), currentFrom, end);
            if (ctr) {
                Window prev = window(e.getValue(), previousFrom, currentFrom.minusDays(1));
                out.add(ctrFinding(rule, e.getKey(), name, cur, prev));
            } else {
                out.add(cplFinding(rule, e.getKey(), name, cur));
            }
        }
        return out;
    }

    private static Finding ctrFinding(Rule rule, String id, String name, Window cur, Window prev) {
        Map<String, Object> observed = obs("current_impressions", cur.impressions, "previous_impressions", prev.impressions,
                "current_ctr", cur.ctr(), "previous_ctr", prev.ctr(), "min_sample", rule.minSample());
        if (cur.impressions < rule.minSample() || prev.impressions < rule.minSample() || prev.clicks == 0) {
            return new Finding(id, name, Outcome.INSUFFICIENT_DATA, "Not enough impressions in both windows to judge CTR (need "
                    + rule.minSample() + " each).", observed);
        }
        BigDecimal decline = BigDecimal.ONE.subtract(cur.ctr().divide(prev.ctr(), 6, RoundingMode.HALF_UP)).movePointRight(2)
                .setScale(1, RoundingMode.HALF_UP);
        observed.put("decline_pct", decline);
        BigDecimal threshold = rule.threshold() == null ? BigDecimal.valueOf(30) : rule.threshold();
        boolean hit = decline.compareTo(threshold) >= 0;
        return new Finding(id, name, hit ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED, "CTR " + pct(prev.ctr()) + " → "
                + pct(cur.ctr()) + " (" + (decline.signum() >= 0 ? "-" : "+") + plain(decline.abs()) + "%) over "
                + rule.observationDays() + " days vs the previous " + rule.observationDays() + ".", observed);
    }

    private static Finding cplFinding(Rule rule, String id, String name, Window cur) {
        Map<String, Object> observed = obs("spend", cur.spend, "meta_leads", cur.leads, "min_sample", rule.minSample(),
                "threshold", rule.threshold());
        if (rule.threshold() == null) {
            return new Finding(id, name, Outcome.INSUFFICIENT_DATA, "No cost-per-lead threshold is set for this rule.", observed);
        }
        if (cur.leads < Math.max(1, rule.minSample())) {
            return new Finding(id, name, Outcome.INSUFFICIENT_DATA, "Only " + cur.leads + " Meta-reported leads in "
                    + rule.observationDays() + " days (need " + Math.max(1, rule.minSample()) + "); no decision yet.", observed);
        }
        BigDecimal cpl = cur.spend.divide(BigDecimal.valueOf(cur.leads), 2, RoundingMode.HALF_UP);
        observed.put("cost_per_lead", cpl);
        boolean hit = cpl.compareTo(rule.threshold()) > 0;
        return new Finding(id, name, hit ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED, "Cost per Meta-reported lead "
                + plain(cpl) + " over " + rule.observationDays() + " days (" + cur.leads + " leads) vs threshold "
                + plain(rule.threshold()) + ".", observed);
    }

    private static List<Finding> delivery(Rule rule, List<CampaignStatus> statuses) {
        List<Finding> out = new ArrayList<>();
        for (CampaignStatus s : statuses) {
            if (rule.campaignId() != null && !rule.campaignId().equals(s.campaignId())) continue;
            boolean issue = s.effectiveStatus() != null && DELIVERY_ISSUES.contains(s.effectiveStatus());
            out.add(new Finding(s.campaignId(), s.name(), issue ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                    issue ? "Meta reports " + s.effectiveStatus().replace('_', ' ').toLowerCase() + "." : "Delivering normally ("
                            + s.effectiveStatus() + ").", obs("status", s.status(), "effective_status", s.effectiveStatus())));
        }
        return out;
    }

    private record Window(BigDecimal spend, long impressions, long clicks, long leads) {
        BigDecimal ctr() {
            return impressions == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP);
        }
    }

    private static Window window(List<Day> days, LocalDate from, LocalDate to) {
        BigDecimal spend = BigDecimal.ZERO;
        long imp = 0;
        long clicks = 0;
        long leads = 0;
        for (Day d : days) {
            if (d.day().isBefore(from) || d.day().isAfter(to)) continue;
            spend = spend.add(d.spend());
            imp += d.impressions();
            clicks += d.linkClicks();
            leads += d.metaLeads();
        }
        return new Window(spend, imp, clicks, leads);
    }

    private static Map<String, Object> obs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String pct(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
