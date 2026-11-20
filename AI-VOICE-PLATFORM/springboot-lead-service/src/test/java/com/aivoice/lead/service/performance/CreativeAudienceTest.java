package com.aivoice.lead.service.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.dto.IntelligenceDtos.CreativeRow;
import com.aivoice.lead.dto.IntelligenceDtos.CrmSegment;
import com.aivoice.lead.dto.IntelligenceDtos.Fatigue;
import com.aivoice.lead.dto.IntelligenceDtos.GroupRow;
import com.aivoice.lead.dto.IntelligenceDtos.Insight;
import com.aivoice.lead.dto.IntelligenceDtos.SegmentRow;
import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.entity.PerformanceSnapshot;
import com.aivoice.lead.service.performance.MetaPerformanceClient.InsightRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CreativeAudienceTest {

    private static PerformanceSnapshot adDay(String ad, LocalDate day, long imp, long clicks, String freq) {
        PerformanceSnapshot s = new PerformanceSnapshot();
        s.setLevel("ad");
        s.setObjectId(ad);
        s.setDay(day);
        s.setSpend(BigDecimal.TEN);
        s.setImpressions(imp);
        s.setLinkClicks(clicks);
        s.setFrequency(new BigDecimal(freq));
        return s;
    }

    @Test
    void fatigueNeedsFallingCtrAndHighFrequency() {
        LocalDate end = LocalDate.of(2026, 11, 19);
        List<PerformanceSnapshot> snaps = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            boolean recent = i < 7;
            snaps.add(adDay("tired", end.minusDays(i), 1000, recent ? 10 : 20, recent ? "3.2" : "2.0"));
            snaps.add(adDay("fine", end.minusDays(i), 1000, 20, "1.2"));
            snaps.add(adDay("small", end.minusDays(i), 50, 1, "1.0"));
        }
        Map<String, Fatigue> f = CreativeIntelligenceService.fatigue(snaps);
        assertEquals("fatigued", f.get("tired").status());
        assertEquals("ok", f.get("fine").status());
        assertEquals("insufficient_data", f.get("small").status());
    }

    @Test
    void rankingUsesTheStrongestMetricWithEnoughData() {
        PerfRow a = Fixtures.ad("a", "c", 5000, 100, 10, 5);
        PerfRow b = Fixtures.ad("b", "c", 5000, 50, 8, 3);
        assertEquals("cost_per_qualified_lead", CreativeIntelligenceService.rankingMetric(List.of(a, b)));
        assertEquals("ctr", CreativeIntelligenceService.rankingMetric(List.of(Fixtures.ad("x", "c", 5000, 10, 1, 0))));
        List<CreativeRow> rows = CreativeIntelligenceService.rank(List.of(row(a, "Villas"), row(b, "Plots")), "cost_per_qualified_lead");
        assertEquals("best", rows.get(0).rank());
        assertEquals("worst", rows.get(1).rank());
        List<GroupRow> byHeadline = CreativeIntelligenceService.group(List.of(row(a, "Same"), row(b, "Same")), "headline", CreativeRow::headline);
        assertEquals(1, byHeadline.size());
        assertEquals(8, byHeadline.get(0).metrics().qualifiedLeads());
        assertEquals(2, byHeadline.get(0).ads());
    }

    private static CreativeRow row(PerfRow r, String headline) {
        return new CreativeRow(r.id(), r.name(), "c", null, "ACTIVE", "cr-" + r.id(), headline, "text", "LEARN_MORE",
                null, "photo", r, new Fatigue("ok", "", null, null, null, null), null);
    }

    private static InsightRow segment(String age, String spend, long imp, long leads) {
        return new InsightRow(null, null, null, null, null, null, null, null, new BigDecimal(spend), imp, null, null, 0,
                imp / 100, leads, "INR", Map.of("age", age));
    }

    @Test
    void segmentFlagsAndInsightsComeFromMetaOnly() {
        List<SegmentRow> rows = AudienceIntelligenceService.segments(List.of(
                segment("25-34", "1000", 50000, 20), segment("35-44", "1000", 50000, 5),
                segment("45-54", "400", 20000, 1)), "age");
        SegmentRow cheap = rows.stream().filter(r -> r.label().equals("25-34")).findFirst().orElseThrow();
        SegmentRow costly = rows.stream().filter(r -> r.label().equals("35-44")).findFirst().orElseThrow();
        assertTrue(cheap.flags().contains("high_converting"));
        assertTrue(costly.flags().contains("high_cost"));
        assertTrue(rows.stream().filter(r -> r.label().equals("45-54")).findFirst().orElseThrow()
                .flags().contains("spend_without_enough_leads"));
        List<Insight> placement = AudienceIntelligenceService.metaInsights(rows, AudienceIntelligenceService.PLACEMENT_BREAKDOWN);
        assertTrue(placement.stream().anyMatch(i -> i.type().equals("inexpensive_placement")
                && i.message().contains("quality per placement is not measured")));
    }

    @Test
    void crmInsightsNeedEnoughLeads() {
        List<CrmSegment> segs = List.of(
                new CrmSegment("preferred_location", "guntur", 20, 10, 8, 2, null, new BigDecimal("0.4"), null),
                new CrmSegment("preferred_location", "tenali", 3, 2, 3, 1, null, new BigDecimal("1.0"), null));
        List<Insight> insights = AudienceIntelligenceService.crmInsights(segs, Map.of());
        assertEquals(1, insights.size());
        assertTrue(insights.get(0).message().contains("guntur"));
    }
}
