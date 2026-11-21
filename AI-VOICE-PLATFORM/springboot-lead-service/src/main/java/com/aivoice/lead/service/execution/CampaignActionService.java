package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.CampaignActionDtos.ActionDto;
import com.aivoice.lead.dto.CampaignActionDtos.EventDto;
import com.aivoice.lead.dto.CampaignActionDtos.GuardrailCheck;
import com.aivoice.lead.dto.CampaignActionDtos.PreviewRequest;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.execution.ActionGuardrails.Policy;
import com.aivoice.lead.service.execution.ActionGuardrails.Result;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Optimization actions on live Meta objects (pause, resume, daily budget). Each one is previewed from the state Meta
 * reports now, checked by {@link ActionGuardrails}, approved by a person, executed once by Spring (a conditional
 * status claim), and verified by reading Meta again. AI never calls this; recommendations only pre-fill a preview.
 */
@Service
public class CampaignActionService {

    static final Duration PREVIEW_TTL = Duration.ofHours(24);
    private static final String ENTITY = "campaign_action";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ClientAccessService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MetaAccessResolver metaAccess;
    private final MetaExecutionGateway meta;
    private final ObjectMapper json;

    public CampaignActionService(JdbcTemplate jdbc, TransactionTemplate tx, ClientAccessService access, AuditService audit,
                                 NotificationService notifications, MetaAccessResolver metaAccess,
                                 MetaExecutionGateway meta, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.metaAccess = metaAccess;
        this.meta = meta;
        this.json = json;
    }

    private record Target(String action, String level, String objectId, Long budgetMinor, UUID recommendationId, String reason) {
    }

    public ActionDto preview(UUID clientId, PreviewRequest req) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        Target t = resolveTarget(clientId, req);
        return createPreview(clientId, actor.email(), t, token(clientId, actor), "manual");
    }

    /**
     * Automation path: a rule prepares a change. It is created PROPOSED like any other and still needs a person to
     * approve it (unless the rule is PREAPPROVED, which the automation service gates separately).
     */
    public ActionDto previewAsSystem(UUID clientId, String actorLabel, String action, String level, String objectId, String reason) {
        MetaAccess resolved = metaAccess.resolve(clientId, null);
        if (!resolved.available()) {
            throw new ConflictException(resolved.note());
        }
        return createPreview(clientId, actorLabel, new Target(action, level, objectId, null, null, reason), resolved, "automation");
    }

    /** Automation path for PREAPPROVED rules only: records the rule as the approver, then executes like a person would. */
    public ActionDto approveAndExecuteAsSystem(UUID id, String actorLabel, String note) {
        UUID clientId = clientOf(id);
        tx.executeWithoutResult(s -> {
            Map<String, Object> row = lock(id);
            requireStatus(row, Set.of("PROPOSED"), "approved");
            jdbc.update("UPDATE campaign_actions SET status = 'APPROVED', approved_by = ?, approved_at = ?, approval_note = ?, "
                    + "updated_at = ? WHERE id = ?", actorLabel, now(), note, now(), id);
            event(id, "approve", "ok", note, actorLabel);
            audit.record(clientId, actorLabel, ENTITY, id, "approved_by_rule", Map.of("note", note));
        });
        MetaAccess resolved = metaAccess.resolve(clientId, null);
        if (!resolved.available()) {
            throw new ConflictException(resolved.note());
        }
        return executeInternal(id, clientId, new Actor(actorLabel, false), resolved);
    }

    private ActionDto createPreview(UUID clientId, String actorEmail, Target t, MetaAccess token, String source) {
        Map<String, Object> before;
        try {
            before = meta.objectState(token.token(), t.level(), t.objectId());
        } catch (GatewayException e) {
            throw new ConflictException("Could not read the current state from Meta: " + e.getMessage());
        }
        Result result = ActionGuardrails.evaluate(t.action(), t.level(), before, t.budgetMinor(), Set.copyOf(token.adAccounts()), policy(clientId));
        if (!result.allowed()) {
            throw new ConflictException(result.checks().stream().filter(c -> "error".equals(c.severity()))
                    .map(ActionGuardrails.Check::message).findFirst().orElse("This change is not allowed"));
        }
        UUID id = UUID.randomUUID();
        String account = "act_" + String.valueOf(before.get("account_id")).replaceFirst("^act_", "");
        try {
            tx.executeWithoutResult(s -> {
                Timestamp now = now();
                jdbc.update("INSERT INTO campaign_actions (id, client_id, recommendation_id, source, action_type, level, object_id, "
                                + "object_name, ad_account_id, status, before_state, proposed_after, guardrails, reason, created_by, "
                                + "expires_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?::jsonb, ?::jsonb, "
                                + "?::jsonb, ?, ?, ?, ?, ?)",
                        id, clientId, t.recommendationId(), t.recommendationId() != null ? "recommendation" : source,
                        t.action(), t.level(), t.objectId(), text(before.get("name")), account, write(before),
                        write(result.proposed()), write(result.checks()), t.reason(), actorEmail,
                        Timestamp.from(Instant.now().plus(PREVIEW_TTL)), now, now);
                event(id, "preview", "ok", null, actorEmail);
                audit.record(clientId, actorEmail, ENTITY, id, "previewed",
                        Map.of("action", t.action(), "level", t.level(), "object_id", t.objectId(), "before", before,
                                "proposed", result.proposed()));
                notifications.notifyClientManagers(clientId, actorEmail, "optimization_approval_requested", "info",
                        "Approve change: " + label(t.action()) + " · " + (before.get("name") == null ? t.objectId() : before.get("name")),
                        "Review the before/after and approve or reject it.", ENTITY, id);
            });
        } catch (DuplicateKeyException e) {
            throw new ConflictException("There is already an open change for this object; finish or cancel it first");
        }
        return dto(id);
    }

    public List<ActionDto> list(UUID clientId, String status) {
        access.read(clientId);
        expireOld(clientId);
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        List<UUID> ids = jdbc.queryForList("SELECT id FROM campaign_actions WHERE client_id = ?"
                        + (filter == null ? "" : " AND status = ?") + " ORDER BY created_at DESC LIMIT 200", UUID.class,
                filter == null ? new Object[]{clientId} : new Object[]{clientId, filter});
        return ids.stream().map(this::dto).toList();
    }

    public ActionDto get(UUID id) {
        access.read(clientOf(id));
        return dto(id);
    }

    public ActionDto approve(UUID id, String note) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            Map<String, Object> row = lock(id);
            requireStatus(row, Set.of("PROPOSED"), "approved");
            requireFresh(row);
            jdbc.update("UPDATE campaign_actions SET status = 'APPROVED', approved_by = ?, approved_at = ?, approval_note = ?, "
                    + "updated_at = ? WHERE id = ?", actor.email(), now(), blankToNull(note), now(), id);
            event(id, "approve", "ok", blankToNull(note), actor.email());
            audit.record(clientId, actor.email(), ENTITY, id, "approved", Map.of("proposed", read(row.get("proposed_after"))));
            if (row.get("recommendation_id") != null) {
                jdbc.update("UPDATE performance_recommendations SET status = 'accepted', decided_by = ?, decided_at = ?, "
                                + "decision_note = COALESCE(decision_note, ?) WHERE id = ? AND status = 'pending'",
                        actor.email(), now(), "Change approved in Optimization actions", row.get("recommendation_id"));
            }
        });
        return dto(id);
    }

    public ActionDto reject(UUID id, String note) {
        return close(id, Set.of("PROPOSED", "APPROVED"), "REJECTED", "reject", note);
    }

    public ActionDto cancel(UUID id) {
        return close(id, Set.of("PROPOSED", "APPROVED", "FAILED"), "CANCELLED", "cancel", null);
    }

    private ActionDto close(UUID id, Set<String> from, String to, String event, String note) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            Map<String, Object> row = lock(id);
            requireStatus(row, from, event.equals("reject") ? "rejected" : "cancelled");
            jdbc.update("UPDATE campaign_actions SET status = ?, updated_at = ? WHERE id = ?", to, now(), id);
            event(id, event, "ok", blankToNull(note), actor.email());
            audit.record(clientId, actor.email(), ENTITY, id, event, Map.of("from", row.get("status"), "to", to));
        });
        return dto(id);
    }

    /** Runs an APPROVED action (or retries a FAILED one): re-read, apply once, verify by reading Meta again. */
    public ActionDto execute(UUID id, boolean confirm) {
        if (!confirm) {
            throw new IllegalArgumentException("Confirm the change: it modifies a live Meta object");
        }
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        return executeInternal(id, clientId, actor, token(clientId, actor));
    }

    private ActionDto executeInternal(UUID id, UUID clientId, Actor actor, MetaAccess token) {
        Map<String, Object> claimed = tx.execute(s -> {
            Map<String, Object> row = lock(id);
            requireStatus(row, Set.of("APPROVED", "FAILED"), "executed");
            if ("FAILED".equals(row.get("status")) && row.get("approved_by") == null) {
                throw new ConflictException("This change was never approved");
            }
            requireFresh(row);
            int n = jdbc.update("UPDATE campaign_actions SET status = 'EXECUTING', attempts = attempts + 1, last_error = NULL, "
                    + "executed_by = ?, updated_at = ? WHERE id = ? AND status = ?", actor.email(), now(), id, row.get("status"));
            if (n != 1) {
                throw new ConflictException("This change is already being executed");
            }
            event(id, "execute", "unknown", "Started", actor.email());
            return row;
        });
        String level = (String) claimed.get("level");
        String objectId = (String) claimed.get("object_id");
        Map<String, Object> before = read(claimed.get("before_state"));
        Map<String, Object> proposed = read(claimed.get("proposed_after"));

        // 1. Meta must still look like the preview; otherwise the approval was for a different situation.
        Map<String, Object> current;
        try {
            current = meta.objectState(token.token(), level, objectId);
        } catch (GatewayException e) {
            fail(id, clientId, actor, "Could not read Meta before applying; nothing was changed. Retry shortly.");
            return dto(id);
        }
        if (ActionGuardrails.matches(proposed, current)) {
            finish(id, clientId, actor, current, "Meta already shows the proposed values; nothing was sent.");
            return dto(id);
        }
        if (!ActionGuardrails.unchanged(proposed, before, current)) {
            fail(id, clientId, actor, "Meta changed since the preview (now " + summary(current) + "). Cancel this and preview again.");
            return dto(id);
        }

        // 2. Apply. Status and budget writes set absolute values, so a retry after an unknown outcome is safe.
        String status = proposed.get("status") == null ? null : String.valueOf(proposed.get("status"));
        Long budget = ActionGuardrails.number(proposed.get("daily_budget"));
        String applyError = null;
        try {
            meta.applyChange(token.token(), level, objectId, status, budget);
        } catch (GatewayException e) {
            applyError = e.getMessage();
        }

        // 3. Verify against what Meta reports, never against the API response alone.
        Map<String, Object> after;
        try {
            after = meta.objectState(token.token(), level, objectId);
        } catch (GatewayException e) {
            if (applyError != null) {
                fail(id, clientId, actor, "Meta returned an error (" + applyError + ") and the result could not be checked. Retry checks first.");
            } else {
                tx.executeWithoutResult(s -> {
                    jdbc.update("UPDATE campaign_actions SET status = 'SUCCEEDED', verification = 'unverified', executed_at = ?, "
                            + "updated_at = ? WHERE id = ?", now(), now(), id);
                    event(id, "verify", "unknown", "Applied, but Meta could not be read back to verify.", actor.email());
                    audit.record(clientId, actor.email(), ENTITY, id, "executed_unverified", Map.of());
                });
            }
            return dto(id);
        }
        if (ActionGuardrails.matches(proposed, after)) {
            finish(id, clientId, actor, after, applyError == null ? null : "Meta reported an error but shows the change applied.");
        } else if (applyError != null) {
            fail(id, clientId, actor, "Meta rejected the change: " + applyError);
        } else {
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE campaign_actions SET status = 'SUCCEEDED', verification = 'mismatch', actual_after = ?::jsonb, "
                        + "executed_at = ?, verified_at = ?, updated_at = ? WHERE id = ?", write(after), now(), now(), now(), id);
                event(id, "verify", "failed", "Meta accepted the request but reports " + summary(after), actor.email());
                audit.record(clientId, actor.email(), ENTITY, id, "executed_mismatch", Map.of("after", after));
                notifications.notifyClientManagers(clientId, actor.email(), "optimization_verify_mismatch", "warning",
                        "Check change: " + claimed.get("object_name"), "Meta does not show the expected values after the change.", ENTITY, id);
            });
        }
        return dto(id);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void finish(UUID id, UUID clientId, Actor actor, Map<String, Object> after, String note) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE campaign_actions SET status = 'SUCCEEDED', verification = 'verified', actual_after = ?::jsonb, "
                    + "executed_at = ?, verified_at = ?, updated_at = ? WHERE id = ?", write(after), now(), now(), now(), id);
            event(id, "verify", "ok", note == null ? "Meta now reports " + summary(after) : note, actor.email());
            audit.record(clientId, actor.email(), ENTITY, id, "executed", Map.of("after", after));
            notifications.notifyClientManagers(clientId, actor.email(), "optimization_executed", "info",
                    "Change applied: " + nameOf(id), "Verified in Meta: " + summary(after), ENTITY, id);
        });
    }

    private void fail(UUID id, UUID clientId, Actor actor, String error) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE campaign_actions SET status = 'FAILED', last_error = ?, updated_at = ? WHERE id = ?", error, now(), id);
            event(id, "execute", "failed", error, actor.email());
            audit.record(clientId, actor.email(), ENTITY, id, "execution_failed", Map.of("error", error));
            notifications.notifyClientManagers(clientId, actor.email(), "optimization_failed", "critical",
                    "Change failed: " + nameOf(id), error, ENTITY, id);
        });
    }

    private Target resolveTarget(UUID clientId, PreviewRequest req) {
        if (req.recommendationId() == null || req.recommendationId().isBlank()) {
            if (req.actionType() == null || req.level() == null || req.objectId() == null) {
                throw new IllegalArgumentException("Choose an action, a level and a Meta object ID");
            }
            return new Target(req.actionType(), req.level(), req.objectId(), req.dailyBudgetMinor(), null, blankToNull(req.reason()));
        }
        UUID recId;
        try {
            recId = UUID.fromString(req.recommendationId());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Recommendation not found");
        }
        Map<String, Object> rec = jdbc.queryForList("SELECT * FROM performance_recommendations WHERE id = ? AND client_id = ?", recId, clientId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Recommendation not found"));
        if (!Set.of("pending", "accepted").contains(String.valueOf(rec.get("status")))) {
            throw new ConflictException("This recommendation is " + rec.get("status") + "; generate a fresh one");
        }
        String type = String.valueOf(rec.get("recommendation_type"));
        String reason = "From recommendation: " + rec.get("title");
        if (req.actionType() != null) {
            String level = req.level() != null ? req.level() : rec.get("ad_id") != null ? "ad" : rec.get("adset_id") != null ? "adset" : "campaign";
            String object = req.objectId() != null ? req.objectId()
                    : String.valueOf(rec.get(level.equals("ad") ? "ad_id" : level.equals("adset") ? "adset_id" : "campaign_id"));
            return new Target(req.actionType(), level, object, req.dailyBudgetMinor(), recId, reason);
        }
        return switch (type) {
            case "increase_budget", "reduce_budget" -> {
                Map<String, Object> change = read(rec.get("proposed_change"));
                Object to = change.get("to");
                if (to == null || rec.get("campaign_id") == null) {
                    throw new ConflictException("This recommendation has no concrete budget; choose the change yourself");
                }
                long minor = new BigDecimal(String.valueOf(to)).movePointRight(2).longValue();
                yield new Target("set_daily_budget", "campaign", String.valueOf(rec.get("campaign_id")), minor, recId, reason);
            }
            case "pause_for_review" -> new Target("pause", "campaign", String.valueOf(rec.get("campaign_id")), null, recId, reason);
            default -> throw new ConflictException("This recommendation has no direct Meta change; choose the action yourself");
        };
    }

    private MetaAccess token(UUID clientId, Actor actor) {
        MetaAccess resolved = metaAccess.resolve(clientId, actor.email());
        if (!resolved.available()) {
            throw new ConflictException(resolved.note());
        }
        return resolved;
    }

    private Policy policy(UUID clientId) {
        return jdbc.query("SELECT max_change_pct, daily_spend_limit FROM client_budget_policies WHERE client_id = ?",
                (rs, i) -> new Policy((Integer) rs.getObject("max_change_pct"), rs.getBigDecimal("daily_spend_limit")), clientId)
                .stream().findFirst().orElse(new Policy(null, null));
    }

    private void expireOld(UUID clientId) {
        jdbc.update("UPDATE campaign_actions SET status = 'EXPIRED', updated_at = ? WHERE client_id = ? "
                + "AND status IN ('PROPOSED', 'APPROVED') AND expires_at < ?", now(), clientId, now());
    }

    private static void requireFresh(Map<String, Object> row) {
        if (((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new ConflictException("This preview is older than 24 hours; preview the change again");
        }
    }

    private static void requireStatus(Map<String, Object> row, Set<String> allowed, String verb) {
        String status = String.valueOf(row.get("status"));
        if (!allowed.contains(status)) {
            throw new ConflictException("A " + status.toLowerCase() + " change cannot be " + verb);
        }
    }

    private Map<String, Object> lock(UUID id) {
        return jdbc.queryForList("SELECT * FROM campaign_actions WHERE id = ? FOR UPDATE", id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Change not found"));
    }

    private UUID clientOf(UUID id) {
        return jdbc.queryForList("SELECT client_id FROM campaign_actions WHERE id = ?", UUID.class, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Change not found"));
    }

    private String nameOf(UUID id) {
        return jdbc.queryForList("SELECT COALESCE(object_name, object_id) FROM campaign_actions WHERE id = ?", String.class, id)
                .stream().findFirst().orElse("");
    }

    private void event(UUID id, String event, String outcome, String detail, String actor) {
        jdbc.update("INSERT INTO campaign_action_events (id, action_id, event, outcome, detail, actor_email, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id, event, outcome, detail, actor, now());
    }

    private ActionDto dto(UUID id) {
        return jdbc.query("SELECT * FROM campaign_actions WHERE id = ?", (rs, i) -> toDto(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Change not found"));
    }

    private ActionDto toDto(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String status = rs.getString("status");
        List<EventDto> events = jdbc.query("SELECT * FROM campaign_action_events WHERE action_id = ? ORDER BY created_at, id",
                (e, j) -> new EventDto(e.getString("event"), e.getString("outcome"), e.getString("detail"),
                        e.getString("actor_email"), e.getTimestamp("created_at").toInstant()), UUID.fromString(id));
        List<GuardrailCheck> checks = new ArrayList<>();
        try {
            checks = json.readValue(rs.getString("guardrails"), new TypeReference<List<GuardrailCheck>>() { });
        } catch (JsonProcessingException ignored) {
            // shown as no checks
        }
        List<String> allowed = new ArrayList<>();
        if ("PROPOSED".equals(status)) { allowed.add("approve"); allowed.add("reject"); allowed.add("cancel"); }
        if ("APPROVED".equals(status)) { allowed.add("execute"); allowed.add("cancel"); }
        if ("FAILED".equals(status) && rs.getString("approved_by") != null) allowed.add("execute");
        if ("FAILED".equals(status)) allowed.add("cancel");
        return new ActionDto(id, rs.getString("client_id"), rs.getString("recommendation_id"), rs.getString("source"),
                rs.getString("action_type"), rs.getString("level"), rs.getString("object_id"), rs.getString("object_name"),
                rs.getString("ad_account_id"), status, read(rs.getString("before_state")), read(rs.getString("proposed_after")),
                rs.getString("actual_after") == null ? null : read(rs.getString("actual_after")), rs.getString("verification"),
                checks, rs.getString("reason"), rs.getInt("attempts"), rs.getString("last_error"), rs.getString("created_by"),
                rs.getString("approved_by"), instant(rs, "approved_at"), rs.getString("approval_note"),
                rs.getString("executed_by"), instant(rs, "executed_at"), instant(rs, "verified_at"), instant(rs, "expires_at"),
                instant(rs, "created_at"), events, allowed);
    }

    private static String summary(Map<String, Object> state) {
        List<String> parts = new ArrayList<>();
        if (state.get("status") != null) parts.add("status " + state.get("status"));
        Long budget = ActionGuardrails.number(state.get("daily_budget"));
        if (budget != null) parts.add("daily budget " + BigDecimal.valueOf(budget).movePointLeft(2).stripTrailingZeros().toPlainString());
        return parts.isEmpty() ? "no status" : String.join(", ", parts);
    }

    private static String label(String action) {
        return switch (action) {
            case "pause" -> "Pause";
            case "resume" -> "Resume";
            default -> "Daily budget";
        };
    }

    private Map<String, Object> read(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        try {
            return json.readValue(String.valueOf(raw), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
