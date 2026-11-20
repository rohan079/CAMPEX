package com.aivoice.lead.service.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.entity.PerformanceSnapshot;
import com.aivoice.lead.service.performance.AnomalyDetector.Detected;
import com.aivoice.lead.service.performance.CrmOutcomeRepository.DailyCrm;
import com.aivoice.lead.service.performance.ExperimentStats.Arm;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class AnomalyAndExperimentTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 19);

    private static PerformanceSnapshot day(LocalDate d, String spend, long impressions, long clicks, long leads) {
        PerformanceSnapshot s = new PerformanceSnapshot();
        s.setLevel("campaign");
        s.setObjectId("c1");
        s.setName("Villas");
        s.setDay(d);
        s.setSpend(new BigDecimal(spend));
        s.setImpressions(impressions);
        s.setLinkClicks(clicks);
        s.setMetaLeads(leads);
        s.setFrequency(new BigDecimal("1.5"));
        return s;
    }

    /** 14 baseline days with small natural variation, then 3 current days from {@code current}. */
    private static List<PerformanceSnapshot> series(IntFunction<PerformanceSnapshot> current) {
        List<PerformanceSnapshot> out = new ArrayList<>();
        for (int i = 16; i >= 3; i--) {
            long wobble = i % 3;
            out.add(day(AS_OF.minusDays(i), "1000", 10000 + wobble * 200, 200 + wobble * 5, 10 + wobble));
        }
        for (int i = 2; i >= 0; i--) out.add(current.apply(i));
        return out;
    }

    @Test
    void criticalCtrDropIsDetectedWithReasonAndAction() {
        List<Detected> found = AnomalyDetector.detect(series(i -> day(AS_OF.minusDays(i), "1000", 10000, 60, 3)),
                List.of(), AS_OF, 1000, 50, 10);
        Detected ctr = found.stream().filter(d -> d.metric().equals("ctr")).findFirst().orElseThrow();
        assertEquals("CRITICAL", ctr.severity());
        assertTrue(ctr.pctChange().doubleValue() < -0.5);
        assertTrue(ctr.reason().contains("fell"));
        assertFalse(ctr.action().isBlank());
        assertEquals(AS_OF.minusDays(2), ctr.currentFrom());
        assertTrue(found.stream().anyMatch(d -> d.metric().equals("meta_leads")));
    }

    @Test
    void stableCampaignsProduceNoAnomalies() {
        assertTrue(AnomalyDetector.detect(series(i -> day(AS_OF.minusDays(i), "1000", 10200, 205, 11)),
                List.of(), AS_OF, 1000, 50, 10).isEmpty());
    }

    @Test
    void minimumSamplesSuppressNoise() {
        List<PerformanceSnapshot> tiny = new ArrayList<>();
        for (int i = 16; i >= 3; i--) tiny.add(day(AS_OF.minusDays(i), "10", 50, 2, 0));
        for (int i = 2; i >= 0; i--) tiny.add(day(AS_OF.minusDays(i), "10", 50, 0, 0));
        assertTrue(AnomalyDetector.detect(tiny, List.of(), AS_OF, 1000, 50, 10).stream()
                .noneMatch(d -> d.metric().equals("ctr") || d.metric().equals("cpc")));
    }

    @Test
    void spendSpikeAndLostQualifiedLeadsAreFlagged() {
        List<DailyCrm> crm = new ArrayList<>();
        for (int i = 16; i >= 3; i--) crm.add(new DailyCrm(AS_OF.minusDays(i), "c1", 2, 1));
        List<Detected> found = AnomalyDetector.detect(series(i -> day(AS_OF.minusDays(i), "3000", 10000, 200, 10)),
                crm, AS_OF, 1000, 50, 10);
        assertTrue(found.stream().anyMatch(d -> d.metric().equals("spend") && !d.severity().equals("INFO")));
        assertTrue(found.stream().anyMatch(d -> d.metric().equals("qualified_leads") && d.severity().equals("CRITICAL")));
    }

    @Test
    void missingRecentMetaDataIsAWarning() {
        List<PerformanceSnapshot> old = new ArrayList<>();
        for (int i = 16; i >= 3; i--) old.add(day(AS_OF.minusDays(i), "1000", 10000, 200, 10));
        assertEquals("meta_data_availability", AnomalyService.missingData(old, AS_OF).orElseThrow().metric());
        old.add(day(AS_OF, "1000", 10000, 200, 10));
        assertTrue(AnomalyService.missingData(old, AS_OF).isEmpty());
    }

    // ---------------------------------------------------------------- experiments

    private static Arm arm(String spend, long impressions, long clicks, long leads, long qualified) {
        return new Arm(new BigDecimal(spend), impressions, clicks, leads, qualified, 0, 0);
    }

    @Test
    void normalCdfMatchesKnownValues() {
        assertEquals(0.5, ExperimentStats.normalCdf(0), 1e-7);
        assertEquals(0.975, ExperimentStats.normalCdf(1.959964), 1e-5);
        assertEquals(0.02275, ExperimentStats.normalCdf(-2), 1e-5);
    }

    @Test
    void ctrWinnerNeedsSignificanceAndSample() {
        // 2.0% vs 2.6% CTR on 20,000 impressions each: z ≈ 4.0, p < 0.001.
        ExperimentStats.Result win = ExperimentStats.evaluate("ctr", arm("1000", 20000, 400, 10, 2),
                arm("1000", 20000, 520, 12, 3), 1000, 0.95, true);
        assertEquals("winner", win.status());
        assertEquals("test", win.winner());
        assertTrue(win.pValue() < 0.001);
        assertEquals(3.99, win.zScore(), 0.05);

        ExperimentStats.Result close = ExperimentStats.evaluate("ctr", arm("1000", 20000, 400, 10, 2),
                arm("1000", 20000, 410, 12, 3), 1000, 0.95, true);
        assertEquals("no_reliable_winner", close.status());
        assertNull(close.winner());
    }

    @Test
    void fewConversionsAreInconclusiveNotAWin() {
        ExperimentStats.Result r = ExperimentStats.evaluate("cost_per_qualified_lead", arm("1000", 5000, 100, 10, 3),
                arm("1000", 5000, 100, 10, 9), 20, 0.95, true);
        assertEquals("inconclusive", r.status());
        assertFalse(r.sampleSufficient());
        assertEquals("insufficient_data", ExperimentStats.evaluate("cost_per_qualified_lead",
                arm("1000", 5000, 100, 10, 3), arm("1000", 5000, 100, 10, 9), 20, 0.95, false).status());
    }

    @Test
    void costMetricsCompareConversionsPerSpend() {
        // Same spend, 30 vs 60 leads: test converts spend twice as efficiently (lower cost per lead).
        ExperimentStats.Result r = ExperimentStats.evaluate("cost_per_lead", arm("3000", 50000, 900, 30, 10),
                arm("3000", 50000, 900, 60, 20), 25, 0.95, true);
        assertEquals("winner", r.status());
        assertEquals("test", r.winner());
        assertEquals(100.0, r.controlValue(), 1e-9);
        assertEquals(50.0, r.testValue(), 1e-9);
        // Unequal spend is accounted for: twice the spend for twice the leads is no difference.
        ExperimentStats.Result fair = ExperimentStats.evaluate("cost_per_lead", arm("3000", 50000, 900, 30, 10),
                arm("6000", 50000, 900, 60, 20), 25, 0.95, true);
        assertEquals("no_reliable_winner", fair.status());
    }
}
