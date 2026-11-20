package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.ExperimentDtos.CreateExperiment;
import com.aivoice.lead.dto.ExperimentDtos.ExperimentDto;
import com.aivoice.lead.dto.ExperimentDtos.VariantDto;
import com.aivoice.lead.dto.ExperimentDtos.VariantInput;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.entity.CampaignExperiment;
import com.aivoice.lead.entity.ExperimentVariant;
import com.aivoice.lead.repository.CampaignExperimentRepository;
import com.aivoice.lead.repository.CreativeAnalysisRepository;
import com.aivoice.lead.repository.ExperimentVariantRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import com.aivoice.lead.service.performance.ExperimentStats.Arm;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.aivoice.lead.service.performance.MetaPerformanceClient.InsightRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A/B experiments between two existing Meta ads (or ad sets). Campex measures and judges them; it does not create or
 * change ads — variants are built and published through Campaign Studio / Ads Manager.
 */
@Service
public class ExperimentService {

    private final CampaignExperimentRepository experiments;
    private final ExperimentVariantRepository variants;
    private final CreativeAnalysisRepository analyses;
    private final MetaAccessResolver accessResolver;
    private final MetaPerformanceClient meta;
    private final CrmOutcomeRepository crm;
    private final PerformanceService performance;
    private final ClientAccessService access;
    private final AuditService audit;
    private final Clock clock;

    public ExperimentService(CampaignExperimentRepository experiments, ExperimentVariantRepository variants,
                             CreativeAnalysisRepository analyses, MetaAccessResolver accessResolver,
                             MetaPerformanceClient meta, CrmOutcomeRepository crm, PerformanceService performance,
                             ClientAccessService access, AuditService audit, Clock clock) {
        this.experiments = experiments;
        this.variants = variants;
        this.analyses = analyses;
        this.accessResolver = accessResolver;
        this.meta = meta;
        this.crm = crm;
        this.performance = performance;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ExperimentDto create(UUID clientId, CreateExperiment request) {
        Actor actor = access.require(clientId, Permission.OPERATE);
        validateVariant(request.control());
        validateVariant(request.test());
        if (Objects.equals(target(request.control()), target(request.test()))) {
            throw new IllegalArgumentException("Control and test must be different ads or ad sets");
        }
        boolean adLevel = blank(request.control().adId()) == null ? false : true;
        if (adLevel != (blank(request.test().adId()) != null)) {
            throw new IllegalArgumentException("Both variants must be ads, or both ad sets");
        }
        BigDecimal confidence = request.confidenceLevel() == null ? new BigDecimal("0.95") : request.confidenceLevel();
        if (!List.of(0.90, 0.95, 0.99).contains(confidence.doubleValue())) {
            throw new IllegalArgumentException("confidence_level must be 0.90, 0.95 or 0.99");
        }
        if (request.startDate() != null && request.endDate() != null && request.startDate().isAfter(request.endDate())) {
            throw new IllegalArgumentException("start_date must be on or before end_date");
        }
        CampaignExperiment e = new CampaignExperiment();
        e.setClientId(clientId);
        e.setCampaignId(blank(request.campaignId()));
        e.setName(request.name().trim());
        e.setHypothesis(request.hypothesis().trim());
        e.setVariable(request.variable());
        e.setPrimaryMetric(request.primaryMetric());
        e.setMinSampleSize(request.minSampleSize());
        e.setConfidenceLevel(confidence);
        e.setStartDate(request.startDate());
        e.setEndDate(request.endDate());
        e.setCreatedBy(actor.email());
        e = experiments.saveAndFlush(e);
        variants.save(variant(e, clientId, "control", request.control()));
        variants.save(variant(e, clientId, "test", request.test()));
        audit.record(clientId, actor.email(), "campaign_experiment", e.getId(), "created",
                Map.of("name", e.getName(), "primary_metric", e.getPrimaryMetric()));
        return toDto(e, false);
    }

    private ExperimentVariant variant(CampaignExperiment e, UUID clientId, String role, VariantInput in) {
        ExperimentVariant v = new ExperimentVariant();
        v.setExperimentId(e.getId());
        v.setClientId(clientId);
        v.setRole(role);
        v.setName(in.name().trim());
        v.setAdId(blank(in.adId()));
        v.setAdsetId(blank(in.adId()) == null ? blank(in.adsetId()) : null);
        v.setDescription(blank(in.description()));
        if (blank(in.creativeAnalysisId()) != null) {
            UUID analysisId = parse(in.creativeAnalysisId(), "Creative draft");
            analyses.findById(analysisId).filter(a -> a.getClientId().equals(clientId))
                    .orElseThrow(() -> new NotFoundException("Creative draft not found"));
            v.setCreativeAnalysisId(analysisId);
        }
        return v;
    }

    @Transactional(readOnly = true)
    public List<ExperimentDto> list(UUID clientId) {
        access.read(clientId);
        return experiments.findByClientIdOrderByCreatedAtDesc(clientId).stream().map(e -> toDto(e, false)).toList();
    }

    @Transactional(readOnly = true)
    public ExperimentDto get(UUID id) {
        CampaignExperiment e = require(id);
        access.read(e.getClientId());
        return toDto(e, true);
    }

    @Transactional
    public ExperimentDto start(UUID id) {
        CampaignExperiment e = require(id);
        Actor actor = access.require(e.getClientId(), Permission.OPERATE);
        if (!"draft".equals(e.getStatus())) {
            throw new ConflictException("Only draft experiments can be started");
        }
        e.setStatus("running");
        if (e.getStartDate() == null) e.setStartDate(performance.today());
        e.setStartedAt(clock.instant());
        experiments.save(e);
        audit.record(e.getClientId(), actor.email(), "campaign_experiment", e.getId(), "started", Map.of("start_date", e.getStartDate().toString()));
        return toDto(e, false);
    }

    /** Completes the experiment and stores the statistical result. Live Meta ads are not changed. */
    @Transactional
    public ExperimentDto complete(UUID id) {
        CampaignExperiment e = require(id);
        Actor actor = access.require(e.getClientId(), Permission.OPERATE);
        if (!"running".equals(e.getStatus())) {
            throw new ConflictException("Only running experiments can be completed");
        }
        List<DataNote> notes = new ArrayList<>();
        Map<String, Arm> arms = arms(e, actor.email(), notes);
        if (arms == null) {
            throw new ConflictException("Results need Meta data: " + notes.get(0).message());
        }
        ExperimentStats.Result r = ExperimentStats.evaluate(e.getPrimaryMetric(), arms.get("control"), arms.get("test"),
                e.getMinSampleSize(), e.getConfidenceLevel().doubleValue(), true);
        e.setStatus("completed");
        e.setCompletedAt(clock.instant());
        if (e.getEndDate() == null) e.setEndDate(performance.today());
        e.setOutcome(r.status());
        e.setResult(resultMap(r, arms));
        List<ExperimentVariant> vs = variants.findByExperimentIdOrderByRoleAsc(e.getId());
        e.setWinnerVariantId(r.winner() == null ? null : vs.stream().filter(v -> v.getRole().equals(r.winner()))
                .map(ExperimentVariant::getId).findFirst().orElse(null));
        e.setResultSummary(summary(r, e));
        experiments.save(e);
        audit.record(e.getClientId(), actor.email(), "campaign_experiment", e.getId(), "completed",
                Map.of("outcome", r.status(), "winner", r.winner() == null ? "none" : r.winner(), "meta_changed", false));
        return toDto(e, false);
    }

    static String summary(ExperimentStats.Result r, CampaignExperiment e) {
        return switch (r.status()) {
            case "winner" -> r.explanation() + " The losing variant is the " + ("test".equals(r.winner()) ? "control" : "test")
                    + ". Nothing was changed in Meta: pause or scale variants in Ads Manager after review.";
            case "no_reliable_winner" -> r.explanation() + " Keep the control, or extend the test to gather more data.";
            default -> r.explanation() + " The experiment ended without enough data to judge " + e.getPrimaryMetric().replace('_', ' ') + ".";
        };
    }

    /** Per-variant Meta delivery and CRM outcomes for the experiment window; null when Meta data is unavailable. */
    Map<String, Arm> arms(CampaignExperiment e, String email, List<DataNote> notes) {
        if (e.getStartDate() == null) {
            notes.add(new DataNote("not_started", "info", "The experiment has not started."));
            return null;
        }
        LocalDate yesterday = performance.today().minusDays(1);
        LocalDate to = e.getEndDate() != null && e.getEndDate().isBefore(yesterday) ? e.getEndDate() : yesterday;
        if (to.isBefore(e.getStartDate())) {
            notes.add(new DataNote("no_complete_days", "info", "No complete day of data since the start date yet."));
            return null;
        }
        MetaAccess metaAccess = accessResolver.resolve(e.getClientId(), email);
        if (!metaAccess.available()) {
            notes.add(new DataNote("meta_unavailable", "warning", metaAccess.note()));
            return null;
        }
        List<ExperimentVariant> vs = variants.findByExperimentIdOrderByRoleAsc(e.getId());
        String level = vs.get(0).getAdId() != null ? "ad" : "adset";
        List<InsightRow> rows = new ArrayList<>();
        try {
            for (String account : metaAccess.adAccounts()) {
                rows.addAll(meta.insights(account, e.getStartDate(), to, level, false, null, metaAccess.token()));
            }
        } catch (MetaGraphException ex) {
            notes.add(new DataNote("meta_error", "warning", "Meta Insights request failed (code " + ex.graphCode() + ")."));
            return null;
        }
        ZoneId zone = performance.zone();
        Map<String, CrmOutcome> outcomes = crm.byObject(e.getClientId(), level, e.getStartDate().atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(), e.getStartDate(), to);
        Map<String, Arm> arms = new LinkedHashMap<>();
        for (ExperimentVariant v : vs) {
            String id = v.getAdId() != null ? v.getAdId() : v.getAdsetId();
            MetricSet m = new MetricSet();
            for (InsightRow r : rows) {
                if (id.equals("ad".equals(level) ? r.adId() : r.adsetId())) m.addMeta(r);
            }
            m.addCrm(outcomes.getOrDefault(id, CrmOutcome.EMPTY));
            boolean costOnCrm = List.of("cost_per_qualified_lead", "cost_per_site_visit", "cost_per_booking").contains(e.getPrimaryMetric());
            arms.put(v.getRole(), new Arm(m.spend, m.impressions, m.linkClicks, costOnCrm ? m.crmLeads : m.metaLeads,
                    m.qualifiedLeads, m.siteVisitsCompleted, m.bookings));
        }
        notes.add(new DataNote("window", "info", "Measured " + e.getStartDate() + " to " + to + " (Meta attribution "
                + "windows fixed; CRM outcomes by first touch)."));
        return arms;
    }

    static Map<String, Object> resultMap(ExperimentStats.Result r, Map<String, Arm> arms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        m.put("winner", r.winner());
        m.put("p_value", r.pValue());
        m.put("z_score", r.zScore());
        m.put("control_value", r.controlValue());
        m.put("test_value", r.testValue());
        m.put("relative_difference", r.relativeDifference());
        m.put("sample_sufficient", r.sampleSufficient());
        m.put("explanation", r.explanation());
        m.put("control", armMap(arms.get("control")));
        m.put("test", armMap(arms.get("test")));
        return m;
    }

    static Map<String, Object> armMap(Arm a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spend", a.spend());
        m.put("impressions", a.impressions());
        m.put("link_clicks", a.linkClicks());
        m.put("leads", a.leads());
        m.put("qualified_leads", a.qualified());
        m.put("site_visits", a.visits());
        m.put("bookings", a.bookings());
        return m;
    }

    private ExperimentDto toDto(CampaignExperiment e, boolean live) {
        List<DataNote> notes = new ArrayList<>();
        Map<String, Object> result = e.getResult();
        if (live && "running".equals(e.getStatus())) {
            Map<String, Arm> arms = arms(e, access.currentActor().email(), notes);
            if (arms != null) {
                result = resultMap(ExperimentStats.evaluate(e.getPrimaryMetric(), arms.get("control"), arms.get("test"),
                        e.getMinSampleSize(), e.getConfidenceLevel().doubleValue(), false), arms);
                result.put("interim", true);
            }
        }
        Map<String, Object> resultView = result;
        List<VariantDto> vs = variants.findByExperimentIdOrderByRoleAsc(e.getId()).stream().map(v -> new VariantDto(
                v.getId().toString(), v.getRole(), v.getName(), v.getAdId(), v.getAdsetId(), v.getDescription(),
                v.getCreativeAnalysisId() == null ? null : v.getCreativeAnalysisId().toString(),
                resultView == null || !(resultView.get(v.getRole()) instanceof Map<?, ?> m) ? null : castMap(m))).toList();
        return new ExperimentDto(e.getId().toString(), e.getClientId().toString(), e.getCampaignId(), e.getName(),
                e.getHypothesis(), e.getVariable(), e.getPrimaryMetric(), e.getMinSampleSize(), e.getConfidenceLevel(),
                e.getStatus(), e.getStartDate(), e.getEndDate(), e.getOutcome(),
                e.getWinnerVariantId() == null ? null : e.getWinnerVariantId().toString(), result, e.getResultSummary(),
                e.getCreatedAt(), e.getStartedAt(), e.getCompletedAt(), vs, notes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private CampaignExperiment require(UUID id) {
        return experiments.findById(id).orElseThrow(() -> new NotFoundException("Experiment not found"));
    }

    private static void validateVariant(VariantInput v) {
        if (blank(v.adId()) == null && blank(v.adsetId()) == null) {
            throw new IllegalArgumentException("Each variant needs a Meta ad id or ad set id");
        }
    }

    private static String target(VariantInput v) {
        return blank(v.adId()) != null ? "ad:" + v.adId().trim() : "adset:" + v.adsetId().trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UUID parse(String id, String what) {
        try {
            return UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(what + " not found");
        }
    }
}
