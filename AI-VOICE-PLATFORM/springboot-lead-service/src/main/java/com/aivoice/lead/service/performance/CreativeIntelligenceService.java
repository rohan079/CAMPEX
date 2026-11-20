package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.IntelligenceDtos.CreativeAnalysisDto;
import com.aivoice.lead.dto.IntelligenceDtos.CreativeAnalysisRequest;
import com.aivoice.lead.dto.IntelligenceDtos.CreativeReport;
import com.aivoice.lead.dto.IntelligenceDtos.CreativeRow;
import com.aivoice.lead.dto.IntelligenceDtos.Fatigue;
import com.aivoice.lead.dto.IntelligenceDtos.GroupRow;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.dto.PerformanceDtos.PerfRow;
import com.aivoice.lead.dto.PerformanceDtos.PerformanceReport;
import com.aivoice.lead.entity.CreativeAnalysis;
import com.aivoice.lead.entity.PerformanceSnapshot;
import com.aivoice.lead.repository.CreativeAnalysisRepository;
import com.aivoice.lead.repository.PerformanceSnapshotRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.aivoice.lead.service.performance.MetaPerformanceClient.AdInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creative comparison from Meta's structured creative metadata (headline, text, CTA, format) joined with delivery
 * and CRM outcomes per ad. Images are not analysed: only their reference is shown.
 */
@Service
public class CreativeIntelligenceService {

    private final PerformanceService performance;
    private final MetaAccessResolver accessResolver;
    private final MetaPerformanceClient meta;
    private final PerformanceSnapshotRepository snapshots;
    private final CreativeAnalysisRepository analyses;
    private final PerformanceAiClient ai;
    private final ClientAccessService access;
    private final AuditService audit;

    public CreativeIntelligenceService(PerformanceService performance, MetaAccessResolver accessResolver,
                                       MetaPerformanceClient meta, PerformanceSnapshotRepository snapshots,
                                       CreativeAnalysisRepository analyses, PerformanceAiClient ai,
                                       ClientAccessService access, AuditService audit) {
        this.performance = performance;
        this.accessResolver = accessResolver;
        this.meta = meta;
        this.snapshots = snapshots;
        this.analyses = analyses;
        this.ai = ai;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public CreativeReport report(UUID clientId, LocalDate from, LocalDate to) {
        String email = access.read(clientId).email();
        PerformanceReport ads = performance.build(clientId, email, from, to, "ad", null);
        List<DataNote> notes = new ArrayList<>(ads.dataNotes());
        Map<String, AdInfo> meta = adMetadata(clientId, email, notes);
        Map<String, Fatigue> fatigue = fatigue(snapshots.findByClientIdAndLevelAndDayBetweenOrderByDayAsc(
                clientId, "ad", to.minusDays(13), to));
        String rankingMetric = rankingMetric(ads.rows());
        List<CreativeRow> rows = new ArrayList<>();
        for (PerfRow r : ads.rows()) {
            AdInfo info = meta.get(r.id());
            rows.add(new CreativeRow(r.id(), r.name(), r.campaignId(), r.adsetId(), info == null ? null : info.status(),
                    info == null ? null : info.creativeId(), info == null ? null : info.headline(),
                    info == null ? null : info.primaryText(), info == null ? null : info.cta(),
                    info == null ? null : (info.imageUrl() != null ? info.imageUrl() : info.thumbnailUrl()),
                    info == null ? null : info.format(), r,
                    fatigue.getOrDefault(r.id(), new Fatigue("unknown", "No daily snapshots for this ad", null, null, null, null)),
                    null));
        }
        rows = rank(rows, rankingMetric);
        List<CreativeAnalysisDto> drafts = analyses.findTop50ByClientIdOrderByCreatedAtDesc(clientId).stream()
                .map(CreativeIntelligenceService::toDto).toList();
        return new CreativeReport(clientId.toString(), from, to, ads.currency(), ads.metaAvailable(), rankingMetric, rows,
                group(rows, "headline", CreativeRow::headline), group(rows, "cta", CreativeRow::cta),
                group(rows, "image", c -> c.creativeId() == null ? null : c.imageUrl() == null ? null : c.creativeId()),
                drafts, notes);
    }

    private Map<String, AdInfo> adMetadata(UUID clientId, String email, List<DataNote> notes) {
        Map<String, AdInfo> out = new HashMap<>();
        MetaAccess metaAccess = accessResolver.resolve(clientId, email);
        if (!metaAccess.available()) return out;
        try {
            for (String account : metaAccess.adAccounts()) {
                for (AdInfo ad : meta.ads(account, metaAccess.token())) out.put(ad.adId(), ad);
            }
        } catch (MetaGraphException e) {
            notes.add(new DataNote("creative_metadata_unavailable", "warning",
                    "Creative metadata could not be loaded from Meta (code " + e.graphCode() + ")."));
        }
        return out;
    }

    /**
     * Picks the strongest outcome metric with enough data: cost per qualified lead (≥2 ads with 3+ qualified leads),
     * else Meta cost per lead (≥2 ads with 5+ leads), else link CTR.
     */
    static String rankingMetric(List<PerfRow> rows) {
        if (rows.stream().filter(r -> r.qualifiedLeads() >= 3).count() >= 2) return "cost_per_qualified_lead";
        if (rows.stream().filter(r -> r.metaLeads() >= 5).count() >= 2) return "meta_cost_per_lead";
        return "ctr";
    }

    static List<CreativeRow> rank(List<CreativeRow> rows, String metric) {
        Function<PerfRow, BigDecimal> value = switch (metric) {
            case "cost_per_qualified_lead" -> r -> r.qualifiedLeads() >= 3 ? r.costPerQualifiedLead() : null;
            case "meta_cost_per_lead" -> r -> r.metaLeads() >= 5 ? r.metaCostPerLead() : null;
            default -> r -> r.impressions() >= 1000 ? r.ctr() : null;
        };
        boolean higherIsBetter = "ctr".equals(metric);
        List<CreativeRow> eligible = rows.stream().filter(c -> value.apply(c.metrics()) != null).toList();
        if (eligible.size() < 2) return rows;
        Comparator<CreativeRow> cmp = Comparator.comparing(c -> value.apply(c.metrics()));
        CreativeRow best = higherIsBetter ? eligible.stream().max(cmp).get() : eligible.stream().min(cmp).get();
        CreativeRow worst = higherIsBetter ? eligible.stream().min(cmp).get() : eligible.stream().max(cmp).get();
        List<CreativeRow> out = new ArrayList<>();
        for (CreativeRow c : rows) {
            String rank = c == best ? "best" : c == worst ? "worst" : null;
            out.add(new CreativeRow(c.adId(), c.adName(), c.campaignId(), c.adsetId(), c.status(), c.creativeId(),
                    c.headline(), c.primaryText(), c.cta(), c.imageUrl(), c.format(), c.metrics(), c.fatigue(), rank));
        }
        return out;
    }

    /**
     * Fatigue: last 7 days vs the 7 before. Flagged when link CTR fell ≥20% while average frequency is ≥2.5 or
     * rose ≥20%, with at least 1,000 impressions in each window.
     */
    static Map<String, Fatigue> fatigue(List<PerformanceSnapshot> snaps) {
        if (snaps.isEmpty()) return Map.of();
        LocalDate last = snaps.stream().map(PerformanceSnapshot::getDay).max(LocalDate::compareTo).orElseThrow();
        LocalDate split = last.minusDays(6);
        Map<String, long[]> recent = new HashMap<>();
        Map<String, long[]> previous = new HashMap<>();
        Map<String, BigDecimal[]> freq = new HashMap<>();
        for (PerformanceSnapshot s : snaps) {
            boolean isRecent = !s.getDay().isBefore(split);
            long[] acc = (isRecent ? recent : previous).computeIfAbsent(s.getObjectId(), k -> new long[2]);
            acc[0] += s.getImpressions();
            acc[1] += s.getLinkClicks();
            if (s.getFrequency() != null) {
                BigDecimal[] f = freq.computeIfAbsent(s.getObjectId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                int i = isRecent ? 0 : 2;
                f[i] = f[i].add(s.getFrequency().multiply(BigDecimal.valueOf(s.getImpressions())));
                f[i + 1] = f[i + 1].add(BigDecimal.valueOf(s.getImpressions()));
            }
        }
        Map<String, Fatigue> out = new HashMap<>();
        for (String ad : recent.keySet()) {
            long[] r = recent.get(ad);
            long[] p = previous.getOrDefault(ad, new long[2]);
            if (r[0] < 1000 || p[0] < 1000) {
                out.put(ad, new Fatigue("insufficient_data", "Fewer than 1,000 impressions in one of the two 7-day windows",
                        null, null, null, null));
                continue;
            }
            BigDecimal ctrR = MetricSet.ratio(r[1], r[0]);
            BigDecimal ctrP = MetricSet.ratio(p[1], p[0]);
            BigDecimal[] f = freq.get(ad);
            BigDecimal fR = f == null || f[1].signum() == 0 ? null : f[0].divide(f[1], 2, RoundingMode.HALF_UP);
            BigDecimal fP = f == null || f[3].signum() == 0 ? null : f[2].divide(f[3], 2, RoundingMode.HALF_UP);
            boolean ctrDrop = ctrP.signum() > 0 && ctrR.compareTo(ctrP.multiply(new BigDecimal("0.8"))) <= 0;
            boolean freqHigh = fR != null && (fR.compareTo(new BigDecimal("2.5")) >= 0
                    || (fP != null && fR.compareTo(fP.multiply(new BigDecimal("1.2"))) >= 0));
            String status = ctrDrop && freqHigh ? "fatigued" : ctrDrop ? "watch" : "ok";
            String reason = switch (status) {
                case "fatigued" -> "Link CTR fell from " + OptimizationRules.pct(ctrP) + " to " + OptimizationRules.pct(ctrR)
                        + " while average frequency is " + fR;
                case "watch" -> "Link CTR fell from " + OptimizationRules.pct(ctrP) + " to " + OptimizationRules.pct(ctrR)
                        + ", but frequency does not indicate fatigue";
                default -> "No fatigue signal";
            };
            out.put(ad, new Fatigue(status, reason, ctrR, ctrP, fR, fP));
        }
        return out;
    }

    static List<GroupRow> group(List<CreativeRow> rows, String dimension, Function<CreativeRow, String> key) {
        Map<String, MetricSet> sets = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CreativeRow c : rows) {
            String k = key.apply(c);
            if (k == null || k.isBlank()) continue;
            MetricSet m = sets.computeIfAbsent(k, x -> new MetricSet());
            PerfRow r = c.metrics();
            m.spend = m.spend.add(r.spend());
            m.impressions += r.impressions();
            m.linkClicks += r.linkClicks();
            m.metaLeads += r.metaLeads();
            m.crmLeads += r.crmLeads();
            m.qualifiedLeads += r.qualifiedLeads();
            m.siteVisitsCompleted += r.siteVisits();
            m.bookings += r.bookings();
            m.confirmedRevenue = m.confirmedRevenue.add(r.confirmedRevenue());
            counts.merge(k, 1, Integer::sum);
        }
        List<GroupRow> out = new ArrayList<>();
        sets.forEach((k, m) -> out.add(new GroupRow(dimension, k, counts.get(k), new PerfRow("group", k, k, null, null, null,
                null, null, m.spend, m.impressions, null, null, m.linkClicks, m.ctr(), m.cpc(), m.cpm(), m.metaLeads,
                m.metaCostPerLead(), m.conversionRate(), m.crmLeads, m.qualifiedLeads, m.qualifiedRate(),
                m.costPerQualifiedLead(), m.siteVisitsCompleted, m.costPerSiteVisit(), m.bookings, m.costPerBooking(),
                m.bookingValue, m.confirmedRevenue, m.roas(), List.of()))));
        out.sort(Comparator.comparing((GroupRow g) -> g.metrics().spend()).reversed());
        return out;
    }

    /** Builds a deterministic observation for one ad, asks the AI for variants and stores them as a draft. */
    @Transactional
    public CreativeAnalysisDto analyze(UUID clientId, CreativeAnalysisRequest request) {
        Actor actor = access.require(clientId, Permission.OPERATE);
        LocalDate to = request.to() == null ? performance.today().minusDays(1) : request.to();
        LocalDate from = request.from() == null ? to.minusDays(27) : request.from();
        PerformanceReport ads = performance.build(clientId, actor.email(), from, to, "ad", null);
        PerfRow ad = ads.rows().stream().filter(r -> r.id().equals(request.adId())).findFirst()
                .orElseThrow(() -> new NotFoundException("No delivery for this ad in the period"));
        List<DataNote> notes = new ArrayList<>();
        AdInfo info = adMetadata(clientId, actor.email(), notes).get(request.adId());
        BigDecimal medianCtr = OptimizationRules.median(ads.rows().stream().filter(r -> r.ctr() != null && r.impressions() >= 1000)
                .map(PerfRow::ctr).toList().isEmpty() ? List.of(BigDecimal.ZERO)
                : ads.rows().stream().filter(r -> r.ctr() != null && r.impressions() >= 1000).map(PerfRow::ctr).toList());
        String observation = observation(ad, medianCtr);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("period", from + " to " + to);
        metrics.put("currency", ads.currency());
        metrics.put("spend", ad.spend());
        metrics.put("impressions", ad.impressions());
        metrics.put("link_ctr", ad.ctr());
        metrics.put("median_link_ctr_of_ads", medianCtr);
        metrics.put("meta_leads", ad.metaLeads());
        metrics.put("crm_leads", ad.crmLeads());
        metrics.put("qualified_leads", ad.qualifiedLeads());
        metrics.put("site_visits", ad.siteVisits());
        metrics.put("bookings", ad.bookings());
        Map<String, Object> creative = new LinkedHashMap<>();
        creative.put("headline", info == null ? null : info.headline());
        creative.put("primary_text", info == null ? null : info.primaryText());
        creative.put("cta", info == null ? null : info.cta());
        creative.put("format", info == null ? null : info.format());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("observation", observation);
        body.put("creative", creative);
        body.put("metrics", metrics);
        body.put("audience_notes", request.audienceNotes());
        Map<String, Object> suggestions = ai.creativeSuggestions(body);
        CreativeAnalysis analysis = new CreativeAnalysis();
        analysis.setClientId(clientId);
        analysis.setAdId(request.adId());
        analysis.setCampaignId(ad.campaignId());
        analysis.setObservation(observation);
        analysis.setInputMetrics(metrics);
        Map<String, Object> stored = new LinkedHashMap<>(suggestions);
        stored.remove("provider");
        stored.remove("model");
        analysis.setSuggestions(stored);
        analysis.setAiProvider(String.valueOf(suggestions.get("provider")));
        analysis.setAiModel(String.valueOf(suggestions.get("model")));
        analysis.setCreatedBy(actor.email());
        analysis = analyses.save(analysis);
        audit.record(clientId, actor.email(), "creative_analysis", analysis.getId(), "draft_created",
                Map.of("ad_id", request.adId(), "published", false));
        return toDto(analysis);
    }

    static String observation(PerfRow ad, BigDecimal medianCtr) {
        StringBuilder s = new StringBuilder();
        s.append("Link CTR ").append(OptimizationRules.pct(ad.ctr())).append(" vs a median of ")
                .append(OptimizationRules.pct(medianCtr)).append(" across ads. ");
        s.append(ad.metaLeads()).append(" Meta-reported leads, ").append(ad.crmLeads()).append(" CRM leads, ")
                .append(ad.qualifiedLeads()).append(" qualified, ").append(ad.siteVisits()).append(" site visits, ")
                .append(ad.bookings()).append(" bookings.");
        if (ad.ctr() != null && medianCtr.signum() > 0 && ad.ctr().compareTo(medianCtr) >= 0 && ad.crmLeads() > 0
                && ad.qualifiedLeads() * 2 < ad.crmLeads()) {
            s.append(" The ad attracts clicks but under half of its leads qualify.");
        } else if (ad.ctr() != null && medianCtr.signum() > 0 && ad.ctr().compareTo(medianCtr.multiply(new BigDecimal("0.6"))) < 0) {
            s.append(" The ad's click-through is well below the other ads.");
        }
        return s.toString();
    }

    static CreativeAnalysisDto toDto(CreativeAnalysis a) {
        return new CreativeAnalysisDto(a.getId().toString(), a.getAdId(), a.getCampaignId(), a.getObservation(),
                a.getInputMetrics(), a.getSuggestions(), a.getStatus(), a.getAiProvider(), a.getAiModel(), a.getCreatedAt());
    }
}
