package com.aivoice.lead.service.performance;

import com.aivoice.lead.dto.PerformanceDtos.PerformanceReport;
import com.aivoice.lead.dto.RecommendationDtos.BudgetAnalysis;
import com.aivoice.lead.dto.RecommendationDtos.BudgetPolicyDto;
import com.aivoice.lead.dto.RecommendationDtos.BudgetPolicyRequest;
import com.aivoice.lead.entity.ClientBudgetPolicy;
import com.aivoice.lead.repository.ClientBudgetPolicyRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Budget guardrails per client and the budget analysis built on them. Budgets are never changed in Meta here. */
@Service
public class BudgetService {

    private final ClientBudgetPolicyRepository policies;
    private final PerformanceService performance;
    private final ClientAccessService access;
    private final AuditService audit;

    public BudgetService(ClientBudgetPolicyRepository policies, PerformanceService performance,
                         ClientAccessService access, AuditService audit) {
        this.policies = policies;
        this.performance = performance;
        this.access = access;
        this.audit = audit;
    }

    public ClientBudgetPolicy policy(UUID clientId) {
        return policies.findById(clientId).orElseGet(() -> {
            ClientBudgetPolicy defaults = new ClientBudgetPolicy();
            defaults.setClientId(clientId);
            return defaults;
        });
    }

    @Transactional(readOnly = true)
    public BudgetPolicyDto getPolicy(UUID clientId) {
        access.read(clientId);
        return toDto(policy(clientId), policies.existsById(clientId));
    }

    @Transactional
    public BudgetPolicyDto updatePolicy(UUID clientId, BudgetPolicyRequest request) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        ClientBudgetPolicy p = policy(clientId);
        Map<String, Object> before = snapshot(p);
        p.setDailySpendLimit(request.dailySpendLimit());
        p.setMonthlySpendLimit(request.monthlySpendLimit());
        if (request.maxChangePct() != null) p.setMaxChangePct(request.maxChangePct());
        if (request.minQualifiedLeads() != null) p.setMinQualifiedLeads(request.minQualifiedLeads());
        p.setTargetCostPerQualifiedLead(request.targetCostPerQualifiedLead());
        p.setTargetRoas(request.targetRoas());
        if (request.recommendationTtlHours() != null) p.setRecommendationTtlHours(request.recommendationTtlHours());
        if (p.getDailySpendLimit() != null && p.getMonthlySpendLimit() != null
                && p.getDailySpendLimit().compareTo(p.getMonthlySpendLimit()) > 0) {
            throw new IllegalArgumentException("The daily limit cannot exceed the monthly limit");
        }
        p.setUpdatedBy(actor.email());
        policies.save(p);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", snapshot(p));
        audit.record(clientId, actor.email(), "budget_policy", clientId, "updated", change);
        return toDto(p, true);
    }

    @Transactional(readOnly = true)
    public BudgetAnalysis analysis(UUID clientId, LocalDate from, LocalDate to) {
        String email = access.read(clientId).email();
        PerformanceReport report = performance.build(clientId, email, from, to, "campaign", null);
        PerformanceService.MetaData meta = performance.metaData(clientId, email, from, to, "campaign");
        ClientBudgetPolicy p = policy(clientId);
        BudgetEngine.Result result = BudgetEngine.analyze(report.rows(), meta.campaigns(), p,
                ChronoUnit.DAYS.between(from, to) + 1, from.toString(), to.toString(), report.currency());
        return new BudgetAnalysis(clientId.toString(), from, to, report.currency(), report.metaAvailable(),
                toDto(p, policies.existsById(clientId)), result.totalDailyBudget(), result.projectedDailyBudget(),
                result.lines(), result.notes(), report.dataNotes());
    }

    static BudgetPolicyDto toDto(ClientBudgetPolicy p, boolean configured) {
        return new BudgetPolicyDto(p.getDailySpendLimit(), p.getMonthlySpendLimit(), p.getMaxChangePct(),
                p.getMinQualifiedLeads(), p.getTargetCostPerQualifiedLead(), p.getTargetRoas(),
                p.getRecommendationTtlHours(), configured);
    }

    private static Map<String, Object> snapshot(ClientBudgetPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("daily_spend_limit", p.getDailySpendLimit());
        m.put("monthly_spend_limit", p.getMonthlySpendLimit());
        m.put("max_change_pct", p.getMaxChangePct());
        m.put("min_qualified_leads", p.getMinQualifiedLeads());
        m.put("target_cost_per_qualified_lead", p.getTargetCostPerQualifiedLead());
        m.put("target_roas", p.getTargetRoas());
        return m;
    }
}
