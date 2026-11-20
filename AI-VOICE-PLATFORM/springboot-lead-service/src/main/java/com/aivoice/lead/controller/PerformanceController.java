package com.aivoice.lead.controller;

import com.aivoice.lead.dto.AnomalyDtos;
import com.aivoice.lead.dto.AnomalyDtos.AnomalyDto;
import com.aivoice.lead.dto.AnomalyDtos.DetectionResult;
import com.aivoice.lead.dto.ExperimentDtos.CreateExperiment;
import com.aivoice.lead.dto.ExperimentDtos.ExperimentDto;
import com.aivoice.lead.dto.IntelligenceDtos.AudienceReport;
import com.aivoice.lead.dto.IntelligenceDtos.CreativeAnalysisDto;
import com.aivoice.lead.dto.IntelligenceDtos.CreativeAnalysisRequest;
import com.aivoice.lead.dto.IntelligenceDtos.CreativeReport;
import com.aivoice.lead.dto.PerformanceDtos.Overview;
import com.aivoice.lead.dto.PerformanceDtos.PerformanceReport;
import com.aivoice.lead.dto.PerformanceDtos.SyncResult;
import com.aivoice.lead.dto.PerformanceDtos.Trends;
import com.aivoice.lead.dto.RecommendationDtos.BudgetAnalysis;
import com.aivoice.lead.dto.RecommendationDtos.BudgetPolicyDto;
import com.aivoice.lead.dto.RecommendationDtos.BudgetPolicyRequest;
import com.aivoice.lead.dto.RecommendationDtos.GenerateRequest;
import com.aivoice.lead.dto.RecommendationDtos.GenerateResult;
import com.aivoice.lead.dto.RecommendationDtos.RecommendationDto;
import com.aivoice.lead.dto.RecommendationDtos.StatusResult;
import com.aivoice.lead.dto.RecommendationDtos.StatusUpdate;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.performance.AnomalyService;
import com.aivoice.lead.service.performance.AudienceIntelligenceService;
import com.aivoice.lead.service.performance.BudgetService;
import com.aivoice.lead.service.performance.CreativeIntelligenceService;
import com.aivoice.lead.service.performance.ExperimentService;
import com.aivoice.lead.service.performance.PerformanceAssistantService;
import com.aivoice.lead.service.performance.PerformanceAssistantService.AskResult;
import com.aivoice.lead.service.performance.PerformanceService;
import com.aivoice.lead.service.performance.PerformanceSyncService;
import com.aivoice.lead.service.performance.RecommendationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 2 performance intelligence. Read-only towards Meta: nothing here changes a live campaign. */
@RestController
public class PerformanceController {

    public record AskRequest(@NotBlank @Size(max = 1000) String question, LocalDate from, LocalDate to,
                             @Pattern(regexp = "previous_period|previous_month") String comparison) {
    }

    private final PerformanceService performance;
    private final PerformanceSyncService sync;
    private final RecommendationService recommendations;
    private final BudgetService budgets;
    private final CreativeIntelligenceService creative;
    private final AudienceIntelligenceService audience;
    private final AnomalyService anomalies;
    private final ExperimentService experiments;
    private final PerformanceAssistantService assistant;
    private final ClientAccessService access;

    public PerformanceController(PerformanceService performance, PerformanceSyncService sync,
                                 RecommendationService recommendations, BudgetService budgets,
                                 CreativeIntelligenceService creative, AudienceIntelligenceService audience,
                                 AnomalyService anomalies, ExperimentService experiments,
                                 PerformanceAssistantService assistant, ClientAccessService access) {
        this.performance = performance;
        this.sync = sync;
        this.recommendations = recommendations;
        this.budgets = budgets;
        this.creative = creative;
        this.audience = audience;
        this.anomalies = anomalies;
        this.experiments = experiments;
        this.assistant = assistant;
        this.access = access;
    }

    // ---------------------------------------------------------------- performance

    @GetMapping("/api/clients/{clientId}/performance/overview")
    public Overview overview(@PathVariable String clientId, @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false, name = "campaign_id") String campaignId) {
        LocalDate[] r = range(from, to);
        return performance.overview(Ids.uuid(clientId, "Client"), r[0], r[1], blank(campaignId));
    }

    @GetMapping("/api/clients/{clientId}/performance/campaigns")
    public PerformanceReport campaigns(@PathVariable String clientId, @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to) {
        LocalDate[] r = range(from, to);
        return performance.report(Ids.uuid(clientId, "Client"), r[0], r[1], "campaign", null);
    }

    @GetMapping("/api/clients/{clientId}/performance/adsets")
    public PerformanceReport adsets(@PathVariable String clientId, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false, name = "campaign_id") String campaignId) {
        LocalDate[] r = range(from, to);
        return performance.report(Ids.uuid(clientId, "Client"), r[0], r[1], "adset", blank(campaignId));
    }

    @GetMapping("/api/clients/{clientId}/performance/ads")
    public PerformanceReport ads(@PathVariable String clientId, @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false, name = "campaign_id") String campaignId) {
        LocalDate[] r = range(from, to);
        return performance.report(Ids.uuid(clientId, "Client"), r[0], r[1], "ad", blank(campaignId));
    }

    /** Current period vs the previous period of equal length, or vs explicit compare_from/compare_to. */
    @GetMapping("/api/clients/{clientId}/performance/compare")
    public Overview compare(@PathVariable String clientId, @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false, name = "campaign_id") String campaignId) {
        return overview(clientId, from, to, campaignId);
    }

    @GetMapping("/api/clients/{clientId}/performance/trends")
    public Trends trends(@PathVariable String clientId, @RequestParam(required = false) String from,
                         @RequestParam(required = false) String to,
                         @RequestParam(required = false, name = "campaign_id") String campaignId) {
        LocalDate[] r = range(from, to);
        return performance.trends(Ids.uuid(clientId, "Client"), r[0], r[1], blank(campaignId));
    }

    /** Syncs daily Meta snapshots for the last 28 days and re-runs anomaly detection. */
    @PostMapping("/api/clients/{clientId}/performance/refresh")
    public SyncResult refresh(@PathVariable String clientId) {
        UUID id = Ids.uuid(clientId, "Client");
        String email = access.require(id, Permission.OPERATE).email();
        LocalDate to = performance.today().minusDays(1);
        SyncResult result = sync.sync(id, email, to.minusDays(27), to);
        performance.evict(id);
        anomalies.detect(id);
        return result;
    }

    @PostMapping("/api/clients/{clientId}/performance/ask")
    public AskResult ask(@PathVariable String clientId, @Valid @RequestBody AskRequest body) {
        LocalDate to = body.to() == null ? performance.today().minusDays(1) : body.to();
        LocalDate from = body.from() == null ? to.minusDays(6) : body.from();
        return assistant.ask(Ids.uuid(clientId, "Client"), body.question().trim(), from, to,
                body.comparison() == null ? "previous_period" : body.comparison());
    }

    // ---------------------------------------------------------------- recommendations

    @GetMapping("/api/clients/{clientId}/recommendations")
    public List<RecommendationDto> recommendations(@PathVariable String clientId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String category) {
        return recommendations.list(Ids.uuid(clientId, "Client"), status, category);
    }

    @PostMapping("/api/clients/{clientId}/recommendations/generate")
    public GenerateResult generate(@PathVariable String clientId, @RequestBody(required = false) GenerateRequest body) {
        LocalDate to = body == null || body.to() == null ? performance.today().minusDays(1) : body.to();
        LocalDate from = body == null || body.from() == null ? to.minusDays(27) : body.from();
        return recommendations.generate(Ids.uuid(clientId, "Client"), from, to, body == null || !Boolean.FALSE.equals(body.explain()));
    }

    @GetMapping("/api/recommendations/{recommendationId}")
    public RecommendationDto recommendation(@PathVariable String recommendationId) {
        return recommendations.get(Ids.uuid(recommendationId, "Recommendation"));
    }

    @PatchMapping("/api/recommendations/{recommendationId}/status")
    public StatusResult recommendationStatus(@PathVariable String recommendationId, @Valid @RequestBody StatusUpdate body) {
        return recommendations.updateStatus(Ids.uuid(recommendationId, "Recommendation"), body);
    }

    // ---------------------------------------------------------------- budget

    @GetMapping("/api/clients/{clientId}/budget/analysis")
    public BudgetAnalysis budgetAnalysis(@PathVariable String clientId, @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        LocalDate[] r = range(from, to);
        return budgets.analysis(Ids.uuid(clientId, "Client"), r[0], r[1]);
    }

    @GetMapping("/api/clients/{clientId}/budget/recommendations")
    public List<RecommendationDto> budgetRecommendations(@PathVariable String clientId,
                                                         @RequestParam(required = false) String status) {
        return recommendations.list(Ids.uuid(clientId, "Client"), status, "budget");
    }

    @GetMapping("/api/clients/{clientId}/budget/policy")
    public BudgetPolicyDto budgetPolicy(@PathVariable String clientId) {
        return budgets.getPolicy(Ids.uuid(clientId, "Client"));
    }

    @PutMapping("/api/clients/{clientId}/budget/policy")
    public BudgetPolicyDto updateBudgetPolicy(@PathVariable String clientId, @Valid @RequestBody BudgetPolicyRequest body) {
        return budgets.updatePolicy(Ids.uuid(clientId, "Client"), body);
    }

    // ---------------------------------------------------------------- creative & audience

    @GetMapping("/api/clients/{clientId}/creative-performance")
    public CreativeReport creativePerformance(@PathVariable String clientId, @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        LocalDate[] r = range(from, to);
        return creative.report(Ids.uuid(clientId, "Client"), r[0], r[1]);
    }

    @PostMapping("/api/clients/{clientId}/creative-analysis")
    public CreativeAnalysisDto creativeAnalysis(@PathVariable String clientId, @Valid @RequestBody CreativeAnalysisRequest body) {
        return creative.analyze(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/clients/{clientId}/audience-performance")
    public AudienceReport audiencePerformance(@PathVariable String clientId, @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String breakdown) {
        LocalDate[] r = range(from, to);
        return audience.audience(Ids.uuid(clientId, "Client"), r[0], r[1], breakdown);
    }

    @GetMapping("/api/clients/{clientId}/placement-performance")
    public AudienceReport placementPerformance(@PathVariable String clientId, @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        LocalDate[] r = range(from, to);
        return audience.placements(Ids.uuid(clientId, "Client"), r[0], r[1]);
    }

    // ---------------------------------------------------------------- anomalies

    @GetMapping("/api/clients/{clientId}/anomalies")
    public List<AnomalyDto> anomalies(@PathVariable String clientId, @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String severity) {
        return anomalies.list(Ids.uuid(clientId, "Client"), status, severity);
    }

    @PostMapping("/api/clients/{clientId}/anomalies/detect")
    public DetectionResult detect(@PathVariable String clientId) {
        UUID id = Ids.uuid(clientId, "Client");
        access.require(id, Permission.OPERATE);
        return anomalies.detect(id);
    }

    @PatchMapping("/api/anomalies/{anomalyId}/status")
    public AnomalyDto anomalyStatus(@PathVariable String anomalyId, @Valid @RequestBody AnomalyDtos.StatusUpdate body) {
        return anomalies.updateStatus(Ids.uuid(anomalyId, "Anomaly"), body);
    }

    @PostMapping("/api/anomalies/{anomalyId}/explain")
    public AnomalyDto explainAnomaly(@PathVariable String anomalyId) {
        return anomalies.explain(Ids.uuid(anomalyId, "Anomaly"));
    }

    // ---------------------------------------------------------------- experiments

    @GetMapping("/api/clients/{clientId}/experiments")
    public List<ExperimentDto> experiments(@PathVariable String clientId) {
        return experiments.list(Ids.uuid(clientId, "Client"));
    }

    @PostMapping("/api/clients/{clientId}/experiments")
    public ExperimentDto createExperiment(@PathVariable String clientId, @Valid @RequestBody CreateExperiment body) {
        return experiments.create(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/experiments/{experimentId}")
    public ExperimentDto experiment(@PathVariable String experimentId) {
        return experiments.get(Ids.uuid(experimentId, "Experiment"));
    }

    @PostMapping("/api/experiments/{experimentId}/start")
    public ExperimentDto startExperiment(@PathVariable String experimentId) {
        return experiments.start(Ids.uuid(experimentId, "Experiment"));
    }

    @PostMapping("/api/experiments/{experimentId}/complete")
    public ExperimentDto completeExperiment(@PathVariable String experimentId) {
        return experiments.complete(Ids.uuid(experimentId, "Experiment"));
    }

    // ---------------------------------------------------------------- helpers

    /** Default window: the last 28 complete days. */
    private LocalDate[] range(String from, String to) {
        try {
            LocalDate end = to == null || to.isBlank() ? performance.today().minusDays(1) : LocalDate.parse(to);
            LocalDate start = from == null || from.isBlank() ? end.minusDays(27) : LocalDate.parse(from);
            return new LocalDate[] {start, end};
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must be YYYY-MM-DD");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
