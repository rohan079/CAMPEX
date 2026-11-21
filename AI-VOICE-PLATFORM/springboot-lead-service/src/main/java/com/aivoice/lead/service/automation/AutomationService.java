package com.aivoice.lead.service.automation;

import com.aivoice.lead.config.AutomationProperties;
import com.aivoice.lead.dto.AutomationDtos.ExecutionDto;
import com.aivoice.lead.dto.AutomationDtos.RuleDto;
import com.aivoice.lead.dto.AutomationDtos.RuleInput;
import com.aivoice.lead.dto.AutomationDtos.Settings;
import com.aivoice.lead.dto.CampaignActionDtos.ActionDto;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.automation.RuleEvaluator.CampaignStatus;
import com.aivoice.lead.service.automation.RuleEvaluator.Day;
import com.aivoice.lead.service.automation.RuleEvaluator.Finding;
import com.aivoice.lead.service.automation.RuleEvaluator.Outcome;
import com.aivoice.lead.service.execution.CampaignActionService;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.execution.NotificationService;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Automation rules. Evaluation is deterministic ({@link RuleEvaluator}); what happens next depends on the rule's
 * level: MONITOR records, RECOMMEND notifies, APPROVAL_REQUIRED prepares a pause that a person must approve, and
 * PREAPPROVED (server flag, off by default) may pause within a daily cap. No level can raise a budget or activate.
 */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private static final Set<String> ACTIONABLE = Set.of("ctr_decline", "cpl_above_threshold");
    private static final String ENTITY = "automation_rule";

    private final JdbcTemplate jdbc;
    private final ClientAccessService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final CampaignActionService actions;
    private final MetaAccessResolver metaAccess;
    private final MetaExecutionGateway meta;
    private final AutomationProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public AutomationService(JdbcTemplate jdbc, ClientAccessService access, AuditService audit, NotificationService notifications,
                             CampaignActionService actions, MetaAccessResolver metaAccess, MetaExecutionGateway meta,
                             AutomationProperties props, ObjectMapper json, Clock clock,
                             org.springframework.transaction.support.TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.actions = actions;
        this.metaAccess = metaAccess;
        this.meta = meta;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.tx = tx;
    }

    public Settings settings() {
        return new Settings(props.enabled(), props.preapprovedActionsEnabled());
    }

    // ------------------------------------------------------------------------------------------------ rules CRUD

    public RuleDto create(UUID clientId, RuleInput in) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        Normalized r = normalize(in, null);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            Timestamp now = now();
            jdbc.update("INSERT INTO automation_rules (id, client_id, name, rule_type, campaign_id, threshold, observation_days, "
                            + "min_sample, frequency, severity, notify, execution_level, proposed_action, max_auto_actions_per_day, "
                            + "cooldown_hours, enabled, created_by, updated_by, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, clientId, r.name, r.type, r.campaignId, r.threshold, r.days, r.minSample, r.frequency, r.severity,
                    r.notifyTeam, r.level, r.action, r.maxAuto, r.cooldown, r.enabled, actor.email(), actor.email(), now, now);
            audit.record(clientId, actor.email(), ENTITY, id, "created", Map.of("type", r.type, "level", r.level));
        });
        return rule(id);
    }

    public RuleDto update(UUID id, RuleInput in) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        Normalized r = normalize(in, id);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE automation_rules SET name = ?, rule_type = ?, campaign_id = ?, threshold = ?, observation_days = ?, "
                            + "min_sample = ?, frequency = ?, severity = ?, notify = ?, execution_level = ?, proposed_action = ?, "
                            + "max_auto_actions_per_day = ?, cooldown_hours = ?, enabled = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                    r.name, r.type, r.campaignId, r.threshold, r.days, r.minSample, r.frequency, r.severity, r.notifyTeam, r.level,
                    r.action, r.maxAuto, r.cooldown, r.enabled, actor.email(), now(), id);
            audit.record(clientId, actor.email(), ENTITY, id, "updated", Map.of("level", r.level, "enabled", r.enabled));
        });
        return rule(id);
    }

    public RuleDto setEnabled(UUID id, boolean enabled) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        if (enabled) {
            String level = jdbc.queryForObject("SELECT execution_level FROM automation_rules WHERE id = ?", String.class, id);
            if ("PREAPPROVED".equals(level) && !props.preapprovedActionsEnabled()) {
                throw new ConflictException("Pre-approved automation is disabled on this server; change the rule's level first");
            }
        }
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE automation_rules SET enabled = ?, updated_by = ?, updated_at = ? WHERE id = ?", enabled, actor.email(), now(), id);
            audit.record(clientId, actor.email(), ENTITY, id, enabled ? "enabled" : "disabled", Map.of());
        });
        return rule(id);
    }

    public List<RuleDto> list(UUID clientId) {
        access.read(clientId);
        return jdbc.query(RULE_SQL + " WHERE r.client_id = ? ORDER BY r.created_at", (rs, i) -> ruleDto(rs), clientId);
    }

    public List<ExecutionDto> executions(UUID clientId, String ruleId) {
        access.read(clientId);
        UUID rule = ruleId == null || ruleId.isBlank() ? null : UUID.fromString(ruleId);
        return jdbc.query("SELECT e.*, r.name AS rule_name FROM automation_executions e JOIN automation_rules r ON r.id = e.rule_id "
                        + "WHERE e.client_id = ?" + (rule == null ? "" : " AND e.rule_id = ?") + " ORDER BY e.evaluated_at DESC LIMIT 200",
                (rs, i) -> new ExecutionDto(rs.getString("id"), rs.getString("rule_id"), rs.getString("rule_name"),
                        rs.getString("object_id"), rs.getString("object_name"), rs.getString("outcome"), rs.getString("result"),
                        readMap(rs.getString("observed")), rs.getString("message"), rs.getString("action_id"),
                        rs.getString("triggered_by"), rs.getTimestamp("evaluated_at").toInstant()),
                rule == null ? new Object[]{clientId} : new Object[]{clientId, rule});
    }

    /** "Run now" from the dashboard. */
    public List<ExecutionDto> runNow(UUID ruleId) {
        UUID clientId = clientOf(ruleId);
        Actor actor = access.require(clientId, Permission.MANAGE);
        evaluate(ruleId, actor.email());
        return jdbc.query("SELECT e.*, r.name AS rule_name FROM automation_executions e JOIN automation_rules r ON r.id = e.rule_id "
                        + "WHERE e.rule_id = ? AND e.evaluated_at >= ? ORDER BY e.evaluated_at DESC",
                (rs, i) -> new ExecutionDto(rs.getString("id"), rs.getString("rule_id"), rs.getString("rule_name"),
                        rs.getString("object_id"), rs.getString("object_name"), rs.getString("outcome"), rs.getString("result"),
                        readMap(rs.getString("observed")), rs.getString("message"), rs.getString("action_id"),
                        rs.getString("triggered_by"), rs.getTimestamp("evaluated_at").toInstant()),
                ruleId, Timestamp.from(clock.instant().minusSeconds(60)));
    }

    // ------------------------------------------------------------------------------------------------ evaluation

    /** Rules due now (scheduler). */
    public List<UUID> dueRules() {
        Instant now = clock.instant();
        return jdbc.queryForList("SELECT id FROM automation_rules WHERE enabled AND (last_evaluated_at IS NULL OR "
                        + "(frequency = 'hourly' AND last_evaluated_at < ?) OR (frequency = 'daily' AND last_evaluated_at < ?))",
                UUID.class, Timestamp.from(now.minus(Duration.ofMinutes(55))), Timestamp.from(now.minus(Duration.ofHours(23))));
    }

    public void evaluate(UUID ruleId, String triggeredBy) {
        Map<String, Object> rule = jdbc.queryForMap("SELECT * FROM automation_rules WHERE id = ?", ruleId);
        UUID clientId = (UUID) rule.get("client_id");
        String type = (String) rule.get("rule_type");
        int days = (Integer) rule.get("observation_days");
        RuleEvaluator.Rule r = new RuleEvaluator.Rule(type, (String) rule.get("campaign_id"), (BigDecimal) rule.get("threshold"),
                days, (Integer) rule.get("min_sample"));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate latest = today.minusDays(1);
        LocalDate lastComplete = today.minusDays(2);
        List<Finding> findings;
        try {
            List<Day> data = "delivery_issue".equals(type) ? List.of() : snapshots(clientId, lastComplete.minusDays(2L * days), latest);
            List<CampaignStatus> statuses = "delivery_issue".equals(type) ? statuses(clientId) : List.of();
            findings = RuleEvaluator.evaluate(r, data, statuses, lastComplete, latest);
        } catch (RuntimeException e) {
            record(ruleId, clientId, null, null, "error", null, Map.of(), "Evaluation failed: " + safe(e), null, triggeredBy);
            touch(ruleId);
            return;
        }
        boolean anyTriggered = false;
        for (Finding f : findings) {
            if (f.outcome() == Outcome.TRIGGERED) {
                anyTriggered = true;
                handleTriggered(rule, clientId, f, triggeredBy);
            } else if (f.outcome() == Outcome.INSUFFICIENT_DATA) {
                record(ruleId, clientId, f.objectId(), f.objectName(), "insufficient_data", "recorded", f.observed(), f.message(), null, triggeredBy);
            }
        }
        if (!anyTriggered && findings.stream().noneMatch(f -> f.outcome() == Outcome.INSUFFICIENT_DATA)) {
            record(ruleId, clientId, null, null, "not_triggered", "recorded", Map.of("checked", findings.size()),
                    findings.isEmpty() ? "No data in the observation window." : "Checked " + findings.size() + "; nothing crossed the threshold.",
                    null, triggeredBy);
        }
        touch(ruleId);
    }

    private void handleTriggered(Map<String, Object> rule, UUID clientId, Finding f, String triggeredBy) {
        UUID ruleId = (UUID) rule.get("id");
        String name = (String) rule.get("name");
        int cooldown = (Integer) rule.get("cooldown_hours");
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM automation_executions WHERE rule_id = ? AND "
                        + "COALESCE(object_id, '') = COALESCE(?, '') AND outcome = 'triggered' AND evaluated_at > ?",
                Integer.class, ruleId, f.objectId(), Timestamp.from(clock.instant().minus(Duration.ofHours(cooldown))));
        if (recent != null && recent > 0) {
            record(ruleId, clientId, f.objectId(), f.objectName(), "cooldown", "recorded", f.observed(),
                    "Still triggered; already reported within " + cooldown + " hours.", null, triggeredBy);
            return;
        }
        String level = (String) rule.get("execution_level");
        String actor = "automation:" + name;
        String message = f.message();
        switch (level) {
            case "MONITOR" -> record(ruleId, clientId, f.objectId(), f.objectName(), "triggered", "recorded", f.observed(), message, null, triggeredBy);
            case "RECOMMEND" -> {
                notifyRule(rule, clientId, f);
                record(ruleId, clientId, f.objectId(), f.objectName(), "triggered", Boolean.TRUE.equals(rule.get("notify")) ? "notified" : "recorded",
                        f.observed(), message, null, triggeredBy);
            }
            default -> {
                boolean auto = "PREAPPROVED".equals(level) && props.preapprovedActionsEnabled() && underDailyCap(rule);
                String note = "PREAPPROVED".equals(level) && !auto
                        ? (props.preapprovedActionsEnabled() ? " Daily automatic-action cap reached; prepared for approval instead."
                        : " Pre-approved automation is disabled on this server; prepared for approval instead.") : "";
                try {
                    ActionDto prepared = actions.previewAsSystem(clientId, actor, "pause", "campaign", f.objectId(),
                            "Rule “" + name + "”: " + message);
                    if (auto) {
                        ActionDto done = actions.approveAndExecuteAsSystem(UUID.fromString(prepared.id()), actor,
                                "Pre-approved by rule “" + name + "”");
                        record(ruleId, clientId, f.objectId(), f.objectName(), "triggered", "action_applied", f.observed(),
                                message + " Paused automatically (" + done.status().toLowerCase() + ", " + done.verification() + ").",
                                UUID.fromString(prepared.id()), triggeredBy);
                        notifications.notifyClientManagers(clientId, null, "automation_executed", "warning",
                                "Rule paused " + (f.objectName() == null ? f.objectId() : f.objectName()), message, "campaign_action", prepared.id());
                    } else {
                        record(ruleId, clientId, f.objectId(), f.objectName(), "triggered", "action_prepared", f.observed(),
                                message + " A pause is waiting for approval in Optimization actions." + note, UUID.fromString(prepared.id()), triggeredBy);
                    }
                } catch (RuntimeException e) {
                    notifyRule(rule, clientId, f);
                    record(ruleId, clientId, f.objectId(), f.objectName(), "triggered", "action_blocked", f.observed(),
                            message + " No change prepared: " + safe(e), null, triggeredBy);
                }
            }
        }
    }

    private boolean underDailyCap(Map<String, Object> rule) {
        Integer today = jdbc.queryForObject("SELECT count(*) FROM automation_executions WHERE rule_id = ? AND result = 'action_applied' "
                + "AND evaluated_at > ?", Integer.class, rule.get("id"), Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
        return today == null || today < (Integer) rule.get("max_auto_actions_per_day");
    }

    private void notifyRule(Map<String, Object> rule, UUID clientId, Finding f) {
        if (!Boolean.TRUE.equals(rule.get("notify"))) return;
        String type = switch ((String) rule.get("rule_type")) {
            case "spend_over_limit" -> "budget_threshold_reached";
            case "cpl_above_threshold" -> "cpl_increased";
            case "delivery_issue" -> "campaign_delivery_issue";
            default -> "optimization_recommendation_available";
        };
        notifications.notifyClientManagers(clientId, null, type, (String) rule.get("severity"),
                rule.get("name") + (f.objectName() == null ? "" : ": " + f.objectName()), f.message(), ENTITY, rule.get("id"));
    }

    private List<Day> snapshots(UUID clientId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT object_id, name, day, spend, impressions, link_clicks, meta_leads FROM performance_snapshots "
                        + "WHERE client_id = ? AND level = 'campaign' AND day BETWEEN ? AND ? ORDER BY day",
                (rs, i) -> new Day(rs.getString("object_id"), rs.getString("name"), rs.getDate("day").toLocalDate(),
                        rs.getBigDecimal("spend"), rs.getLong("impressions"), rs.getLong("link_clicks"), rs.getLong("meta_leads")),
                clientId, Date.valueOf(from), Date.valueOf(to));
    }

    private List<CampaignStatus> statuses(UUID clientId) {
        MetaAccess resolved = metaAccess.resolve(clientId, null);
        if (!resolved.available()) throw new IllegalStateException(resolved.note());
        List<CampaignStatus> out = new ArrayList<>();
        for (String account : resolved.adAccounts()) {
            try {
                out.addAll(meta.campaignStatuses(resolved.token(), account));
            } catch (GatewayException e) {
                throw new IllegalStateException("Meta status unavailable for " + account + ": " + e.getMessage());
            }
        }
        return out;
    }

    private void record(UUID ruleId, UUID clientId, String objectId, String objectName, String outcome, String result,
                        Map<String, Object> observed, String message, UUID actionId, String triggeredBy) {
        jdbc.update("INSERT INTO automation_executions (id, rule_id, client_id, object_id, object_name, outcome, result, observed, "
                        + "message, action_id, triggered_by, evaluated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                UUID.randomUUID(), ruleId, clientId, objectId, objectName, outcome, result, write(observed),
                message == null ? null : message.substring(0, Math.min(message.length(), 2000)), actionId, triggeredBy, now());
    }

    private void touch(UUID ruleId) {
        jdbc.update("UPDATE automation_rules SET last_evaluated_at = ? WHERE id = ?", now(), ruleId);
    }

    // ------------------------------------------------------------------------------------------------ validation

    private record Normalized(String name, String type, String campaignId, BigDecimal threshold, int days, int minSample,
                              String frequency, String severity, boolean notifyTeam, String level, String action, int maxAuto,
                              int cooldown, boolean enabled) {
    }

    private Normalized normalize(RuleInput in, UUID existing) {
        String type = in.ruleType();
        String level = in.executionLevel() == null ? "MONITOR" : in.executionLevel();
        if ("PREAPPROVED".equals(level) && !props.preapprovedActionsEnabled()) {
            throw new IllegalArgumentException("Pre-approved automation (level 4) is disabled on this server");
        }
        boolean changes = Set.of("APPROVAL_REQUIRED", "PREAPPROVED").contains(level);
        if (changes && !ACTIONABLE.contains(type)) {
            throw new IllegalArgumentException("Only CTR-decline and cost-per-lead rules can prepare a pause");
        }
        if (changes && (in.campaignId() == null && "PREAPPROVED".equals(level))) {
            throw new IllegalArgumentException("Pre-approved rules must be scoped to one campaign");
        }
        String action = changes ? "pause" : null;
        BigDecimal threshold = in.threshold();
        switch (type) {
            case "spend_over_limit", "cpl_above_threshold" -> {
                if (threshold == null || threshold.signum() <= 0) throw new IllegalArgumentException("Set a positive threshold");
            }
            case "ctr_decline" -> {
                if (threshold == null) threshold = BigDecimal.valueOf(30);
                if (threshold.compareTo(BigDecimal.ONE) < 0 || threshold.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new IllegalArgumentException("CTR decline threshold must be 1-100 (%)");
                }
            }
            default -> threshold = null;
        }
        int minSample = in.minSample() != null ? in.minSample()
                : "ctr_decline".equals(type) ? 1000 : "cpl_above_threshold".equals(type) ? 5 : 0;
        return new Normalized(in.name().trim(), type, in.campaignId(), threshold,
                in.observationDays() == null ? 7 : in.observationDays(), minSample,
                in.frequency() == null ? "daily" : in.frequency(), in.severity() == null ? "warning" : in.severity(),
                in.notifyTeam() == null || in.notifyTeam(), level, action,
                in.maxAutoActionsPerDay() == null ? 1 : in.maxAutoActionsPerDay(),
                in.cooldownHours() == null ? 24 : in.cooldownHours(), in.enabled() == null || in.enabled());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final String RULE_SQL = "SELECT r.*, (SELECT e.outcome FROM automation_executions e WHERE e.rule_id = r.id "
            + "ORDER BY e.evaluated_at DESC LIMIT 1) AS last_outcome FROM automation_rules r";

    private RuleDto rule(UUID id) {
        return jdbc.query(RULE_SQL + " WHERE r.id = ?", (rs, i) -> ruleDto(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Rule not found"));
    }

    private RuleDto ruleDto(ResultSet rs) throws SQLException {
        Timestamp last = rs.getTimestamp("last_evaluated_at");
        return new RuleDto(rs.getString("id"), rs.getString("client_id"), rs.getString("name"), rs.getString("rule_type"),
                rs.getString("campaign_id"), rs.getBigDecimal("threshold"), rs.getInt("observation_days"), rs.getInt("min_sample"),
                rs.getString("frequency"), rs.getString("severity"), rs.getBoolean("notify"), rs.getString("execution_level"),
                rs.getString("proposed_action"), rs.getInt("max_auto_actions_per_day"), rs.getInt("cooldown_hours"),
                rs.getBoolean("enabled"), last == null ? null : last.toInstant(), rs.getString("last_outcome"),
                rs.getString("created_by"), rs.getTimestamp("updated_at").toInstant());
    }

    private UUID clientOf(UUID ruleId) {
        return jdbc.queryForList("SELECT client_id FROM automation_rules WHERE id = ?", UUID.class, ruleId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Rule not found"));
    }

    private Map<String, Object> readMap(String raw) {
        try {
            return raw == null ? Map.of() : json.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String safe(RuntimeException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.length() > 300 ? m.substring(0, 300) : m;
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
