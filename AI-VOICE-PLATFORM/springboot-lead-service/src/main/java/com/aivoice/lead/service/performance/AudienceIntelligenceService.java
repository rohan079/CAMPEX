package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.IntelligenceDtos.AudienceReport;
import com.aivoice.lead.dto.IntelligenceDtos.CrmSegment;
import com.aivoice.lead.dto.IntelligenceDtos.Insight;
import com.aivoice.lead.dto.IntelligenceDtos.SegmentRow;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.aivoice.lead.service.performance.CrmOutcomeRepository.SegmentCount;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.aivoice.lead.service.performance.MetaPerformanceClient.InsightRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Audience and placement performance from Meta Insights breakdowns, plus CRM segment outcomes. Meta breakdowns and
 * CRM data are reported side by side and never joined: Campex leads do not carry Meta's age/region/placement, so
 * lead quality per Meta segment is reported as not measurable rather than guessed.
 */
@Service
public class AudienceIntelligenceService {

    static final Set<String> AUDIENCE_BREAKDOWNS = Set.of("age", "gender", "age,gender", "country", "region", "device_platform");
    static final String PLACEMENT_BREAKDOWN = "publisher_platform,platform_position";

    private final MetaAccessResolver accessResolver;
    private final MetaPerformanceClient meta;
    private final CrmOutcomeRepository crm;
    private final PerformanceService performance;
    private final ClientAccessService access;
    private final String currency;

    public AudienceIntelligenceService(MetaAccessResolver accessResolver, MetaPerformanceClient meta,
                                       CrmOutcomeRepository crm, PerformanceService performance,
                                       ClientAccessService access, @Value("${app.currency:INR}") String currency) {
        this.accessResolver = accessResolver;
        this.meta = meta;
        this.crm = crm;
        this.performance = performance;
        this.access = access;
        this.currency = currency;
    }

    public AudienceReport audience(UUID clientId, LocalDate from, LocalDate to, String breakdown) {
        String b = breakdown == null || breakdown.isBlank() ? "age,gender" : breakdown;
        if (!AUDIENCE_BREAKDOWNS.contains(b)) {
            throw new IllegalArgumentException("Supported audience breakdowns: " + String.join(", ", AUDIENCE_BREAKDOWNS));
        }
        return build(clientId, from, to, b, true);
    }

    public AudienceReport placements(UUID clientId, LocalDate from, LocalDate to) {
        return build(clientId, from, to, PLACEMENT_BREAKDOWN, false);
    }

    private AudienceReport build(UUID clientId, LocalDate from, LocalDate to, String breakdown, boolean includeCrm) {
        String email = access.read(clientId).email();
        performance.validateRange(from, to);
        List<DataNote> notes = new ArrayList<>();
        List<InsightRow> rows = new ArrayList<>();
        boolean available = false;
        MetaAccess metaAccess = accessResolver.resolve(clientId, email);
        if (!metaAccess.available()) {
            notes.add(new DataNote("meta_unavailable", "warning", metaAccess.note()));
        } else {
            try {
                for (String account : metaAccess.adAccounts()) {
                    rows.addAll(meta.insights(account, from, to, "account", false, breakdown, metaAccess.token()));
                }
                available = true;
            } catch (MetaGraphException e) {
                notes.add(new DataNote("meta_error", "warning", "Meta breakdown request failed (code " + e.graphCode() + ")."));
            }
        }
        List<SegmentRow> segments = segments(rows, breakdown);
        List<Insight> insights = new ArrayList<>(metaInsights(segments, breakdown));
        notes.add(new DataNote("segment_quality_not_measurable", "info",
                "Lead quality, site visits and bookings per Meta " + (includeCrm ? "audience segment" : "placement")
                        + " cannot be measured: Campex leads do not carry Meta's segment. Meta-reported leads are shown instead."));
        List<CrmSegment> crmSegments = List.of();
        Map<String, Map<String, Long>> fit = Map.of();
        if (includeCrm) {
            ZoneId zone = performance.zone();
            var fromI = from.atStartOfDay(zone).toInstant();
            var toI = to.plusDays(1).atStartOfDay(zone).toInstant();
            crmSegments = crm.crmSegments(clientId, fromI, toI).stream().map(AudienceIntelligenceService::toCrm).toList();
            fit = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> e : crm.locationFit(clientId, fromI, toI).entrySet()) {
                fit.put(e.getKey(), Map.of("leads", e.getValue()[0], "site_visits", e.getValue()[1], "bookings", e.getValue()[2]));
            }
            insights.addAll(crmInsights(crmSegments, fit));
            if (crmSegments.isEmpty()) {
                notes.add(new DataNote("no_qualification_data", "info",
                        "No qualification answers (preferred location, timeline, purpose) were captured for leads in this period."));
            }
        }
        return new AudienceReport(clientId.toString(), from, to, breakdown, currency, available, segments, crmSegments, fit,
                insights, notes);
    }

    static List<SegmentRow> segments(List<InsightRow> rows, String breakdown) {
        Map<String, MetricSet> sets = new LinkedHashMap<>();
        Map<String, Map<String, String>> keys = new LinkedHashMap<>();
        for (InsightRow r : rows) {
            String label = String.join(" · ", r.breakdown().values());
            sets.computeIfAbsent(label, k -> new MetricSet()).addMeta(r);
            keys.putIfAbsent(label, r.breakdown());
        }
        BigDecimal totalSpend = sets.values().stream().map(m -> m.spend).reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalLeads = sets.values().stream().mapToLong(m -> m.metaLeads).sum();
        BigDecimal overallCpl = totalLeads == 0 ? null : totalSpend.divide(BigDecimal.valueOf(totalLeads), 2, RoundingMode.HALF_UP);
        List<SegmentRow> out = new ArrayList<>();
        sets.forEach((label, m) -> {
            List<String> flags = new ArrayList<>();
            BigDecimal share = totalSpend.signum() == 0 ? null : m.spend.divide(totalSpend, 4, RoundingMode.HALF_UP);
            BigDecimal cpl = m.metaCostPerLead();
            if (m.impressions < 1000) flags.add("low_sample");
            if (overallCpl != null && cpl != null && m.metaLeads >= 5) {
                if (cpl.compareTo(overallCpl.multiply(new BigDecimal("1.5"))) >= 0) flags.add("high_cost");
                if (cpl.compareTo(overallCpl.multiply(new BigDecimal("0.7"))) <= 0) flags.add("high_converting");
            }
            if (m.metaLeads < 5 && share != null && share.compareTo(new BigDecimal("0.10")) >= 0) flags.add("spend_without_enough_leads");
            out.add(new SegmentRow(keys.get(label), label, m.spend, m.impressions, m.linkClicks, m.ctr(), m.cpc(), m.cpm(),
                    m.metaLeads, cpl, share, totalLeads == 0 ? null : BigDecimal.valueOf(m.metaLeads)
                    .divide(BigDecimal.valueOf(totalLeads), 4, RoundingMode.HALF_UP), flags));
        });
        out.sort(Comparator.comparing(SegmentRow::spend).reversed());
        return out;
    }

    static List<Insight> metaInsights(List<SegmentRow> segments, String breakdown) {
        List<Insight> out = new ArrayList<>();
        boolean placement = PLACEMENT_BREAKDOWN.equals(breakdown);
        for (SegmentRow s : segments) {
            List<String> ev = List.of("spend " + s.spend(), "Meta-reported leads " + s.metaLeads(),
                    "cost per Meta lead " + (s.metaCostPerLead() == null ? "—" : s.metaCostPerLead()));
            if (s.flags().contains("high_cost")) {
                out.add(new Insight("high_cost_segment", s.label() + " costs at least 1.5× the average per Meta-reported lead.", ev));
            }
            if (s.flags().contains("high_converting")) {
                out.add(new Insight(placement ? "inexpensive_placement" : "high_converting_segment", s.label()
                        + " produces Meta-reported leads at 0.7× the average cost or less"
                        + (placement ? "; check their quality in the CRM before shifting budget, because quality per placement is not measured." : "."), ev));
            }
            if (s.flags().contains("spend_without_enough_leads")) {
                out.add(new Insight("spend_without_leads", s.label() + " takes at least 10% of spend with fewer than 5 Meta-reported leads.", ev));
            }
        }
        return out;
    }

    static List<Insight> crmInsights(List<CrmSegment> segments, Map<String, Map<String, Long>> fit) {
        List<Insight> out = new ArrayList<>();
        for (String dim : List.of("preferred_location", "purchase_timeline", "project")) {
            segments.stream().filter(s -> s.dimension().equals(dim) && s.leads() >= 5 && s.siteVisitRate() != null)
                    .max(Comparator.comparing(CrmSegment::siteVisitRate))
                    .ifPresent(best -> out.add(new Insight("top_" + dim, "Among leads with " + dim.replace('_', ' ')
                            + " captured, \"" + best.segment() + "\" has the highest site-visit rate ("
                            + best.siteVisits() + " of " + best.leads() + " leads).",
                            List.of("leads " + best.leads(), "site visits " + best.siteVisits(), "bookings " + best.bookings()))));
        }
        Map<String, Long> match = fit.get("match");
        Map<String, Long> mismatch = fit.get("mismatch");
        if (match != null && mismatch != null && match.get("leads") >= 5 && mismatch.get("leads") >= 5) {
            out.add(new Insight("location_fit", "Leads whose preferred location matches the project location: "
                    + match.get("site_visits") + " visits from " + match.get("leads") + " leads; other leads: "
                    + mismatch.get("site_visits") + " visits from " + mismatch.get("leads") + ". Consider an audience/messaging experiment around location.",
                    List.of("match leads " + match.get("leads"), "mismatch leads " + mismatch.get("leads"))));
        }
        return out;
    }

    static CrmSegment toCrm(SegmentCount s) {
        return new CrmSegment(s.dimension(), s.segment(), s.leads(), s.qualified(), s.visitsCompleted(), s.bookings(),
                MetricSet.ratio(s.qualified(), s.leads()), MetricSet.ratio(s.visitsCompleted(), s.leads()),
                MetricSet.ratio(s.bookings(), s.leads()));
    }
}
