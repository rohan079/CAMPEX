package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.entity.ClientBudgetPolicy;
import com.aivoice.lead.service.performance.Finding.Evidence;
import com.aivoice.lead.service.performance.MetaPerformanceClient.CampaignInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Budget analysis and recommendations. Pure and deterministic. Campaigns are only compared with peers sharing the
 * same objective over the same window, decisions need a minimum number of qualified leads, proposed changes are
 * capped by the client's max change and spending limits, and nothing is ever applied to Meta.
 */
public final class BudgetEngine {

    public record BudgetLine(String campaignId, String name, String objective, String status, String budgetLevel,
                             BigDecimal dailyBudget, BigDecimal lifetimeBudget, BigDecimal spend, BigDecimal utilization,
                             long qualifiedLeads, BigDecimal costPerQualifiedLead, long bookings, BigDecimal costPerBooking,
                             BigDecimal roas, BigDecimal revenueShare, BigDecimal peerCostPerQualifiedLead, String decision,
                             BigDecimal proposedDailyBudget, String reason) {
    }

    public record Result(List<BudgetLine> lines, List<Finding> findings, BigDecimal totalDailyBudget,
                         BigDecimal projectedDailyBudget, List<String> notes) {
    }

    private BudgetEngine() {
    }

    public static Result analyze(List<PerfRow> rows, Map<String, CampaignInfo> campaigns, ClientBudgetPolicy policy,
                                 long days, String from, String to, String currency) {
        int minQ = policy.getMinQualifiedLeads();
        BigDecimal step = BigDecimal.valueOf(policy.getMaxChangePct()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal totalRevenue = rows.stream().map(PerfRow::confirmedRevenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> notes = new ArrayList<>();
        List<BudgetLine> lines = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        BigDecimal totalDaily = BigDecimal.ZERO;
        Map<String, BigDecimal> proposals = new LinkedHashMap<>();

        for (PerfRow row : rows) {
            CampaignInfo info = campaigns.get(row.id());
            BigDecimal daily = info == null ? null : info.dailyBudget();
            if (daily != null && "ACTIVE".equals(info.effectiveStatus())) totalDaily = totalDaily.add(daily);
        }

        for (PerfRow row : rows) {
            CampaignInfo info = campaigns.get(row.id());
            String objective = info == null ? null : info.objective();
            BigDecimal peerCpql = peerCost(rows, campaigns, row, objective, minQ);
            BigDecimal daily = info == null ? null : info.dailyBudget();
            BigDecimal utilization = daily == null || days <= 0 ? null
                    : row.spend().divide(daily.multiply(BigDecimal.valueOf(days)), 4, RoundingMode.HALF_UP);
            BigDecimal share = totalRevenue.signum() == 0 ? null
                    : row.confirmedRevenue().divide(totalRevenue, 4, RoundingMode.HALF_UP);
            String decision;
            String reason;
            BigDecimal proposed = null;
            boolean active = info != null && "ACTIVE".equals(info.effectiveStatus());
            BigDecimal cpql = row.costPerQualifiedLead();
            BigDecimal target = policy.getTargetCostPerQualifiedLead();
            BigDecimal benchmark = target != null ? target : peerCpql;

            if (!active) {
                decision = "not_active";
                reason = "Campaign is not active.";
            } else if (row.qualifiedLeads() == 0 && benchmark != null
                    && row.spend().compareTo(benchmark.multiply(BigDecimal.valueOf(3))) >= 0) {
                decision = "pause_for_review";
                reason = "Spent " + money(row.spend(), currency) + " (at least 3× the benchmark cost per qualified lead of "
                        + money(benchmark, currency) + ") with no qualified leads.";
            } else if (row.qualifiedLeads() < minQ || benchmark == null) {
                decision = "insufficient_data";
                reason = benchmark == null ? "No comparable campaign with " + minQ + "+ qualified leads and no target cost set."
                        : "Only " + row.qualifiedLeads() + " qualified lead(s); at least " + minQ + " are needed to judge efficiency.";
            } else if (cpql.compareTo(benchmark.multiply(new BigDecimal("0.8"))) <= 0 && meetsRoas(row, policy)
                    && utilization != null && utilization.compareTo(new BigDecimal("0.8")) >= 0) {
                decision = "increase";
                proposed = daily == null ? null : daily.multiply(BigDecimal.ONE.add(step)).setScale(0, RoundingMode.HALF_UP);
                reason = "Cost per qualified lead " + money(cpql, currency) + " is at least 20% below the benchmark "
                        + money(benchmark, currency) + " and the budget is " + pct(utilization) + " used.";
            } else if (cpql.compareTo(benchmark.multiply(new BigDecimal("1.5"))) >= 0) {
                decision = "reduce";
                proposed = daily == null ? null : daily.multiply(BigDecimal.ONE.subtract(step)).setScale(0, RoundingMode.HALF_UP);
                reason = "Cost per qualified lead " + money(cpql, currency) + " is at least 50% above the benchmark "
                        + money(benchmark, currency) + ".";
            } else {
                decision = "maintain";
                reason = "Cost per qualified lead is within 20% below to 50% above the benchmark.";
            }
            if (proposed != null) proposals.put(row.id(), proposed.subtract(daily));
            lines.add(new BudgetLine(row.id(), row.name(), objective, info == null ? null : info.effectiveStatus(),
                    info == null ? "unknown" : info.budgetLevel(), daily, info == null ? null : info.lifetimeBudget(),
                    row.spend(), utilization, row.qualifiedLeads(), cpql, row.bookings(), row.costPerBooking(), row.roas(),
                    share, benchmark, decision, proposed, reason));
        }

        // Spending limits: increases may only use the remaining daily/monthly headroom.
        BigDecimal headroom = headroom(policy, totalDaily);
        BigDecimal projected = totalDaily;
        for (int i = 0; i < lines.size(); i++) {
            BudgetLine line = lines.get(i);
            BigDecimal delta = proposals.get(line.campaignId());
            if (delta == null) continue;
            if (delta.signum() > 0 && headroom != null) {
                if (headroom.signum() <= 0) {
                    lines.set(i, withDecision(line, "maintain_limit_reached", null,
                            line.reason() + " An increase would exceed the client's spending limit."));
                    proposals.remove(line.campaignId());
                    continue;
                }
                if (delta.compareTo(headroom) > 0) {
                    delta = headroom;
                    BigDecimal capped = line.dailyBudget().add(delta);
                    lines.set(i, withDecision(line, line.decision(), capped, line.reason() + " Capped by the client's spending limit."));
                    proposals.put(line.campaignId(), delta);
                }
                headroom = headroom.subtract(delta);
            }
            projected = projected.add(proposals.get(line.campaignId()));
        }

        String fromTo = from + "–" + to;
        for (BudgetLine line : lines) {
            Finding f = toFinding(line, fromTo, currency, minQ);
            if (f != null) findings.add(f);
        }
        Finding reallocation = reallocation(lines, currency, fromTo, minQ);
        if (reallocation != null) findings.add(reallocation);
        if (policy.getDailySpendLimit() == null && policy.getMonthlySpendLimit() == null) {
            notes.add("No client spending limit is set; increases are only bounded by the maximum change of "
                    + policy.getMaxChangePct() + "%.");
        }
        notes.add("Budget utilization assumes each campaign ran its current daily budget for the whole period; it is an estimate.");
        return new Result(lines, findings, totalDaily, projected, notes);
    }

    static BigDecimal headroom(ClientBudgetPolicy policy, BigDecimal totalDaily) {
        BigDecimal h = null;
        if (policy.getDailySpendLimit() != null) h = policy.getDailySpendLimit().subtract(totalDaily);
        if (policy.getMonthlySpendLimit() != null) {
            BigDecimal monthlyDaily = policy.getMonthlySpendLimit().divide(BigDecimal.valueOf(30), 2, RoundingMode.DOWN)
                    .subtract(totalDaily);
            h = h == null ? monthlyDaily : h.min(monthlyDaily);
        }
        return h;
    }

    /** Peer benchmark: cost per qualified lead of other campaigns with the same objective and enough volume. */
    static BigDecimal peerCost(List<PerfRow> rows, Map<String, CampaignInfo> campaigns, PerfRow self, String objective, int minQ) {
        BigDecimal spend = BigDecimal.ZERO;
        long qualified = 0;
        for (PerfRow other : rows) {
            if (other.id().equals(self.id()) || other.qualifiedLeads() < minQ) continue;
            CampaignInfo info = campaigns.get(other.id());
            if (!Objects.equals(objective, info == null ? null : info.objective())) continue;
            spend = spend.add(other.spend());
            qualified += other.qualifiedLeads();
        }
        return qualified == 0 ? null : spend.divide(BigDecimal.valueOf(qualified), 2, RoundingMode.HALF_UP);
    }

    private static boolean meetsRoas(PerfRow row, ClientBudgetPolicy policy) {
        return policy.getTargetRoas() == null || (row.roas() != null && row.roas().compareTo(policy.getTargetRoas()) >= 0);
    }

    private static BudgetLine withDecision(BudgetLine l, String decision, BigDecimal proposed, String reason) {
        return new BudgetLine(l.campaignId(), l.name(), l.objective(), l.status(), l.budgetLevel(), l.dailyBudget(),
                l.lifetimeBudget(), l.spend(), l.utilization(), l.qualifiedLeads(), l.costPerQualifiedLead(), l.bookings(),
                l.costPerBooking(), l.roas(), l.revenueShare(), l.peerCostPerQualifiedLead(), decision, proposed, reason);
    }

    static Finding toFinding(BudgetLine l, String period, String currency, int minQ) {
        String type = switch (l.decision()) {
            case "increase" -> "increase_budget";
            case "reduce" -> "reduce_budget";
            case "pause_for_review" -> "pause_for_review";
            default -> null;
        };
        if (type == null) return null;
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence("spend", "Spend", l.spend(), currency, "meta_reported", "campaign"));
        ev.add(new Evidence("qualified_leads", "Qualified leads", BigDecimal.valueOf(l.qualifiedLeads()), "count", "crm_verified", "campaign"));
        if (l.costPerQualifiedLead() != null) ev.add(new Evidence("cost_per_qualified_lead", "Cost per qualified lead", l.costPerQualifiedLead(), currency, "derived", "campaign"));
        if (l.peerCostPerQualifiedLead() != null) ev.add(new Evidence("benchmark_cost_per_qualified_lead", "Benchmark cost per qualified lead", l.peerCostPerQualifiedLead(), currency, "derived", "same-objective peers or target"));
        if (l.dailyBudget() != null) ev.add(new Evidence("daily_budget", "Current daily budget", l.dailyBudget(), currency, "meta_reported", "campaign"));
        if (l.utilization() != null) ev.add(new Evidence("budget_utilization", "Budget utilization", l.utilization(), "ratio", "estimated", "campaign"));
        Map<String, Object> change = null;
        String impact;
        String action;
        String risk;
        if (l.proposedDailyBudget() != null) {
            change = new LinkedHashMap<>();
            change.put("field", "daily_budget");
            change.put("from", l.dailyBudget());
            change.put("to", l.proposedDailyBudget());
            change.put("currency", currency);
            change.put("applied_automatically", false);
            ev.add(new Evidence("proposed_daily_budget", "Proposed daily budget", l.proposedDailyBudget(), currency, "estimated", "campaign"));
        }
        BigDecimal delta = l.proposedDailyBudget() == null ? null : l.proposedDailyBudget().subtract(l.dailyBudget());
        switch (type) {
            case "increase_budget" -> {
                BigDecimal extra = delta == null || l.costPerQualifiedLead() == null ? null
                        : delta.multiply(BigDecimal.valueOf(7)).divide(l.costPerQualifiedLead(), 1, RoundingMode.HALF_UP);
                impact = extra == null ? "Estimate unavailable (no daily budget on the campaign)."
                        : "Estimate: about " + extra.toPlainString() + " more qualified leads per week if cost per qualified lead stayed at "
                        + money(l.costPerQualifiedLead(), currency) + ". Costs usually rise as budget grows, so treat this as an upper bound.";
                action = delta == null ? "Consider a controlled budget increase in Ads Manager after review."
                        : "After review, raise the daily budget from " + money(l.dailyBudget(), currency) + " to "
                        + money(l.proposedDailyBudget(), currency) + " in Ads Manager, then re-check cost per qualified lead after 7 days.";
                risk = "medium";
            }
            case "reduce_budget" -> {
                impact = delta == null ? "Estimate unavailable (no daily budget on the campaign)."
                        : "Estimate: saves about " + money(delta.negate().multiply(BigDecimal.valueOf(7)), currency)
                        + " per week; qualified lead volume from this campaign may also fall.";
                action = delta == null ? "Consider reducing spend after review."
                        : "After review, lower the daily budget from " + money(l.dailyBudget(), currency) + " to "
                        + money(l.proposedDailyBudget(), currency) + ", or fix targeting/creative first.";
                risk = "low";
            }
            default -> {
                impact = "Estimate: stops further spend without qualified leads while the cause is investigated.";
                action = "Review targeting, creative and lead handling for this campaign; pause it in Ads Manager if nothing explains the gap.";
                risk = "medium";
            }
        }
        String title = switch (type) {
            case "increase_budget" -> "Consider increasing budget for " + l.name();
            case "reduce_budget" -> "Consider reducing budget for " + l.name();
            default -> "Review " + l.name() + " before spending more";
        };
        String current = "Spend " + money(l.spend(), currency) + ", " + l.qualifiedLeads() + " qualified lead(s)"
                + (l.costPerQualifiedLead() == null ? "" : ", cost per qualified lead " + money(l.costPerQualifiedLead(), currency))
                + " in " + period + ".";
        return new Finding("budget", type, l.campaignId(), null, null, l.name(), title, current, l.reason(), impact,
                Finding.confidence(l.qualifiedLeads(), minQ), risk, action, change, ev,
                "budget:" + type + ":" + l.campaignId());
    }

    /** Suggests moving budget from a reduce candidate to an increase candidate with the same objective. */
    static Finding reallocation(List<BudgetLine> lines, String currency, String period, int minQ) {
        for (BudgetLine up : lines) {
            if (!"increase".equals(up.decision()) || up.proposedDailyBudget() == null) continue;
            for (BudgetLine down : lines) {
                if (!"reduce".equals(down.decision()) || down.proposedDailyBudget() == null
                        || !Objects.equals(up.objective(), down.objective())) continue;
                BigDecimal amount = up.proposedDailyBudget().subtract(up.dailyBudget())
                        .min(down.dailyBudget().subtract(down.proposedDailyBudget()));
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("move_daily_amount", amount);
                change.put("from_campaign_id", down.campaignId());
                change.put("to_campaign_id", up.campaignId());
                change.put("currency", currency);
                change.put("applied_automatically", false);
                List<Evidence> ev = List.of(
                        new Evidence("from_cost_per_qualified_lead", "Cost per qualified lead — " + down.name(), down.costPerQualifiedLead(), currency, "derived", "campaign"),
                        new Evidence("to_cost_per_qualified_lead", "Cost per qualified lead — " + up.name(), up.costPerQualifiedLead(), currency, "derived", "campaign"),
                        new Evidence("move_daily_amount", "Daily amount to move", amount, currency, "estimated", "campaign pair"));
                return new Finding("budget", "reallocate_budget", up.campaignId(), null, null, down.name() + " → " + up.name(),
                        "Consider moving " + money(amount, currency) + "/day from " + down.name() + " to " + up.name(),
                        down.name() + " costs " + money(down.costPerQualifiedLead(), currency) + " per qualified lead vs "
                                + money(up.costPerQualifiedLead(), currency) + " for " + up.name() + " (same objective, " + period + ").",
                        "Both campaigns share the objective " + up.objective() + " and were compared over the same window.",
                        "Estimate: total spend unchanged; qualified lead volume may improve if the cost gap persists.",
                        Finding.confidence(Math.min(up.qualifiedLeads(), down.qualifiedLeads()), minQ), "medium",
                        "After review, shift the amount in Ads Manager and compare both campaigns again after 7 days.",
                        change, ev, "budget:reallocate:" + down.campaignId() + ":" + up.campaignId());
            }
        }
        return null;
    }

    static String money(BigDecimal v, String currency) {
        return v == null ? "—" : currency + " " + v.setScale(v.scale() > 0 && v.stripTrailingZeros().scale() > 0 ? 2 : 0, RoundingMode.HALF_UP).toPlainString();
    }

    static String pct(BigDecimal ratio) {
        return ratio == null ? "—" : ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
    }
}
