package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.CampaignActionDtos.ActionDto;
import com.aivoice.lead.dto.CampaignActionDtos.PreviewRequest;
import com.aivoice.lead.dto.ExperimentDtos.ExperimentDto;
import com.aivoice.lead.dto.ExperimentExecutionDtos.Check;
import com.aivoice.lead.dto.ExperimentExecutionDtos.Execution;
import com.aivoice.lead.dto.ExperimentExecutionDtos.LinkedAction;
import com.aivoice.lead.dto.ExperimentExecutionDtos.Progress;
import com.aivoice.lead.dto.ExperimentExecutionDtos.VariantState;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.performance.ExperimentService;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.aivoice.lead.service.performance.PerformanceService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs Phase 2 experiments against live Meta objects without ever changing them silently:
 * readiness is checked from Meta's current state, paused variants are activated only through approved Optimization
 * actions, measurement starts when every variant is delivering, and acting on a result (pausing the loser) is a
 * prepared change that needs approval and exists only when the statistics found a reliable winner.
 */
@Service
public class ExperimentExecutionService {

    static final Set<String> CREATIVE_VARIABLES = Set.of("image", "headline", "primary_text", "cta");
    static final Set<String> BLOCKING_STATUSES = Set.of("DELETED", "ARCHIVED");
    static final Set<String> BLOCKING_EFFECTIVE = Set.of("DISAPPROVED", "WITH_ISSUES", "PENDING_BILLING_INFO");
    static final int RECOMMENDED_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final ClientAccessService access;
    private final AuditService audit;
    private final MetaAccessResolver metaAccess;
    private final MetaExecutionGateway meta;
    private final CampaignActionService actions;
    private final ExperimentService experiments;
    private final PerformanceService performance;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public ExperimentExecutionService(JdbcTemplate jdbc, ClientAccessService access, AuditService audit,
                                      MetaAccessResolver metaAccess, MetaExecutionGateway meta, CampaignActionService actions,
                                      ExperimentService experiments, PerformanceService performance,
                                      org.springframework.transaction.support.TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.metaAccess = metaAccess;
        this.meta = meta;
        this.actions = actions;
        this.experiments = experiments;
        this.performance = performance;
        this.tx = tx;
    }

    record Exp(UUID id, UUID clientId, String status, String outcome, String variable, String campaignId, LocalDate start,
               LocalDate end, int minSample, UUID winner) {
    }

    record Variant(UUID id, String role, String name, String adId, String adsetId, UUID creativeId, Integer creativeVersion) {
        String level() { return adId != null ? "ad" : "adset"; }
        String objectId() { return adId != null ? adId : adsetId; }
    }

    // ------------------------------------------------------------------------------------------------ read

    public Execution get(UUID experimentId) {
        Exp e = load(experimentId);
        Actor actor = access.read(e.clientId());
        return build(e, actor);
    }

    /** Attaches an approved creative to a variant (draft experiments only). Null clears it. */
    public Execution linkCreative(UUID experimentId, UUID variantId, String creativeAssetId) {
        Exp e = load(experimentId);
        Actor actor = access.require(e.clientId(), Permission.MANAGE);
        if (!"draft".equals(e.status())) throw new ConflictException("Creatives can only be changed before the experiment starts");
        Integer version = null;
        UUID creative = null;
        if (creativeAssetId != null && !creativeAssetId.isBlank()) {
            creative = UUID.fromString(creativeAssetId);
            Map<String, Object> c = jdbc.queryForList("SELECT approved_version, status FROM creative_assets WHERE id = ? AND client_id = ?",
                    creative, e.clientId()).stream().findFirst().orElseThrow(() -> new NotFoundException("Creative not found"));
            if (c.get("approved_version") == null || "ARCHIVED".equals(c.get("status"))) {
                throw new ConflictException("Only an approved creative can be attached to a variant");
            }
            version = (Integer) c.get("approved_version");
        }
        int n = jdbc.update("UPDATE experiment_variants SET creative_asset_id = ?, creative_version = ? WHERE id = ? AND experiment_id = ?",
                creative, version, variantId, experimentId);
        if (n == 0) throw new NotFoundException("Variant not found");
        UUID linked = creative;
        Integer linkedVersion = version;
        tx.executeWithoutResult(s -> audit.record(e.clientId(), actor.email(), "campaign_experiment", experimentId, "variant_creative_linked",
                java.util.Collections.unmodifiableMap(new java.util.HashMap<>(java.util.Map.of("variant", variantId.toString(),
                        "creative", linked == null ? "none" : linked.toString(), "version", linkedVersion == null ? 0 : linkedVersion)))));
        return build(e, actor);
    }

    // ------------------------------------------------------------------------------------------------ launch

    /**
     * Idempotent. If every variant is already delivering, measurement starts (Phase 2 start). Otherwise each paused
     * variant gets a "resume" Optimization action that someone must approve and apply; call again afterwards.
     */
    public Execution launch(UUID experimentId) {
        Exp e = load(experimentId);
        Actor actor = access.require(e.clientId(), Permission.MANAGE);
        if (!"draft".equals(e.status())) throw new ConflictException("This experiment has already started");
        Execution current = build(e, actor);
        if (!current.ready()) {
            throw new ConflictException("Not ready to launch: " + current.checks().stream()
                    .filter(c -> "error".equals(c.severity())).map(Check::message).findFirst().orElse("see the readiness checks"));
        }
        boolean allActive = current.variants().stream().allMatch(v -> "ACTIVE".equals(v.status()));
        if (allActive) {
            ExperimentDto started = experiments.start(experimentId);
            tx.executeWithoutResult(s -> audit.record(e.clientId(), actor.email(), "campaign_experiment", experimentId, "launched",
                    Map.of("start_date", String.valueOf(started.startDate()))));
            return build(load(experimentId), actor);
        }
        Set<String> pending = openActivationObjects(experimentId);
        for (VariantState v : current.variants()) {
            if ("ACTIVE".equals(v.status()) || pending.contains(v.objectId())) continue;
            ActionDto prepared = actions.preview(e.clientId(), new PreviewRequest(null, "resume", v.level(), v.objectId(), null,
                    "Launch experiment variant “" + v.name() + "” (" + v.role() + ")"));
            jdbc.update("INSERT INTO experiment_executions (id, experiment_id, client_id, variant_id, purpose, action_id, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, 'activate', ?, ?, ?)", UUID.randomUUID(), experimentId, e.clientId(),
                    UUID.fromString(v.variantId()), UUID.fromString(prepared.id()), actor.email(), now());
        }
        return build(e, actor);
    }

    /** After a reliable winner: prepare (not apply) a pause of the losing variant. */
    public Execution followUp(UUID experimentId) {
        Exp e = load(experimentId);
        Actor actor = access.require(e.clientId(), Permission.MANAGE);
        if (!"completed".equals(e.status())) throw new ConflictException("Complete the experiment first");
        if (!"winner".equals(e.outcome()) || e.winner() == null) {
            throw new ConflictException("There is no reliable winner, so no live ad will be replaced. Keep the control or extend the test.");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM experiment_executions WHERE experiment_id = ? AND purpose = 'pause_loser'",
                Integer.class, experimentId) > 0) {
            throw new ConflictException("A pause of the losing variant was already prepared");
        }
        Variant loser = variants(experimentId).stream().filter(v -> !v.id().equals(e.winner())).findFirst()
                .orElseThrow(() -> new ConflictException("Losing variant not found"));
        ActionDto prepared = actions.preview(e.clientId(), new PreviewRequest(null, "pause", loser.level(), loser.objectId(), null,
                "Experiment result: the " + loser.role() + " variant “" + loser.name() + "” lost (statistically reliable)."));
        jdbc.update("INSERT INTO experiment_executions (id, experiment_id, client_id, variant_id, purpose, action_id, created_by, created_at) "
                + "VALUES (?, ?, ?, ?, 'pause_loser', ?, ?, ?)", UUID.randomUUID(), experimentId, e.clientId(), loser.id(),
                UUID.fromString(prepared.id()), actor.email(), now());
        return build(e, actor);
    }

    // ------------------------------------------------------------------------------------------------ build

    private Execution build(Exp e, Actor actor) {
        List<Variant> vs = variants(e.id());
        List<Check> checks = new ArrayList<>();
        List<VariantState> states = new ArrayList<>();
        MetaAccess token = metaAccess.resolve(e.clientId(), actor.email());
        Set<String> mapped = token.available() ? new HashSet<>(token.adAccounts()) : Set.of();
        if (!token.available()) checks.add(new Check("meta", "error", token.note(), null));
        for (Variant v : vs) {
            Map<String, Object> state = Map.of();
            if (token.available()) {
                try {
                    state = meta.objectState(token.token(), v.level(), v.objectId());
                } catch (GatewayException ex) {
                    checks.add(new Check("meta_read", "error", "Could not read the " + v.role() + " variant from Meta: " + ex.getMessage(), v.role()));
                }
            }
            String creativeName = v.creativeId() == null ? null
                    : jdbc.queryForList("SELECT name FROM creative_assets WHERE id = ?", String.class, v.creativeId()).stream().findFirst().orElse(null);
            states.add(new VariantState(v.id().toString(), v.role(), v.name(), v.level(), v.objectId(), text(state.get("name")),
                    text(state.get("status")), text(state.get("effective_status")), text(state.get("campaign_id")),
                    v.creativeId() == null ? null : v.creativeId().toString(), v.creativeVersion(), creativeName));
            if (!state.isEmpty()) checks.addAll(variantChecks(e, v, state, mapped));
            if (v.creativeId() != null) {
                Integer approved = jdbc.queryForList("SELECT approved_version FROM creative_assets WHERE id = ?", Integer.class, v.creativeId())
                        .stream().filter(Objects::nonNull).findFirst().orElse(null);
                if (!Objects.equals(approved, v.creativeVersion())) {
                    checks.add(new Check("creative", "error", "The " + v.role() + " variant's creative is no longer the approved version.", v.role()));
                }
            }
        }
        checks.addAll(designChecks(e, vs, states));
        boolean ready = checks.stream().noneMatch(c -> "error".equals(c.severity()));
        List<LinkedAction> linked = jdbc.query("SELECT x.purpose, x.action_id, x.created_at, v.role, a.action_type, a.status, a.verification "
                        + "FROM experiment_executions x JOIN campaign_actions a ON a.id = x.action_id LEFT JOIN experiment_variants v ON v.id = x.variant_id "
                        + "WHERE x.experiment_id = ? ORDER BY x.created_at",
                (rs, i) -> new LinkedAction(rs.getString("action_id"), rs.getString("purpose"), rs.getString("role"),
                        rs.getString("action_type"), rs.getString("status"), rs.getString("verification"),
                        rs.getTimestamp("created_at").toInstant()), e.id());
        Progress progress = progress(e);
        String phase = switch (e.status()) {
            case "draft" -> linked.stream().anyMatch(a -> "activate".equals(a.purpose())) ? "awaiting_activation" : "setup";
            case "running" -> "measuring";
            case "completed" -> "completed";
            default -> e.status();
        };
        List<String> allowed = new ArrayList<>();
        if ("draft".equals(e.status())) allowed.add("launch");
        if ("completed".equals(e.status()) && "winner".equals(e.outcome()) && linked.stream().noneMatch(a -> "pause_loser".equals(a.purpose()))) {
            allowed.add("follow_up");
        }
        return new Execution(e.id().toString(), e.status(), e.outcome(), phase, ready, checks, states, linked, progress, allowed);
    }

    static List<Check> variantChecks(Exp e, Variant v, Map<String, Object> state, Set<String> mapped) {
        List<Check> out = new ArrayList<>();
        String account = state.get("account_id") == null ? null : "act_" + String.valueOf(state.get("account_id")).replaceFirst("^act_", "");
        if (account == null || !mapped.contains(account)) {
            out.add(new Check("account", "error", "The " + v.role() + " variant is not in an ad account mapped to this client.", v.role()));
        }
        String status = text(state.get("status"));
        String effective = text(state.get("effective_status"));
        if (status != null && BLOCKING_STATUSES.contains(status)) {
            out.add(new Check("status", "error", "The " + v.role() + " variant is " + status.toLowerCase() + " in Meta.", v.role()));
        }
        if (effective != null && BLOCKING_EFFECTIVE.contains(effective)) {
            out.add(new Check("delivery", "error", "Meta reports the " + v.role() + " variant as " + effective.replace('_', ' ').toLowerCase() + ".", v.role()));
        }
        if ("PAUSED".equals(status)) {
            out.add(new Check("paused", "info", "The " + v.role() + " variant is paused; launching prepares an approved resume.", v.role()));
        }
        if (e.campaignId() != null && state.get("campaign_id") != null && !e.campaignId().equals(text(state.get("campaign_id")))) {
            out.add(new Check("campaign", "error", "The " + v.role() + " variant is not in the experiment's campaign " + e.campaignId() + ".", v.role()));
        }
        return out;
    }

    static List<Check> designChecks(Exp e, List<Variant> vs, List<VariantState> states) {
        List<Check> out = new ArrayList<>();
        if (vs.size() != 2) out.add(new Check("variants", "error", "An experiment needs exactly one control and one test variant.", null));
        if (vs.stream().map(Variant::level).distinct().count() > 1) {
            out.add(new Check("level", "error", "Both variants must be ads, or both ad sets.", null));
        }
        if (CREATIVE_VARIABLES.contains(e.variable()) && vs.stream().anyMatch(v -> v.adId() == null)) {
            out.add(new Check("level", "error", "Creative tests (" + e.variable().replace('_', ' ') + ") compare ads, not ad sets.", null));
        }
        if ("audience".equals(e.variable()) && vs.stream().anyMatch(v -> v.adsetId() == null || v.adId() != null)) {
            out.add(new Check("level", "warning", "Audience tests are usually run between ad sets.", null));
        }
        if (vs.size() == 2 && Objects.equals(vs.get(0).objectId(), vs.get(1).objectId())) {
            out.add(new Check("same_object", "error", "Control and test point at the same Meta object.", null));
        }
        Set<String> campaigns = new HashSet<>();
        for (VariantState s : states) if (s.campaignId() != null) campaigns.add(s.campaignId());
        if (campaigns.size() > 1) {
            out.add(new Check("campaigns", "warning", "Variants are in different campaigns, so budgets and delivery may differ; results are less reliable.", null));
        }
        if (e.end() == null) {
            out.add(new Check("duration", "warning", "No end date: set a planned duration so the test is not stopped early.", null));
        } else if (e.start() != null && ChronoUnit.DAYS.between(e.start(), e.end()) + 1 < RECOMMENDED_DAYS) {
            out.add(new Check("duration", "warning", "Shorter than " + RECOMMENDED_DAYS + " days; weekday effects can skew the result.", null));
        }
        if (e.minSample() < 100) {
            out.add(new Check("sample", "warning", "A minimum sample under 100 rarely gives a reliable result.", null));
        }
        return out;
    }

    private Progress progress(Exp e) {
        LocalDate today = performance.today();
        Long elapsed = e.start() == null ? null : Math.max(0, ChronoUnit.DAYS.between(e.start(), today));
        Long planned = e.start() == null || e.end() == null ? null : ChronoUnit.DAYS.between(e.start(), e.end()) + 1;
        Map<String, Object> interim = null;
        boolean sampleReached = false;
        if ("running".equals(e.status()) || "completed".equals(e.status())) {
            ExperimentDto dto = experiments.get(e.id());
            interim = dto.result();
            sampleReached = interim != null && Boolean.TRUE.equals(interim.get("sample_sufficient"));
        }
        boolean durationReached = planned != null && elapsed != null && elapsed >= planned;
        String guidance = switch (e.status()) {
            case "draft" -> "Check readiness, then launch. Paused variants are resumed only after approval.";
            case "running" -> sampleReached && (durationReached || planned == null)
                    ? "Enough data and time: you can complete the experiment. A winner is declared only if the difference is statistically reliable."
                    : "Keep it running" + (!sampleReached ? " until each variant reaches the minimum sample" : "")
                    + (!durationReached && planned != null ? (sampleReached ? " until" : " and") + " the planned end date" : "")
                    + ". Completing early usually ends with no reliable winner.";
            case "completed" -> "winner".equals(e.outcome())
                    ? "A reliable winner was found. Live ads are not changed automatically; prepare a pause of the losing variant if you agree."
                    : "No reliable winner: keep the control or run a longer test. Nothing will be replaced.";
            default -> null;
        };
        return new Progress(e.start(), e.end(), elapsed, planned, e.minSample(), interim, sampleReached, durationReached, guidance);
    }

    private Set<String> openActivationObjects(UUID experimentId) {
        return new HashSet<>(jdbc.queryForList("SELECT a.object_id FROM experiment_executions x JOIN campaign_actions a ON a.id = x.action_id "
                + "WHERE x.experiment_id = ? AND x.purpose = 'activate' AND a.status IN ('PROPOSED', 'APPROVED', 'EXECUTING')", String.class, experimentId));
    }

    private Exp load(UUID id) {
        return jdbc.query("SELECT * FROM campaign_experiments WHERE id = ?", (rs, i) -> new Exp(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("client_id")), rs.getString("status"), rs.getString("outcome"), rs.getString("variable"),
                        rs.getString("campaign_id"), date(rs.getDate("start_date")), date(rs.getDate("end_date")), rs.getInt("min_sample_size"),
                        rs.getString("winner_variant_id") == null ? null : UUID.fromString(rs.getString("winner_variant_id"))), id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Experiment not found"));
    }

    private List<Variant> variants(UUID experimentId) {
        return jdbc.query("SELECT * FROM experiment_variants WHERE experiment_id = ? ORDER BY role", (rs, i) -> new Variant(
                UUID.fromString(rs.getString("id")), rs.getString("role"), rs.getString("name"), rs.getString("ad_id"), rs.getString("adset_id"),
                rs.getString("creative_asset_id") == null ? null : UUID.fromString(rs.getString("creative_asset_id")),
                (Integer) rs.getObject("creative_version")), experimentId);
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
