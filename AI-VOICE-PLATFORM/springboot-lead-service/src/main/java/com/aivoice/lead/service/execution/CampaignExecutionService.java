package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.CampaignExecutionDtos.ApprovalDto;
import com.aivoice.lead.dto.CampaignExecutionDtos.CampaignConfig;
import com.aivoice.lead.dto.CampaignExecutionDtos.ExecutionDetail;
import com.aivoice.lead.dto.CampaignExecutionDtos.ExecutionSummary;
import com.aivoice.lead.dto.CampaignExecutionDtos.StepDto;
import com.aivoice.lead.dto.CampaignExecutionDtos.ValidationReport;
import com.aivoice.lead.dto.CampaignExecutionDtos.VersionDto;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.execution.MetaExecutionGateway.CampaignState;
import com.aivoice.lead.service.execution.MetaExecutionGateway.FoundCampaign;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.execution.MetaExecutionGateway.PublishCommand;
import com.aivoice.lead.service.execution.MetaExecutionGateway.PublishResult;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 3 campaign execution engine: the only place that turns an approved configuration into Meta objects.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Publishing requires an approval bound to the SHA-256 of the exact current configuration.</li>
 *   <li>{@code APPROVED → EXECUTING} is a conditional update, so concurrent publishes cannot both reach Meta.</li>
 *   <li>No database transaction is open while Meta is called.</li>
 *   <li>A failed or timed-out publish is reconciled (looked up by publish tag) before it is called failed;
 *       a retry never re-creates a campaign that Meta already has.</li>
 *   <li>Objects are always created PAUSED. Local status and Meta status are stored separately.</li>
 * </ul>
 */
@Service
public class CampaignExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CampaignExecutionService.class);
    private static final Set<String> EDITABLE = Set.of("DRAFT", "READY_FOR_REVIEW", "PENDING_APPROVAL", "APPROVED");
    private static final Set<String> CANCELLABLE = Set.of("DRAFT", "READY_FOR_REVIEW", "PENDING_APPROVAL", "APPROVED", "FAILED");
    private static final Set<String> RECONCILABLE = Set.of("EXECUTING", "FAILED", "PUBLISHED_PAUSED", "ACTIVE");
    private static final String ENTITY = "campaign_execution";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ClientAccessService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MetaAccessResolver metaAccess;
    private final MetaExecutionGateway meta;
    private final ObjectMapper json;

    public CampaignExecutionService(JdbcTemplate jdbc, TransactionTemplate tx, ClientAccessService access,
                                    AuditService audit, NotificationService notifications,
                                    MetaAccessResolver metaAccess, MetaExecutionGateway meta, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.metaAccess = metaAccess;
        this.meta = meta;
        this.json = json;
    }

    // ------------------------------------------------------------------------------------------------ draft lifecycle

    public ExecutionDetail create(UUID clientId, CampaignConfig input, String idempotencyKey) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        CampaignConfig config = normalize(input);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 100 characters");
        }
        if (key != null) {
            Optional<UUID> existing = findByIdempotencyKey(clientId, key);
            if (existing.isPresent()) {
                return detail(existing.get());
            }
        }
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                Timestamp now = now();
                String hash = hash(config);
                jdbc.update("INSERT INTO campaign_executions (id, client_id, ad_account_id, name, objective, status, "
                                + "config, config_version, config_hash, idempotency_key, publish_tag, created_by, "
                                + "updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, 1, ?, ?, ?, ?, ?, ?, ?)",
                        id, clientId, config.adAccountId(), config.name(), config.objective(), write(config), hash,
                        key, newPublishTag(), actor.email(), actor.email(), now, now);
                insertVersion(id, 1, config, hash, actor.email(), now);
                step(id, "create", "ok", null, null, actor.email());
                audit.record(clientId, actor.email(), ENTITY, id, "created", Map.of("name", config.name()));
            });
        } catch (DuplicateKeyException e) {
            // Same Idempotency-Key raced with another request: return the row that won.
            return detail(findByIdempotencyKey(clientId, key).orElseThrow(() -> e));
        }
        return detail(id);
    }

    public List<ExecutionSummary> list(UUID clientId, String status) {
        access.read(clientId);
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("SELECT * FROM campaign_executions WHERE client_id = ?"
                        + (filter == null ? "" : " AND status = ?") + " ORDER BY updated_at DESC LIMIT 200",
                (rs, i) -> summary(rs),
                filter == null ? new Object[]{clientId} : new Object[]{clientId, filter});
    }

    public ExecutionDetail get(UUID id) {
        access.read(clientOf(id));
        return detail(id);
    }

    public ExecutionDetail update(UUID id, CampaignConfig input) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        CampaignConfig config = normalize(input);
        tx.executeWithoutResult(s -> {
            Row row = lock(id);
            requireStatus(row, EDITABLE, "edited");
            String hash = hash(config);
            if (hash.equals(row.configHash)) {
                return;
            }
            int version = row.configVersion + 1;
            Timestamp now = now();
            jdbc.update("UPDATE campaign_executions SET config = ?::jsonb, config_version = ?, config_hash = ?, "
                            + "name = ?, objective = ?, ad_account_id = ?, status = 'DRAFT', last_validation = NULL, "
                            + "updated_by = ?, updated_at = ? WHERE id = ?",
                    write(config), version, hash, config.name(), config.objective(), config.adAccountId(),
                    actor.email(), now, id);
            insertVersion(id, version, config, hash, actor.email(), now);
            int invalidated = jdbc.update("UPDATE campaign_approvals SET invalidated_at = ? WHERE execution_id = ? "
                    + "AND invalidated_at IS NULL", now, id);
            step(id, "edit", "ok", invalidated > 0 ? "Configuration changed; previous approval invalidated." : null,
                    null, actor.email());
            audit.record(clientId, actor.email(), ENTITY, id, "edited",
                    Map.of("from_version", row.configVersion, "to_version", version, "from_status", row.status,
                            "approvals_invalidated", invalidated));
        });
        return detail(id);
    }

    public ValidationReport validate(UUID id) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        return tx.execute(s -> {
            Row row = lock(id);
            ValidationReport report = CampaignValidator.validate(row.config, context(clientId));
            String next = report.valid() && "DRAFT".equals(row.status) ? "READY_FOR_REVIEW"
                    : (!report.valid() && "READY_FOR_REVIEW".equals(row.status) ? "DRAFT" : row.status);
            jdbc.update("UPDATE campaign_executions SET last_validation = ?::jsonb, status = ?, updated_at = ? WHERE id = ?",
                    write(report), next, now(), id);
            step(id, "validate", report.valid() ? "ok" : "blocked",
                    report.valid() ? null : report.errors().size() + " error(s)", null, actor.email());
            return report;
        });
    }

    public ExecutionDetail submit(UUID id) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            Row row = lock(id);
            requireStatus(row, Set.of("READY_FOR_REVIEW"), "submitted (validate it first)");
            requireValid(row, clientId);
            transition(id, row.status, "PENDING_APPROVAL", actor, "submit", null);
            jdbc.update("UPDATE campaign_executions SET submitted_at = ? WHERE id = ?", now(), id);
            notifications.notifyClientManagers(clientId, actor.email(), "campaign_approval_requested", "info",
                    "Approval requested: " + row.name, "Review the configuration and approve or reject it.",
                    ENTITY, id);
        });
        return detail(id);
    }

    public ExecutionDetail approve(UUID id, String note) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            Row row = lock(id);
            requireStatus(row, Set.of("PENDING_APPROVAL"), "approved");
            requireValid(row, clientId);
            jdbc.update("INSERT INTO campaign_approvals (id, execution_id, client_id, config_version, config_hash, "
                            + "approved_daily_budget_minor, decision, decided_by, decided_at, note) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'approved', ?, ?, ?)",
                    UUID.randomUUID(), id, clientId, row.configVersion, row.configHash, row.config.dailyBudgetMinor(),
                    actor.email(), now(), blankToNull(note));
            transition(id, row.status, "APPROVED", actor, "approve", Map.of("config_version", row.configVersion,
                    "config_hash", row.configHash, "daily_budget_minor", row.config.dailyBudgetMinor()));
        });
        return detail(id);
    }

    public ExecutionDetail reject(UUID id, String note) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            Row row = lock(id);
            requireStatus(row, Set.of("PENDING_APPROVAL"), "rejected");
            jdbc.update("INSERT INTO campaign_approvals (id, execution_id, client_id, config_version, config_hash, "
                            + "decision, decided_by, decided_at, note) VALUES (?, ?, ?, ?, ?, 'rejected', ?, ?, ?)",
                    UUID.randomUUID(), id, clientId, row.configVersion, row.configHash, actor.email(), now(),
                    blankToNull(note));
            transition(id, row.status, "DRAFT", actor, "reject", note == null ? null : Map.of("note", note));
            notifications.notifyClientManagers(clientId, row.createdBy, "campaign_rejected", "warning",
                    "Changes requested: " + row.name, blankToNull(note), ENTITY, id);
        });
        return detail(id);
    }

    public ExecutionDetail cancel(UUID id) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            Row row = lock(id);
            requireStatus(row, CANCELLABLE, "cancelled");
            if (row.metaCampaignId != null) {
                throw new ConflictException("A paused campaign already exists in Meta for this execution; "
                        + "delete or archive it in Ads Manager instead");
            }
            transition(id, row.status, "CANCELLED", actor, "cancel", null);
        });
        return detail(id);
    }

    // ------------------------------------------------------------------------------------------------ publishing

    public ExecutionDetail publish(UUID id, boolean confirm) {
        if (!confirm) {
            throw new IllegalArgumentException("Confirm publishing: Campex will create a PAUSED campaign and ad set in Meta");
        }
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        Prepared prepared = tx.execute(s -> prepare(id, clientId, actor, Set.of("APPROVED"), "publish"));
        execute(prepared, actor);
        return detail(id);
    }

    /** FAILED executions with no Meta campaign: reconcile first, and only publish again if Meta has nothing. */
    public ExecutionDetail retry(UUID id) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        Row row = load(id);
        requireStatus(row, Set.of("FAILED"), "retried");
        if (row.metaCampaignId != null) {
            throw new ConflictException("Meta already has a campaign for this execution; it will not be created twice");
        }
        MetaAccess metaToken = requireMeta(clientId, actor, row.config.adAccountId());
        List<FoundCampaign> found;
        try {
            found = meta.findByNameFragment(metaToken.token(), row.config.adAccountId(), row.publishTag);
        } catch (GatewayException e) {
            throw new ConflictException("Could not confirm with Meta whether the earlier attempt created a campaign; "
                    + "try again shortly. Nothing was re-created.");
        }
        if (!found.isEmpty()) {
            tx.executeWithoutResult(s -> recordFound(id, clientId, lock(id), found.getFirst(), actor, "retry"));
            return detail(id);
        }
        Prepared prepared = tx.execute(s -> prepare(id, clientId, actor, Set.of("FAILED"), "retry"));
        execute(prepared, actor);
        return detail(id);
    }

    /** Looks the execution up in Meta: finds a campaign whose creation outcome was unknown, or refreshes status. */
    public ExecutionDetail reconcile(UUID id) {
        UUID clientId = clientOf(id);
        Actor actor = access.require(clientId, Permission.MANAGE);
        Row row = load(id);
        requireStatus(row, RECONCILABLE, "reconciled");
        MetaAccess metaToken = requireMeta(clientId, actor, row.config.adAccountId());
        if (row.metaCampaignId != null) {
            refreshStatus(id, metaToken.token(), row.config.adAccountId(), row.metaCampaignId, actor);
            return detail(id);
        }
        reconcileUnknown(id, clientId, metaToken.token(), row.config.adAccountId(), row.publishTag, actor, null);
        return detail(id);
    }

    private record Prepared(UUID id, UUID clientId, String token, PublishCommand command, String name) {
    }

    /** Inside a transaction: all checks, then the conditional claim of the publish. */
    private Prepared prepare(UUID id, UUID clientId, Actor actor, Set<String> from, String stepName) {
        Row row = lock(id);
        requireStatus(row, from, "published");
        requireValid(row, clientId);
        Map<String, Object> approval = jdbc.queryForList("SELECT config_hash, approved_daily_budget_minor FROM "
                        + "campaign_approvals WHERE execution_id = ? AND decision = 'approved' AND invalidated_at IS NULL "
                        + "ORDER BY decided_at DESC LIMIT 1", id).stream().findFirst().orElse(null);
        if (approval == null || !row.configHash.equals(approval.get("config_hash"))) {
            throw new ConflictException("The current configuration has not been approved");
        }
        MetaAccess metaToken = requireMeta(clientId, actor, row.config.adAccountId());
        int claimed = jdbc.update("UPDATE campaign_executions SET status = 'EXECUTING', publish_outcome = 'unknown', "
                        + "publish_attempts = publish_attempts + 1, last_error = NULL, updated_by = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?", actor.email(), now(), id, row.status);
        if (claimed != 1) {
            throw new ConflictException("This campaign is already being published");
        }
        step(id, stepName, "unknown", "Sent to Meta (attempt " + (row.publishAttempts + 1) + ").", null, actor.email());
        audit.record(clientId, actor.email(), ENTITY, id, stepName + "_started",
                Map.of("from", row.status, "to", "EXECUTING", "config_hash", row.configHash));
        CampaignConfig c = row.config;
        PublishCommand command = new PublishCommand(c.adAccountId(), c.name() + " [" + row.publishTag + "]",
                c.objective(), c.platforms(), c.location(), c.ageMin(), c.ageMax(), c.dailyBudgetMinor(),
                c.headline(), c.primaryText(), c.cta(), blankToNull(c.pageId()));
        return new Prepared(id, clientId, metaToken.token(), command, c.name());
    }

    /** Outside any transaction: call Meta, then record the result (or reconcile when it is unknown). */
    private void execute(Prepared p, Actor actor) {
        PublishResult result;
        try {
            result = meta.publish(p.token(), p.command());
        } catch (GatewayException e) {
            log.info("Publish of execution {} did not return a result; reconciling", p.id());
            reconcileUnknown(p.id(), p.clientId(), p.token(), p.command().adAccountId(),
                    tagOf(p.command().campaignName()), actor, e.getMessage());
            return;
        }
        if (result.campaignId() == null || result.campaignId().isBlank()) {
            reconcileUnknown(p.id(), p.clientId(), p.token(), p.command().adAccountId(),
                    tagOf(p.command().campaignName()), actor, "Meta returned no campaign ID");
            return;
        }
        tx.executeWithoutResult(s -> recordCreated(p.id(), p.clientId(), result.campaignId(),
                blankToNull(result.adsetId()), blankToNull(result.adId()), actor, "publish", result.warning()));
        refreshStatus(p.id(), p.token(), p.command().adAccountId(), result.campaignId(), actor);
    }

    private void reconcileUnknown(UUID id, UUID clientId, String token, String adAccountId, String tag, Actor actor,
                                  String publishError) {
        List<FoundCampaign> found;
        try {
            found = meta.findByNameFragment(token, adAccountId, tag);
        } catch (GatewayException e) {
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE campaign_executions SET publish_outcome = 'unknown', last_error = ?, updated_at = ? "
                                + "WHERE id = ?",
                        "Meta did not confirm the result" + (publishError == null ? "" : " (" + publishError + ")")
                                + ". Use Reconcile to check again; nothing will be re-created.", now(), id);
                step(id, "reconcile", "unknown", "Lookup failed: " + e.getMessage(), null, actor.email());
                notifications.notifyClientManagers(clientId, actor.email(), "campaign_publish_unconfirmed", "warning",
                        "Publish not confirmed: " + nameOf(id), "Campex could not confirm with Meta. Reconcile before retrying.",
                        ENTITY, id);
            });
            return;
        }
        tx.executeWithoutResult(s -> {
            Row row = lock(id);
            if (!found.isEmpty()) {
                recordFound(id, clientId, row, found.getFirst(), actor, "reconcile");
                return;
            }
            String reason = publishError == null ? "Meta has no campaign for this publish."
                    : "Meta rejected the publish: " + publishError;
            jdbc.update("UPDATE campaign_executions SET status = 'FAILED', publish_outcome = ?, last_error = ?, "
                            + "updated_at = ? WHERE id = ?",
                    publishError == null ? "not_found" : "rejected", reason + " Nothing was created; it is safe to retry.",
                    now(), id);
            step(id, "reconcile", "failed", reason, null, actor.email());
            audit.record(clientId, actor.email(), ENTITY, id, "publish_failed", Map.of("from", row.status, "to", "FAILED"));
            notifications.notifyClientManagers(clientId, actor.email(), "campaign_publish_failed", "critical",
                    "Publishing failed: " + row.name, reason, ENTITY, id);
        });
    }

    private void recordFound(UUID id, UUID clientId, Row row, FoundCampaign campaign, Actor actor, String stepName) {
        String adset = campaign.adsetIds().isEmpty() ? null : campaign.adsetIds().getFirst();
        recordCreated(id, clientId, campaign.id(), adset, null, actor, stepName, "Found in Meta by publish tag.");
        jdbc.update("UPDATE campaign_executions SET meta_status = ?, meta_effective_status = ?, meta_checked_at = ? "
                + "WHERE id = ?", campaign.status(), campaign.effectiveStatus(), now(), id);
    }

    /** Inside a transaction. Campaign + ad set → PUBLISHED_PAUSED; campaign only → FAILED (partial, not retryable). */
    private void recordCreated(UUID id, UUID clientId, String campaignId, String adsetId, String adId, Actor actor,
                               String stepName, String note) {
        Row row = lock(id);
        boolean complete = adsetId != null;
        Timestamp now = now();
        jdbc.update("UPDATE campaign_executions SET status = ?, publish_outcome = ?, meta_campaign_id = ?, "
                        + "meta_adset_id = ?, meta_ad_id = ?, last_error = ?, published_at = ?, updated_at = ? WHERE id = ?",
                complete ? "PUBLISHED_PAUSED" : "FAILED", complete ? "created" : "partial", campaignId, adsetId, adId,
                complete ? null : "Meta created the campaign (paused) but not the ad set. Finish or delete it in Ads Manager.",
                now, now, id);
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("campaign_id", campaignId);
        ids.put("adset_id", adsetId);
        step(id, stepName, complete ? "ok" : "failed", note, ids, actor.email());
        audit.record(clientId, actor.email(), ENTITY, id, complete ? "published_paused" : "publish_partial",
                Map.of("from", row.status, "to", complete ? "PUBLISHED_PAUSED" : "FAILED", "meta_campaign_id", campaignId));
        notifications.notifyClientManagers(clientId, actor.email(),
                complete ? "campaign_published" : "campaign_publish_failed", complete ? "info" : "critical",
                (complete ? "Published (paused): " : "Partly published: ") + row.name,
                complete ? "Campaign and ad set were created in Meta in PAUSED status. Add the ad and review it in Ads Manager before activating."
                        : "The campaign exists in Meta but the ad set could not be created.",
                ENTITY, id);
    }

    private void refreshStatus(UUID id, String token, String adAccountId, String campaignId, Actor actor) {
        try {
            CampaignState state = meta.status(token, adAccountId, campaignId);
            jdbc.update("UPDATE campaign_executions SET meta_status = ?, meta_effective_status = ?, meta_checked_at = ? "
                    + "WHERE id = ?", state.status(), state.effectiveStatus(), now(), id);
        } catch (GatewayException e) {
            // Status is informational; the local state already reflects what Meta confirmed.
            log.info("Could not refresh Meta status for execution {}", id);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private MetaAccess requireMeta(UUID clientId, Actor actor, String adAccountId) {
        MetaAccess resolved = metaAccess.resolve(clientId, actor.email());
        if (!resolved.available()) {
            throw new ConflictException(resolved.note());
        }
        if (!resolved.adAccounts().contains(adAccountId)) {
            throw new ConflictException("This ad account is not mapped to the client");
        }
        return resolved;
    }

    private void requireValid(Row row, UUID clientId) {
        ValidationReport report = CampaignValidator.validate(row.config, context(clientId));
        if (!report.valid()) {
            throw new ConflictException("Configuration is not valid: " + report.errors().getFirst().message());
        }
    }

    private CampaignValidator.Context context(UUID clientId) {
        List<String> accounts = jdbc.queryForList(
                "SELECT ad_account_id FROM client_ad_accounts WHERE client_id = ?", String.class, clientId);
        BigDecimal limit = jdbc.queryForList("SELECT daily_spend_limit FROM client_budget_policies WHERE client_id = ?",
                BigDecimal.class, clientId).stream().findFirst().orElse(null);
        return new CampaignValidator.Context(Set.copyOf(accounts), limit);
    }

    private void transition(UUID id, String from, String to, Actor actor, String stepName, Map<String, Object> extra) {
        jdbc.update("UPDATE campaign_executions SET status = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                to, actor.email(), now(), id);
        step(id, stepName, "ok", null, null, actor.email());
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("from", from);
        changes.put("to", to);
        if (extra != null) {
            changes.putAll(extra);
        }
        audit.record(clientOf(id), actor.email(), ENTITY, id, stepName, changes);
    }

    private static void requireStatus(Row row, Set<String> allowed, String verb) {
        if (!allowed.contains(row.status)) {
            throw new ConflictException("A " + row.status.toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " campaign cannot be " + verb);
        }
    }

    private void step(UUID id, String step, String outcome, String detail, Map<String, Object> metaIds, String actor) {
        jdbc.update("INSERT INTO campaign_execution_steps (id, execution_id, step, outcome, detail, meta_ids, "
                        + "actor_email, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                UUID.randomUUID(), id, step, outcome, detail, metaIds == null ? null : write(metaIds), actor, now());
    }

    private void insertVersion(UUID id, int version, CampaignConfig config, String hash, String actor, Timestamp now) {
        jdbc.update("INSERT INTO campaign_execution_versions (id, execution_id, version, config, config_hash, "
                        + "created_by, created_at) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)",
                UUID.randomUUID(), id, version, write(config), hash, actor, now);
    }

    private Optional<UUID> findByIdempotencyKey(UUID clientId, String key) {
        return jdbc.queryForList("SELECT id FROM campaign_executions WHERE client_id = ? AND idempotency_key = ?",
                UUID.class, clientId, key).stream().findFirst();
    }

    private UUID clientOf(UUID id) {
        return jdbc.queryForList("SELECT client_id FROM campaign_executions WHERE id = ?", UUID.class, id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Campaign execution not found"));
    }

    private String nameOf(UUID id) {
        return jdbc.queryForObject("SELECT name FROM campaign_executions WHERE id = ?", String.class, id);
    }

    private static final class Row {
        String status;
        String name;
        String configHash;
        int configVersion;
        int publishAttempts;
        String publishTag;
        String metaCampaignId;
        String createdBy;
        CampaignConfig config;
    }

    private Row lock(UUID id) {
        return jdbc.query("SELECT * FROM campaign_executions WHERE id = ? FOR UPDATE", this::row, id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Campaign execution not found"));
    }

    private Row load(UUID id) {
        return jdbc.query("SELECT * FROM campaign_executions WHERE id = ?", this::row, id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Campaign execution not found"));
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        Row r = new Row();
        r.status = rs.getString("status");
        r.name = rs.getString("name");
        r.configHash = rs.getString("config_hash");
        r.configVersion = rs.getInt("config_version");
        r.publishAttempts = rs.getInt("publish_attempts");
        r.publishTag = rs.getString("publish_tag");
        r.metaCampaignId = rs.getString("meta_campaign_id");
        r.createdBy = rs.getString("created_by");
        r.config = read(rs.getString("config"), CampaignConfig.class);
        return r;
    }

    private ExecutionSummary summary(ResultSet rs) throws SQLException {
        return new ExecutionSummary(rs.getString("id"), rs.getString("client_id"), rs.getString("name"),
                rs.getString("objective"), rs.getString("ad_account_id"), rs.getString("status"),
                rs.getString("meta_status"), rs.getString("meta_effective_status"), rs.getString("meta_campaign_id"),
                rs.getString("publish_outcome"), rs.getString("last_error"), rs.getInt("config_version"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "published_at"));
    }

    private ExecutionDetail detail(UUID id) {
        return jdbc.query("SELECT * FROM campaign_executions WHERE id = ?", (rs, i) -> {
            ExecutionSummary summary = summary(rs);
            String status = summary.status();
            String validation = rs.getString("last_validation");
            String hash = rs.getString("config_hash");
            List<VersionDto> versions = jdbc.query("SELECT * FROM campaign_execution_versions WHERE execution_id = ? "
                    + "ORDER BY version DESC", (v, j) -> new VersionDto(v.getInt("version"), v.getString("config_hash"),
                    read(v.getString("config"), new TypeReference<Map<String, Object>>() { }),
                    v.getString("created_by"), instant(v, "created_at")), id);
            List<ApprovalDto> approvals = jdbc.query("SELECT * FROM campaign_approvals WHERE execution_id = ? "
                    + "ORDER BY decided_at DESC", (a, j) -> new ApprovalDto(a.getString("id"), a.getInt("config_version"),
                    a.getString("decision"), a.getString("decided_by"), instant(a, "decided_at"), a.getString("note"),
                    (Long) a.getObject("approved_daily_budget_minor"),
                    "approved".equals(a.getString("decision")) && a.getTimestamp("invalidated_at") == null
                            && hash.equals(a.getString("config_hash"))), id);
            List<StepDto> steps = jdbc.query("SELECT * FROM campaign_execution_steps WHERE execution_id = ? "
                    + "ORDER BY created_at, id", (st, j) -> new StepDto(st.getString("step"), st.getString("outcome"),
                    st.getString("detail"), st.getString("meta_ids") == null ? null
                    : read(st.getString("meta_ids"), new TypeReference<Map<String, Object>>() { }),
                    st.getString("actor_email"), instant(st, "created_at")), id);
            return new ExecutionDetail(summary, read(rs.getString("config"), CampaignConfig.class), hash,
                    validation == null ? null : read(validation, ValidationReport.class), rs.getString("publish_tag"),
                    rs.getInt("publish_attempts"), rs.getString("meta_adset_id"), instant(rs, "meta_checked_at"),
                    versions, approvals, steps, allowedActions(status, rs.getString("meta_campaign_id") != null));
        }, id).stream().findFirst().orElseThrow(() -> new NotFoundException("Campaign execution not found"));
    }

    static List<String> allowedActions(String status, boolean hasMetaCampaign) {
        List<String> actions = new ArrayList<>();
        if (EDITABLE.contains(status)) actions.add("edit");
        if (Set.of("DRAFT", "READY_FOR_REVIEW").contains(status)) actions.add("validate");
        if ("READY_FOR_REVIEW".equals(status)) actions.add("submit");
        if ("PENDING_APPROVAL".equals(status)) { actions.add("approve"); actions.add("reject"); }
        if ("APPROVED".equals(status)) actions.add("publish");
        if ("FAILED".equals(status) && !hasMetaCampaign) actions.add("retry");
        if (RECONCILABLE.contains(status)) actions.add("reconcile");
        if (CANCELLABLE.contains(status) && !hasMetaCampaign) actions.add("cancel");
        return actions;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static CampaignConfig normalize(CampaignConfig c) {
        List<String> platforms = c.platforms() == null ? List.of()
                : c.platforms().stream().map(p -> p.trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        return new CampaignConfig(trim(c.adAccountId()), trim(c.name()), lower(c.objective()), platforms,
                trim(c.location()), c.ageMin(), c.ageMax(), c.dailyBudgetMinor(), trim(c.headline()),
                trim(c.primaryText()), trim(c.description()),
                c.cta() == null ? null : c.cta().trim().toUpperCase(Locale.ROOT).replace(' ', '_'),
                trim(c.destinationUrl()), trim(c.pageId()));
    }

    String hash(CampaignConfig config) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(config).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newPublishTag() {
        return "cx-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String tagOf(String campaignName) {
        int open = campaignName.lastIndexOf('[');
        return campaignName.substring(open + 1, campaignName.length() - 1);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise campaign data", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored campaign data is unreadable", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored campaign data is unreadable", e);
        }
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
