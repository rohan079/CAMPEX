package com.aivoice.lead.service.performance;

import com.aivoice.lead.config.PerformanceProperties;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.dto.PerformanceDtos.PerformanceReport;
import com.aivoice.lead.dto.RecommendationDtos.EvidenceDto;
import com.aivoice.lead.dto.RecommendationDtos.GenerateResult;
import com.aivoice.lead.dto.RecommendationDtos.RecommendationDto;
import com.aivoice.lead.dto.RecommendationDtos.StatusResult;
import com.aivoice.lead.dto.RecommendationDtos.StatusUpdate;
import com.aivoice.lead.entity.CampaignAnomaly;
import com.aivoice.lead.entity.ClientBudgetPolicy;
import com.aivoice.lead.entity.PerformanceRecommendation;
import com.aivoice.lead.entity.RecommendationEvidence;
import com.aivoice.lead.repository.CampaignAnomalyRepository;
import com.aivoice.lead.repository.PerformanceRecommendationRepository;
import com.aivoice.lead.repository.RecommendationEvidenceRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.performance.Finding.Evidence;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns deterministic findings into stored, auditable recommendations. Duplicates are prevented (one open
 * recommendation per finding), recommendations expire, and every decision is written to audit_events.
 * Accepting a recommendation never changes a live Meta campaign.
 */
@Service
public class RecommendationService {

    private final PerformanceRecommendationRepository recommendations;
    private final RecommendationEvidenceRepository evidence;
    private final CampaignAnomalyRepository anomalies;
    private final PerformanceService performance;
    private final BudgetService budgets;
    private final PerformanceAiClient ai;
    private final ClientAccessService access;
    private final AuditService audit;
    private final PerformanceProperties props;
    private final Clock clock;

    public RecommendationService(PerformanceRecommendationRepository recommendations,
                                 RecommendationEvidenceRepository evidence, CampaignAnomalyRepository anomalies,
                                 PerformanceService performance, BudgetService budgets, PerformanceAiClient ai,
                                 ClientAccessService access, AuditService audit, PerformanceProperties props, Clock clock) {
        this.recommendations = recommendations;
        this.evidence = evidence;
        this.anomalies = anomalies;
        this.performance = performance;
        this.budgets = budgets;
        this.ai = ai;
        this.access = access;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public GenerateResult generate(UUID clientId, LocalDate from, LocalDate to, boolean explain) {
        Actor actor = access.require(clientId, Permission.OPERATE);
        PerformanceReport campaigns = performance.build(clientId, actor.email(), from, to, "campaign", null);
        PerformanceReport ads = performance.build(clientId, actor.email(), from, to, "ad", null);
        PerformanceService.MetaData meta = performance.metaData(clientId, actor.email(), from, to, "campaign");
        ClientBudgetPolicy policy = budgets.policy(clientId);
        String period = from + "–" + to;
        List<Finding> findings = new ArrayList<>();
        if (campaigns.metaAvailable()) {
            findings.addAll(OptimizationRules.campaignFindings(campaigns.rows(), props.minConversions(), props.minClicks(),
                    campaigns.currency(), period));
            findings.addAll(OptimizationRules.adFindings(ads.rows(), props.minImpressions(), period));
            findings.addAll(BudgetEngine.analyze(campaigns.rows(), meta.campaigns(), policy,
                    ChronoUnit.DAYS.between(from, to) + 1, from.toString(), to.toString(), campaigns.currency()).findings());
        }
        findings.addAll(anomalyFindings(clientId));

        recommendations.expireOverdue(clock.instant());
        int created = 0;
        int duplicates = 0;
        List<PerformanceRecommendation> fresh = new ArrayList<>();
        Instant now = clock.instant();
        for (Finding f : findings) {
            if (recommendations.findByClientIdAndDedupKeyAndStatus(clientId, f.dedupKey(), "pending").isPresent()) {
                duplicates++;
                continue;
            }
            PerformanceRecommendation r = toEntity(clientId, f, from, to, now, policy.getRecommendationTtlHours());
            r = recommendations.saveAndFlush(r);
            for (Evidence e : f.evidence()) {
                RecommendationEvidence row = new RecommendationEvidence();
                row.setRecommendationId(r.getId());
                row.setMetric(e.metric());
                row.setLabel(e.label());
                row.setValue(e.value());
                row.setUnit(e.unit());
                row.setSource(e.source());
                row.setScope(e.scope());
                row.setPeriodFrom(from);
                row.setPeriodTo(to);
                evidence.save(row);
            }
            fresh.add(r);
            created++;
        }
        boolean explained = explain && !fresh.isEmpty() && explainWithAi(fresh, campaigns);
        audit.record(clientId, actor.email(), "performance_recommendations", clientId, "generated",
                Map.of("findings", findings.size(), "created", created, "duplicates_skipped", duplicates,
                        "period", period));
        List<DataNote> notes = new ArrayList<>(campaigns.dataNotes());
        if (!campaigns.metaAvailable()) {
            notes.add(new DataNote("limited_recommendations", "warning",
                    "Without Meta data only anomaly-based recommendations can be produced."));
        }
        return new GenerateResult(findings.size(), created, duplicates, explained, notes,
                toDtos(fresh));
    }

    /** Open critical anomalies become "investigate" recommendations. */
    List<Finding> anomalyFindings(UUID clientId) {
        List<Finding> out = new ArrayList<>();
        for (CampaignAnomaly a : anomalies.findTop200ByClientIdOrderByLastDetectedAtDesc(clientId)) {
            if (!"open".equals(a.getStatus()) || !"CRITICAL".equals(a.getSeverity())) continue;
            List<Evidence> ev = new ArrayList<>();
            if (a.getPreviousValue() != null) ev.add(new Evidence(a.getMetric() + "_baseline", "Baseline " + a.getMetric(), a.getPreviousValue(), "value", sourceOf(a.getMetric()), "campaign"));
            if (a.getCurrentValue() != null) ev.add(new Evidence(a.getMetric() + "_current", "Current " + a.getMetric(), a.getCurrentValue(), "value", sourceOf(a.getMetric()), "campaign"));
            if (a.getPctChange() != null) ev.add(new Evidence("pct_change", "Change", a.getPctChange(), "ratio", "derived", "campaign"));
            out.add(new Finding("anomaly", "investigate_decline", a.getCampaignId(), null, null, a.getCampaignName(),
                    "Investigate " + a.getMetric().replace('_', ' ') + " change in " + (a.getCampaignName() == null ? "this campaign" : a.getCampaignName()),
                    a.getDetectionReason(), "A critical anomaly is still open for this campaign.",
                    "Estimate: none — the impact depends on the cause.", "medium", "low", a.getSuggestedAction(), null, ev,
                    "anomaly:investigate:" + a.getId()));
        }
        return out;
    }

    private boolean explainWithAi(List<PerformanceRecommendation> fresh, PerformanceReport report) {
        Map<UUID, List<RecommendationEvidence>> ev = evidence.findByRecommendationIdIn(
                fresh.stream().map(PerformanceRecommendation::getId).toList()).stream()
                .collect(Collectors.groupingBy(RecommendationEvidence::getRecommendationId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (PerformanceRecommendation r : fresh) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId().toString());
            item.put("title", r.getTitle());
            item.put("current_performance", r.getCurrentPerformance());
            item.put("reasoning", r.getReasoning());
            item.put("recommended_action", r.getRecommendedAction());
            item.put("expected_impact", r.getExpectedImpact());
            item.put("evidence", ev.getOrDefault(r.getId(), List.of()).stream().map(e -> Map.of(
                    "label", e.getLabel(), "value", e.getValue() == null ? "unavailable" : e.getValue(),
                    "unit", e.getUnit(), "source", e.getSource())).toList());
            items.add(item);
        }
        Map<String, Object> context = Map.of("currency", report.currency(), "period_from", report.from().toString(),
                "period_to", report.to().toString(), "attribution_windows", report.attributionWindows(),
                "data_notes", report.dataNotes().stream().map(DataNote::message).toList());
        Map<String, Object> response = ai.explain("recommendations", context, items).orElse(null);
        if (response == null || !(response.get("explanations") instanceof List<?> list)) {
            return false;
        }
        Map<String, PerformanceRecommendation> byId = fresh.stream()
                .collect(Collectors.toMap(r -> r.getId().toString(), r -> r));
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && byId.containsKey(String.valueOf(m.get("id")))
                    && Boolean.TRUE.equals(m.get("grounded")) && m.get("text") instanceof String text) {
                PerformanceRecommendation r = byId.get(String.valueOf(m.get("id")));
                r.setAiExplanation(text.length() > 4000 ? text.substring(0, 4000) : text);
                r.setAiProvider(String.valueOf(response.get("provider")));
                r.setAiModel(String.valueOf(response.get("model")));
            }
        }
        return true;
    }

    @Transactional
    public List<RecommendationDto> list(UUID clientId, String status, String category) {
        access.read(clientId);
        recommendations.expireOverdue(clock.instant());
        List<PerformanceRecommendation> rows = recommendations.findTop200ByClientIdOrderByCreatedAtDesc(clientId).stream()
                .filter(r -> status == null || status.isBlank() || status.equals(r.getStatus()))
                .filter(r -> category == null || category.isBlank() || category.equals(r.getCategory())).toList();
        return toDtos(rows);
    }

    @Transactional(readOnly = true)
    public RecommendationDto get(UUID id) {
        PerformanceRecommendation r = recommendations.findById(id).orElseThrow(() -> new NotFoundException("Recommendation not found"));
        access.read(r.getClientId());
        return toDtos(List.of(r)).get(0);
    }

    @Transactional
    public StatusResult updateStatus(UUID id, StatusUpdate update) {
        PerformanceRecommendation r = recommendations.findById(id).orElseThrow(() -> new NotFoundException("Recommendation not found"));
        Permission needed = "budget".equals(r.getCategory()) && "accepted".equals(update.status()) ? Permission.MANAGE : Permission.OPERATE;
        Actor actor = access.require(r.getClientId(), needed);
        if (r.getExpiresAt().isBefore(clock.instant()) && "pending".equals(r.getStatus())) {
            r.setStatus("expired");
        }
        boolean allowed = "pending".equals(r.getStatus())
                || ("reviewed".equals(r.getStatus()) && !"reviewed".equals(update.status()));
        if (!allowed) {
            throw new ConflictException("This recommendation is " + r.getStatus() + " and can no longer change");
        }
        String from = r.getStatus();
        r.setStatus(update.status());
        r.setDecidedBy(actor.email());
        r.setDecidedAt(clock.instant());
        r.setDecisionNote(update.note() == null || update.note().isBlank() ? null : update.note().trim());
        recommendations.save(r);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from);
        details.put("to", update.status());
        details.put("type", r.getRecommendationType());
        details.put("campaign_id", r.getCampaignId());
        details.put("proposed_change", r.getProposedChange());
        details.put("note", r.getDecisionNote());
        details.put("meta_changed", false);
        audit.record(r.getClientId(), actor.email(), "performance_recommendation", r.getId(), "status_" + update.status(), details);
        String message = "accepted".equals(update.status())
                ? "Recorded as accepted. Nothing was changed in Meta — apply the change in Ads Manager or Campaign Studio after review."
                : "Recorded as " + update.status() + ".";
        return new StatusResult(toDtos(List.of(r)).get(0), message);
    }

    PerformanceRecommendation toEntity(UUID clientId, Finding f, LocalDate from, LocalDate to, Instant now, int ttlHours) {
        PerformanceRecommendation r = new PerformanceRecommendation();
        r.setClientId(clientId);
        r.setCategory(f.category());
        r.setRecommendationType(f.type());
        r.setCampaignId(f.campaignId());
        r.setAdsetId(f.adsetId());
        r.setAdId(f.adId());
        r.setObjectName(f.objectName());
        r.setTitle(f.title().length() > 300 ? f.title().substring(0, 300) : f.title());
        r.setCurrentPerformance(f.currentPerformance());
        r.setReasoning(f.reasoning());
        r.setExpectedImpact(f.expectedImpact());
        r.setImpactIsEstimate(true);
        r.setConfidence(f.confidence());
        r.setRiskLevel(f.risk());
        r.setRecommendedAction(f.action());
        r.setProposedChange(f.proposedChange());
        r.setDedupKey(f.dedupKey());
        r.setAnalysisFrom(from);
        r.setAnalysisTo(to);
        r.setCalculatedAt(now);
        r.setCreatedAt(now);
        r.setExpiresAt(now.plus(ttlHours, ChronoUnit.HOURS));
        return r;
    }

    private List<RecommendationDto> toDtos(List<PerformanceRecommendation> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, List<RecommendationEvidence>> ev = evidence.findByRecommendationIdIn(
                rows.stream().map(PerformanceRecommendation::getId).toList()).stream()
                .collect(Collectors.groupingBy(RecommendationEvidence::getRecommendationId));
        return rows.stream().map(r -> new RecommendationDto(r.getId().toString(), r.getClientId().toString(),
                r.getCategory(), r.getRecommendationType(), r.getCampaignId(), r.getAdsetId(), r.getAdId(), r.getObjectName(),
                r.getTitle(), r.getCurrentPerformance(), r.getReasoning(), r.getExpectedImpact(), r.isImpactIsEstimate(),
                r.getConfidence(), r.getRiskLevel(), r.getRecommendedAction(), r.getProposedChange(), r.getAiExplanation(),
                r.getAiProvider(), r.getStatus(), r.getAnalysisFrom(), r.getAnalysisTo(), r.getCalculatedAt(), r.getCreatedAt(),
                r.getExpiresAt(), r.getDecidedBy(), r.getDecidedAt(), r.getDecisionNote(),
                ev.getOrDefault(r.getId(), List.of()).stream().map(e -> new EvidenceDto(e.getMetric(), e.getLabel(),
                        e.getValue(), e.getUnit(), e.getSource(), e.getScope(), e.getPeriodFrom(), e.getPeriodTo())).toList()))
                .toList();
    }

    static String sourceOf(String metric) {
        return switch (metric) {
            case "spend", "impressions", "meta_leads", "frequency" -> "meta_reported";
            case "qualified_leads" -> "crm_verified";
            case "event_delivery_failures" -> "campex_tracked";
            default -> "derived";
        };
    }

}
