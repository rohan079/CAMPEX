package com.aivoice.lead.service.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptimizationRulesTest {

    @Test
    void flagsPoorLeadQualityAgainstSameObjectivePeers() {
        PerfRow poor = Fixtures.campaign("P", "OUTCOME_LEADS", "1000", 20000, 300, 20, 20, 2, 0, 0, "0");
        PerfRow peer = Fixtures.campaign("Q", "OUTCOME_LEADS", "1000", 20000, 300, 20, 20, 12, 0, 0, "0");
        List<Finding> f = OptimizationRules.campaignFindings(List.of(poor, peer), 10, 50, "INR", "p");
        Finding quality = f.stream().filter(x -> x.type().equals("improve_lead_quality")).findFirst().orElseThrow();
        assertEquals("P", quality.campaignId());
        assertEquals("medium", quality.confidence());
        assertTrue(quality.evidence().stream().anyMatch(e -> e.source().equals("crm_verified")));
    }

    @Test
    void flagsLandingPageAndCaptureGapsButNotSmallSamples() {
        PerfRow lp = Fixtures.campaign("L", "OUTCOME_LEADS", "1000", 50000, 400, 4, 4, 2, 0, 0, "0");
        PerfRow peer = Fixtures.campaign("M", "OUTCOME_LEADS", "1000", 50000, 400, 40, 40, 20, 0, 0, "0");
        PerfRow gap = Fixtures.campaign("G", "OUTCOME_TRAFFIC", "1000", 50000, 400, 30, 5, 2, 0, 0, "0");
        List<Finding> f = OptimizationRules.campaignFindings(List.of(lp, peer, gap), 10, 50, "INR", "p");
        assertTrue(f.stream().anyMatch(x -> x.type().equals("improve_landing_page") && x.campaignId().equals("L")));
        assertTrue(f.stream().anyMatch(x -> x.type().equals("check_lead_capture") && x.campaignId().equals("G")));
        PerfRow tiny = Fixtures.campaign("T", "OUTCOME_LEADS", "50", 900, 20, 1, 1, 0, 0, 0, "0");
        assertTrue(OptimizationRules.campaignFindings(List.of(tiny, peer), 10, 50, "INR", "p").isEmpty());
    }

    @Test
    void adRulesNeedThreeComparableAds() {
        PerfRow low = Fixtures.ad("a1", "c", 10000, 20, 2, 1);
        PerfRow mid = Fixtures.ad("a2", "c", 10000, 100, 10, 6);
        PerfRow clicky = Fixtures.ad("a3", "c", 10000, 200, 20, 2);
        List<Finding> f = OptimizationRules.adFindings(List.of(low, mid, clicky), 1000, "p");
        assertTrue(f.stream().anyMatch(x -> x.type().equals("test_new_creative") && x.adId().equals("a1")));
        assertTrue(f.stream().anyMatch(x -> x.type().equals("clarify_messaging") && x.adId().equals("a3")));
        assertTrue(OptimizationRules.adFindings(List.of(low, mid), 1000, "p").isEmpty());
    }
}
