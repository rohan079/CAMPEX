package com.aivoice.lead.service.automation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus;
import com.aivoice.lead.service.automation.RuleEvaluator.Day;
import com.aivoice.lead.service.automation.RuleEvaluator.Finding;
import com.aivoice.lead.service.automation.RuleEvaluator.Outcome;
import com.aivoice.lead.service.automation.RuleEvaluator.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEvaluatorTest {

    static final LocalDate END = LocalDate.of(2026, 11, 19);

    /** 14 days of data: the first 7 with ctrBefore, the last 7 with ctrAfter (clicks per 10k impressions). */
    static List<Day> days(String id, long impressions, long clicksBefore, long clicksAfter, BigDecimal spend, long leads) {
        List<Day> out = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            LocalDate d = END.minusDays(i);
            out.add(new Day(id, "Villas", d, spend, impressions, i >= 7 ? clicksBefore : clicksAfter, leads));
        }
        return out;
    }

    private static Finding one(List<Finding> f) {
        assertThat(f).hasSize(1);
        return f.getFirst();
    }

    @Test
    void ctrDeclineNeedsEnoughImpressionsInBothWindows() {
        Rule rule = new Rule("ctr_decline", null, BigDecimal.valueOf(30), 7, 10_000);
        Finding hit = one(RuleEvaluator.evaluate(rule, days("1", 2000, 30, 15, BigDecimal.TEN, 1), List.of(), END, END));
        assertThat(hit.outcome()).isEqualTo(Outcome.TRIGGERED);
        assertThat(hit.observed().get("decline_pct")).isEqualTo(new BigDecimal("50.0"));
        Finding thin = one(RuleEvaluator.evaluate(rule, days("1", 500, 30, 15, BigDecimal.TEN, 1), List.of(), END, END));
        assertThat(thin.outcome()).isEqualTo(Outcome.INSUFFICIENT_DATA);
        Finding mild = one(RuleEvaluator.evaluate(rule, days("1", 2000, 30, 25, BigDecimal.TEN, 1), List.of(), END, END));
        assertThat(mild.outcome()).isEqualTo(Outcome.NOT_TRIGGERED);
    }

    @Test
    void costPerLeadWaitsForEnoughLeads() {
        Rule rule = new Rule("cpl_above_threshold", null, BigDecimal.valueOf(500), 7, 5);
        // 7 days × ₹1000 spend, 1 lead/day → CPL 1000
        assertThat(one(RuleEvaluator.evaluate(rule, days("1", 1000, 10, 10, BigDecimal.valueOf(1000), 1), List.of(), END, END)).outcome())
                .isEqualTo(Outcome.TRIGGERED);
        Rule strict = new Rule("cpl_above_threshold", null, BigDecimal.valueOf(500), 7, 20);
        assertThat(one(RuleEvaluator.evaluate(strict, days("1", 1000, 10, 10, BigDecimal.valueOf(1000), 1), List.of(), END, END)).outcome())
                .isEqualTo(Outcome.INSUFFICIENT_DATA);
        Rule loose = new Rule("cpl_above_threshold", null, BigDecimal.valueOf(1500), 7, 5);
        assertThat(one(RuleEvaluator.evaluate(loose, days("1", 1000, 10, 10, BigDecimal.valueOf(1000), 1), List.of(), END, END)).outcome())
                .isEqualTo(Outcome.NOT_TRIGGERED);
    }

    @Test
    void spendLimitSumsTheLatestDayAcrossCampaigns() {
        List<Day> data = new ArrayList<>(days("1", 100, 1, 1, BigDecimal.valueOf(600), 0));
        data.addAll(days("2", 100, 1, 1, BigDecimal.valueOf(500), 0));
        Finding f = one(RuleEvaluator.evaluate(new Rule("spend_over_limit", null, BigDecimal.valueOf(1000), 1, 0), data, List.of(), END, END));
        assertThat(f.outcome()).isEqualTo(Outcome.TRIGGERED);
        assertThat(f.observed().get("spend")).isEqualTo(BigDecimal.valueOf(1100));
    }

    @Test
    void scopedRulesOnlySeeTheirCampaignAndDeliveryIssuesAreDetected() {
        List<Day> data = new ArrayList<>(days("1", 2000, 30, 15, BigDecimal.TEN, 1));
        data.addAll(days("2", 2000, 30, 30, BigDecimal.TEN, 1));
        List<Finding> scoped = RuleEvaluator.evaluate(new Rule("ctr_decline", "2", BigDecimal.valueOf(30), 7, 1000), data, List.of(), END, END);
        assertThat(one(scoped).objectId()).isEqualTo("2");
        List<Finding> delivery = RuleEvaluator.evaluate(new Rule("delivery_issue", null, null, 1, 0), List.of(),
                List.of(new CampaignStatus("1", "A", "ACTIVE", "WITH_ISSUES"), new CampaignStatus("2", "B", "ACTIVE", "ACTIVE")), END, END);
        assertThat(delivery).extracting(Finding::outcome).containsExactly(Outcome.TRIGGERED, Outcome.NOT_TRIGGERED);
    }
}
