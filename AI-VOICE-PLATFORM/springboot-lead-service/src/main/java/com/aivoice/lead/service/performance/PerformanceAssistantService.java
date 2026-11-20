package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.dto.PerformanceDtos.PerformanceReport;
import com.aivoice.lead.repository.CampaignAnomalyRepository;
import com.aivoice.lead.repository.PerformanceRecommendationRepository;
import com.aivoice.lead.service.ClientAccessService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Answers performance questions. Spring assembles a context of deterministic numbers (current and comparison
 * period, campaigns, ads, CRM segments, open anomalies, pending recommendations); the FastAPI service phrases the
 * answer and verifies every number it states appears in that context.
 */
@Service
public class PerformanceAssistantService {

    public record AskResult(String answer, boolean grounded, List<String> unsupportedNumbers, List<String> dataGaps,
                            String provider, String model, LocalDate from, LocalDate to, LocalDate comparisonFrom,
                            LocalDate comparisonTo) {
    }

    private final PerformanceService performance;
    private final CrmOutcomeRepository crm;
    private final CampaignAnomalyRepository anomalies;
    private final PerformanceRecommendationRepository recommendations;
    private final PerformanceAiClient ai;
    private final ClientAccessService access;

    public PerformanceAssistantService(PerformanceService performance, CrmOutcomeRepository crm,
                                       CampaignAnomalyRepository anomalies,
                                       PerformanceRecommendationRepository recommendations, PerformanceAiClient ai,
                                       ClientAccessService access) {
        this.performance = performance;
        this.crm = crm;
        this.anomalies = anomalies;
        this.recommendations = recommendations;
        this.ai = ai;
        this.access = access;
    }

    public AskResult ask(UUID clientId, String question, LocalDate from, LocalDate to, String comparison) {
        String email = access.read(clientId).email();
        LocalDate prevFrom;
        LocalDate prevTo;
        if ("previous_month".equals(comparison)) {
            prevFrom = from.withDayOfMonth(1).minusMonths(1);
            prevTo = from.withDayOfMonth(1).minusDays(1);
        } else {
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            prevTo = from.minusDays(1);
            prevFrom = prevTo.minusDays(days - 1);
        }
        Map<String, Object> context = context(clientId, email, from, to, prevFrom, prevTo);
        Map<String, Object> response = ai.ask(question, context);
        return new AskResult(String.valueOf(response.getOrDefault("answer", "")),
                Boolean.TRUE.equals(response.get("grounded")), strings(response.get("unsupported_numbers")),
                strings(response.get("data_gaps")), String.valueOf(response.get("provider")),
                String.valueOf(response.get("model")), from, to, prevFrom, prevTo);
    }

    Map<String, Object> context(UUID clientId, String email, LocalDate from, LocalDate to, LocalDate prevFrom, LocalDate prevTo) {
        PerformanceReport current = performance.build(clientId, email, from, to, "campaign", null);
        PerformanceReport previous = performance.build(clientId, email, prevFrom, prevTo, "campaign", null);
        PerformanceReport ads = performance.build(clientId, email, from, to, "ad", null);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("currency", current.currency());
        c.put("period", Map.of("from", from.toString(), "to", to.toString()));
        c.put("comparison_period", Map.of("from", prevFrom.toString(), "to", prevTo.toString()));
        c.put("attribution", "Meta conversions use " + current.attributionWindows()
                + "; CRM leads, qualified leads, site visits and bookings use first-touch campaign attribution.");
        c.put("metric_sources", current.metricSources());
        c.put("meta_available", current.metaAvailable());
        c.put("totals", rowMap(current.totals()));
        c.put("comparison_totals", rowMap(previous.totals()));
        c.put("pct_change_vs_comparison", PerformanceService.changes(current.totals(), previous.totals()));
        c.put("campaigns", current.rows().stream().limit(15).map(PerformanceAssistantService::rowMap).toList());
        c.put("comparison_campaigns", previous.rows().stream().limit(15).map(PerformanceAssistantService::rowMap).toList());
        c.put("top_ads_by_spend", ads.rows().stream().limit(10).map(PerformanceAssistantService::rowMap).toList());
        ZoneId zone = performance.zone();
        c.put("crm_segments", crm.crmSegments(clientId, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().map(s -> Map.of("dimension", s.dimension(), "segment", s.segment() == null ? "unknown" : s.segment(),
                        "leads", s.leads(), "qualified_leads", s.qualified(), "site_visits", s.visitsCompleted(),
                        "bookings", s.bookings())).toList());
        c.put("open_anomalies", anomalies.findTop200ByClientIdOrderByLastDetectedAtDesc(clientId).stream()
                .filter(a -> "open".equals(a.getStatus()) || "acknowledged".equals(a.getStatus())).limit(10)
                .map(a -> Map.of("campaign", a.getCampaignName() == null ? "account" : a.getCampaignName(), "metric", a.getMetric(),
                        "severity", a.getSeverity(), "reason", a.getDetectionReason())).toList());
        c.put("pending_recommendations", recommendations.findTop200ByClientIdOrderByCreatedAtDesc(clientId).stream()
                .filter(r -> "pending".equals(r.getStatus())).limit(10)
                .map(r -> Map.of("title", r.getTitle(), "current_performance", r.getCurrentPerformance(),
                        "confidence", r.getConfidence())).toList());
        List<String> gaps = new ArrayList<>();
        for (DataNote n : current.dataNotes()) gaps.add(n.message());
        gaps.add("Location data for Meta delivery is only available through the audience breakdown screen; CRM segments come from qualification answers.");
        c.put("data_notes", gaps);
        return c;
    }

    static Map<String, Object> rowMap(PerfRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        if (r.objective() != null) m.put("objective", r.objective());
        m.put("spend", r.spend());
        m.put("impressions", r.impressions());
        if (r.reach() != null) m.put("reach", r.reach());
        if (r.frequency() != null) m.put("frequency", r.frequency());
        m.put("link_clicks", r.linkClicks());
        m.put("ctr", r.ctr());
        m.put("cpc", r.cpc());
        m.put("cpm", r.cpm());
        m.put("meta_leads", r.metaLeads());
        m.put("meta_cost_per_lead", r.metaCostPerLead());
        m.put("crm_leads", r.crmLeads());
        m.put("qualified_leads", r.qualifiedLeads());
        m.put("cost_per_qualified_lead", r.costPerQualifiedLead());
        m.put("site_visits", r.siteVisits());
        m.put("cost_per_site_visit", r.costPerSiteVisit());
        m.put("bookings", r.bookings());
        m.put("cost_per_booking", r.costPerBooking());
        m.put("confirmed_revenue", r.confirmedRevenue());
        m.put("roas", r.roas());
        if (!r.flags().isEmpty()) m.put("flags", r.flags());
        return m;
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
