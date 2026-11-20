package com.aivoice.lead.service.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.config.PerformanceProperties;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.dto.PerformanceDtos.PerformanceReport;
import com.aivoice.lead.repository.PerformanceSnapshotRepository;
import com.aivoice.lead.service.ClientAccessService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetricsAndAssemblyTest {

    private final PerformanceService service = new PerformanceService(mock(MetaAccessResolver.class),
            mock(MetaPerformanceClient.class), mock(CrmOutcomeRepository.class), mock(PerformanceSnapshotRepository.class),
            mock(ClientAccessService.class), PerformanceProperties.defaults(),
            new AppProperties("development", "A", "", "Asia/Kolkata", 7, 3, "", "", "", null, "http://v", 5, 1),
            Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC), "INR");

    @Test
    void ratiosComeFromTotalsAndRoasIsNotASumOfRoas() {
        MetricSet a = new MetricSet();
        a.addMeta(Fixtures.insight("1", null, "1000", 10000, 8000L, 100, 10));
        a.addCrm(new CrmOutcome(10, 5, 2, 1, 1, new BigDecimal("50000"), new BigDecimal("5000")));
        MetricSet b = new MetricSet();
        b.addMeta(Fixtures.insight("2", null, "3000", 30000, 20000L, 150, 12));
        b.addCrm(new CrmOutcome(12, 2, 1, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals(new BigDecimal("5.0000"), a.roas());
        assertEquals(new BigDecimal("0.0000"), b.roas());
        MetricSet total = new MetricSet();
        total.addAll(a);
        total.addAll(b);
        // 5000 / 4000 = 1.25, not 5 + 0
        assertEquals(new BigDecimal("1.2500"), total.roas());
        assertEquals(new BigDecimal("571.43"), total.costPerQualifiedLead());
        assertEquals(new BigDecimal("0.006250"), total.ctr());
        assertNull(total.reach, "reach is not additive");
        assertEquals(8000L, a.reach);
    }

    @Test
    void divisionByZeroYieldsNullNotZero() {
        MetricSet m = new MetricSet();
        assertNull(m.ctr());
        assertNull(m.costPerQualifiedLead());
        assertNull(m.roas());
    }

    @Test
    void assemblyJoinsMetaWithCrmAndKeepsUnmatchedCrmOutOfTotals() {
        PerformanceService.MetaData data = new PerformanceService.MetaData(true, List.of(),
                List.of(Fixtures.insight("c1", null, "1000", 10000, 9000L, 100, 10),
                        Fixtures.insight("c2", null, "500", 500, 400L, 5, 0)),
                Map.of("c1", Fixtures.info("c1", "OUTCOME_LEADS", "100")), Map.of("act_1", 12000L),
                Map.of("act_1", new BigDecimal("1.4")), "INR", List.of("act_1"));
        Map<String, CrmOutcome> crm = Map.of(
                "c1", new CrmOutcome(8, 4, 2, 1, 1, new BigDecimal("40000"), new BigDecimal("2000")),
                "unknown", new CrmOutcome(6, 1, 0, 0, 1, new BigDecimal("1000"), new BigDecimal("500")));
        PerformanceReport r = service.assemble(UUID.randomUUID(), "campaign", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 7), null, data, crm);
        assertEquals(2, r.rows().size());
        assertEquals("c1", r.rows().get(0).id());
        assertEquals(new BigDecimal("250.00"), r.rows().get(0).costPerQualifiedLead());
        assertEquals("OUTCOME_LEADS", r.rows().get(0).objective());
        assertTrue(r.rows().get(1).flags().contains("low_sample"));
        // Totals: only CRM outcomes matched to a Meta campaign; revenue 2000 / spend 1500.
        assertEquals(new BigDecimal("1500"), r.totals().spend());
        assertEquals(new BigDecimal("2000"), r.totals().confirmedRevenue());
        assertEquals(new BigDecimal("1.3333"), r.totals().roas());
        assertEquals(12000L, r.totals().reach());
        assertEquals(6, r.unattributedCrm().crmLeads());
        assertTrue(r.dataNotes().stream().map(DataNote::code).anyMatch("unattributed_crm"::equals));
        assertEquals("meta_reported", r.metricSources().get("spend"));
        assertEquals("crm_verified", r.metricSources().get("qualified_leads"));
        assertEquals("manual_financial", r.metricSources().get("confirmed_revenue"));
    }

    @Test
    void withoutMetaDataOnlyNotesAreReturnedAndNothingIsInvented() {
        PerformanceService.MetaData none = new PerformanceService.MetaData(false,
                List.of(new DataNote("meta_unavailable", "warning", "No ad accounts")), List.of(), Map.of(), Map.of(),
                Map.of(), null, List.of());
        PerformanceReport r = service.assemble(UUID.randomUUID(), "campaign", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 7), null, none, Map.of("c1", new CrmOutcome(3, 1, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertFalse(r.metaAvailable());
        assertTrue(r.rows().isEmpty());
        assertEquals(BigDecimal.ZERO, r.totals().spend());
        assertNull(r.totals().roas());
    }

    @Test
    void percentageChangeIsNullWithoutABaseline() {
        Map<String, BigDecimal> c = PerformanceService.changes(
                Fixtures.campaign("x", null, "200", 1000, 10, 2, 2, 1, 0, 0, "0"),
                Fixtures.campaign("x", null, "0", 0, 0, 0, 0, 0, 0, 0, "0"));
        assertNull(c.get("spend"));
        Map<String, BigDecimal> d = PerformanceService.changes(
                Fixtures.campaign("x", null, "200", 1000, 10, 2, 2, 1, 0, 0, "0"),
                Fixtures.campaign("x", null, "100", 1000, 10, 2, 2, 1, 0, 0, "0"));
        assertEquals(new BigDecimal("1.0000"), d.get("spend"));
    }
}
