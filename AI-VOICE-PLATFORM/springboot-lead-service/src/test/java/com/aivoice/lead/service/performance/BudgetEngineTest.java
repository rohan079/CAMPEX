package com.aivoice.lead.service.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.entity.ClientBudgetPolicy;
import com.aivoice.lead.service.performance.BudgetEngine.BudgetLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetEngineTest {

    // The specification's example: same objective, same 1-day window.
    private final PerfRow campaignA = Fixtures.campaign("A", "OUTCOME_LEADS", "900", 20000, 300, 10, 10, 5, 1, 0, "0");
    private final PerfRow campaignB = Fixtures.campaign("B", "OUTCOME_LEADS", "950", 20000, 320, 12, 12, 2, 0, 0, "0");

    private BudgetEngine.Result run(List<PerfRow> rows, Map<String, com.aivoice.lead.service.performance.MetaPerformanceClient.CampaignInfo> info,
                                    ClientBudgetPolicy policy) {
        return BudgetEngine.analyze(rows, info, policy, 1, "2026-11-01", "2026-11-01", "INR");
    }

    private static BudgetLine line(BudgetEngine.Result r, String id) {
        return r.lines().stream().filter(l -> l.campaignId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void recognisesTheMoreEfficientCampaignFromTheSpecExample() {
        BudgetEngine.Result r = run(List.of(campaignA, campaignB),
                Map.of("A", Fixtures.info("A", "OUTCOME_LEADS", "1000"), "B", Fixtures.info("B", "OUTCOME_LEADS", "1000")),
                Fixtures.policy(2));
        assertEquals(new BigDecimal("180.00"), line(r, "A").costPerQualifiedLead());
        assertEquals(new BigDecimal("475.00"), line(r, "B").costPerQualifiedLead());
        assertEquals("increase", line(r, "A").decision());
        assertEquals(new BigDecimal("1200"), line(r, "A").proposedDailyBudget());
        assertEquals("reduce", line(r, "B").decision());
        assertEquals(new BigDecimal("800"), line(r, "B").proposedDailyBudget());
        assertTrue(r.findings().stream().anyMatch(f -> f.type().equals("reallocate_budget")));
        Finding inc = r.findings().stream().filter(f -> f.type().equals("increase_budget")).findFirst().orElseThrow();
        assertTrue(inc.expectedImpact().startsWith("Estimate"));
        assertEquals(Boolean.FALSE, inc.proposedChange().get("applied_automatically"));
        assertFalse(inc.evidence().isEmpty());
    }

    @Test
    void campaignsWithDifferentObjectivesAreNotCompared() {
        BudgetEngine.Result r = run(List.of(campaignA, campaignB),
                Map.of("A", Fixtures.info("A", "OUTCOME_LEADS", "1000"), "B", Fixtures.info("B", "OUTCOME_TRAFFIC", "1000")),
                Fixtures.policy(2));
        assertEquals("insufficient_data", line(r, "A").decision());
        assertNull(line(r, "A").peerCostPerQualifiedLead());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void smallSamplesNeverProduceBudgetChanges() {
        BudgetEngine.Result r = run(List.of(campaignA, campaignB),
                Map.of("A", Fixtures.info("A", "OUTCOME_LEADS", "1000"), "B", Fixtures.info("B", "OUTCOME_LEADS", "1000")),
                Fixtures.policy(10));
        assertTrue(r.findings().isEmpty());
        assertEquals("insufficient_data", line(r, "A").decision());
    }

    @Test
    void increasesAreCappedBySpendingLimits() {
        ClientBudgetPolicy policy = Fixtures.policy(2);
        policy.setDailySpendLimit(new BigDecimal("2100"));
        BudgetEngine.Result r = run(List.of(campaignA, campaignB),
                Map.of("A", Fixtures.info("A", "OUTCOME_LEADS", "1000"), "B", Fixtures.info("B", "OUTCOME_LEADS", "1000")),
                policy);
        assertEquals(new BigDecimal("1100"), line(r, "A").proposedDailyBudget());
        assertTrue(line(r, "A").reason().contains("Capped"));
        policy.setDailySpendLimit(new BigDecimal("2000"));
        BudgetEngine.Result full = run(List.of(campaignA, campaignB),
                Map.of("A", Fixtures.info("A", "OUTCOME_LEADS", "1000"), "B", Fixtures.info("B", "OUTCOME_LEADS", "1000")),
                policy);
        assertEquals("maintain_limit_reached", line(full, "A").decision());
        assertTrue(full.findings().stream().noneMatch(f -> f.type().equals("increase_budget")));
    }

    @Test
    void spendWithoutQualifiedLeadsAsksForHumanReview() {
        PerfRow waste = Fixtures.campaign("C", "OUTCOME_LEADS", "2000", 30000, 400, 6, 6, 0, 0, 0, "0");
        PerfRow peer = Fixtures.campaign("D", "OUTCOME_LEADS", "1000", 20000, 300, 10, 10, 5, 0, 0, "0");
        BudgetEngine.Result r = run(List.of(waste, peer),
                Map.of("C", Fixtures.info("C", "OUTCOME_LEADS", "2000"), "D", Fixtures.info("D", "OUTCOME_LEADS", "1000")),
                Fixtures.policy(2));
        assertEquals("pause_for_review", line(r, "C").decision());
        assertNull(line(r, "C").proposedDailyBudget());
    }

    @Test
    void targetRoasBlocksIncreasesForUnprofitableCampaigns() {
        ClientBudgetPolicy policy = Fixtures.policy(2);
        policy.setTargetRoas(new BigDecimal("2"));
        BudgetEngine.Result r = run(List.of(campaignA, campaignB),
                Map.of("A", Fixtures.info("A", "OUTCOME_LEADS", "1000"), "B", Fixtures.info("B", "OUTCOME_LEADS", "1000")),
                policy);
        assertEquals("maintain", line(r, "A").decision());
    }
}
