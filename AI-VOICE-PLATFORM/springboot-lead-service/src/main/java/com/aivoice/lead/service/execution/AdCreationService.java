package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.CampaignExecutionDtos.CampaignConfig;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.execution.MetaExecutionGateway.CreativeSpec;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.media.MediaStorage;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the ad inside a campaign Campex already published (paused), from the approved creative attached to it:
 * image → upload image; video → upload video + thumbnail, wait until Meta has processed it; then ad creative and a
 * PAUSED ad. Each Meta ID is stored as soon as it is known, so a retry continues where it stopped and never uploads or
 * creates twice; the ad name carries the publish tag and is looked up before creating.
 */
@Service
public class AdCreationService {

    private static final Logger log = LoggerFactory.getLogger(AdCreationService.class);
    static final List<String> ACTIVE = List.of("pending", "uploading", "processing", "creating");
    static final int MAX_PROCESSING_CHECKS = 60;
    static final Duration PROCESSING_POLL = Duration.ofSeconds(30);
    static final Duration LEASE = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ClientAccessService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MetaAccessResolver metaAccess;
    private final MetaExecutionGateway meta;
    private final MediaStorage media;
    private final ObjectMapper json;

    public AdCreationService(JdbcTemplate jdbc, TransactionTemplate tx, ClientAccessService access, AuditService audit,
                             NotificationService notifications, MetaAccessResolver metaAccess, MetaExecutionGateway meta,
                             MediaStorage media, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.metaAccess = metaAccess;
        this.meta = meta;
        this.media = media;
        this.json = json;
    }

    /** Decides, right after publishing, whether Campex can create the ad. Called inside the publish transaction. */
    String initialStatus(CampaignConfig c) {
        if (c.creativeAssetId() == null || c.creativeAssetId().isBlank()) return "skipped";
        if (c.destinationUrl() == null || c.destinationUrl().isBlank() || c.pageId() == null || c.pageId().isBlank()) return "skipped";
        return "pending";
    }

    static String skipReason(CampaignConfig c) {
        if (c.creativeAssetId() == null || c.creativeAssetId().isBlank()) return "No approved creative was attached; add the ad in Ads Manager.";
        if (c.pageId() == null || c.pageId().isBlank()) return "No Facebook Page was selected; add the ad in Ads Manager.";
        return "No destination URL was set; add the ad in Ads Manager.";
    }

    @Scheduled(fixedDelayString = "${campex.ads.poll-ms:15000}", initialDelayString = "${campex.ads.initial-delay-ms:20000}")
    public void tick() {
        List<UUID> due = jdbc.queryForList("SELECT id FROM campaign_executions WHERE ad_status IN ('pending', 'uploading', 'processing', 'creating') "
                + "AND (ad_next_check_at IS NULL OR ad_next_check_at <= now()) ORDER BY updated_at LIMIT 10", UUID.class);
        for (UUID id : due) {
            try {
                advance(id);
            } catch (RuntimeException e) {
                log.warn("Ad creation for execution {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
    }

    /** "Check now" from the dashboard. */
    public void advanceNow(UUID executionId) {
        UUID clientId = jdbc.queryForList("SELECT client_id FROM campaign_executions WHERE id = ?", UUID.class, executionId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Campaign execution not found"));
        access.require(clientId, Permission.MANAGE);
        jdbc.update("UPDATE campaign_executions SET ad_next_check_at = NULL WHERE id = ? AND ad_status IN ('pending', 'uploading', 'processing', 'creating')", executionId);
        advance(executionId);
    }

    /** Restarts a failed ad creation; IDs already obtained from Meta are reused. */
    public void retry(UUID executionId) {
        UUID clientId = jdbc.queryForList("SELECT client_id FROM campaign_executions WHERE id = ?", UUID.class, executionId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Campaign execution not found"));
        String actor = access.require(clientId, Permission.MANAGE).email();
        int n = jdbc.update("UPDATE campaign_executions SET ad_status = 'pending', ad_error = NULL, ad_attempts = 0, ad_next_check_at = NULL "
                + "WHERE id = ? AND ad_status = 'failed' AND meta_adset_id IS NOT NULL", executionId);
        if (n != 1) throw new ConflictException("Only a failed ad creation can be retried");
        tx.executeWithoutResult(s -> audit.record(clientId, actor, "campaign_execution", executionId, "ad_retry", Map.of()));
        advance(executionId);
    }

    /** Runs as many steps as possible now; stops when Meta needs time (video processing) or on an error. */
    public void advance(UUID id) {
        // Lease: only one worker advances an execution at a time.
        int leased = jdbc.update("UPDATE campaign_executions SET ad_next_check_at = ? WHERE id = ? AND ad_status IN "
                + "('pending', 'uploading', 'processing', 'creating') AND (ad_next_check_at IS NULL OR ad_next_check_at <= now())",
                Timestamp.from(Instant.now().plus(LEASE)), id);
        if (leased != 1) return;
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM campaign_executions WHERE id = ?", id);
        UUID clientId = (UUID) row.get("client_id");
        try {
            advanceLeased(id, clientId, row);
        } catch (RuntimeException e) {
            // Never leave an ad silently stuck: anything unexpected becomes a visible, retryable failure.
            log.warn("Ad creation for execution {} hit an unexpected error: {}", id, e.getClass().getSimpleName());
            fail(id, clientId, "Campex hit an unexpected error while creating the ad (" + e.getClass().getSimpleName()
                    + "). Nothing was duplicated; use Retry ad creation.");
        }
    }

    private void advanceLeased(UUID id, UUID clientId, Map<String, Object> row) {
        CampaignConfig config = read(String.valueOf(row.get("config")));
        MetaAccess token = metaAccess.resolve(clientId, null);
        if (!token.available()) {
            fail(id, clientId, "Meta connection unavailable: " + token.note());
            return;
        }
        Creative creative = creative(UUID.fromString(config.creativeAssetId()), config.creativeVersion());
        String tag = String.valueOf(row.get("publish_tag"));
        String account = config.adAccountId();
        String status = String.valueOf(row.get("ad_status"));
        String videoId = (String) row.get("meta_video_id");
        String imageHash = (String) row.get("meta_image_hash");
        String creativeId = (String) row.get("meta_creative_id");
        try {
            if ("pending".equals(status) || "uploading".equals(status)) {
                setStatus(id, "uploading", null);
                if (imageHash == null) {
                    imageHash = meta.uploadImage(token.token(), account, config.name() + " [" + tag + "] image", creative.imageBytes());
                    jdbc.update("UPDATE campaign_executions SET meta_image_hash = ? WHERE id = ?", imageHash, id);
                    step(id, "ad_media", "ok", "Image uploaded to the ad account", Map.of("image_hash", imageHash));
                }
                if (creative.videoPath() != null && videoId == null) {
                    videoId = meta.uploadVideo(token.token(), account, config.name() + " [" + tag + "]", creative.videoPath(), creative.videoMime());
                    jdbc.update("UPDATE campaign_executions SET meta_video_id = ? WHERE id = ?", videoId, id);
                    step(id, "ad_media", "ok", "Video uploaded; Meta is processing it", Map.of("video_id", videoId));
                }
                status = creative.videoPath() != null ? "processing" : "creating";
                setStatus(id, status, null);
            }
            if ("processing".equals(status)) {
                String state = meta.videoStatus(token.token(), videoId);
                if ("error".equals(state)) {
                    fail(id, clientId, "Meta could not process the video. Re-export it as H.264 MP4 and upload it again.");
                    return;
                }
                if (!"ready".equals(state)) {
                    int checks = jdbc.queryForObject("UPDATE campaign_executions SET ad_attempts = ad_attempts + 1 WHERE id = ? RETURNING ad_attempts",
                            Integer.class, id);
                    if (checks > MAX_PROCESSING_CHECKS) {
                        fail(id, clientId, "Meta is still processing the video after 30 minutes; retry later.");
                    } else {
                        jdbc.update("UPDATE campaign_executions SET ad_next_check_at = ? WHERE id = ?", Timestamp.from(Instant.now().plus(PROCESSING_POLL)), id);
                    }
                    return;
                }
                status = "creating";
                setStatus(id, status, null);
            }
            if ("creating".equals(status)) {
                String adName = config.name() + " [" + tag + "]";
                if (creativeId == null) {
                    creativeId = meta.createCreative(token.token(), new CreativeSpec(account, config.pageId(), creative.videoPath() != null ? "video" : "image",
                            adName, config.destinationUrl(), config.primaryText(), config.headline(), config.description(), config.cta(), imageHash, videoId));
                    if (creativeId == null || creativeId.isBlank()) {
                        fail(id, clientId, "Meta did not return an ad creative ID.");
                        return;
                    }
                    jdbc.update("UPDATE campaign_executions SET meta_creative_id = ? WHERE id = ?", creativeId, id);
                    step(id, "ad_creative", "ok", "Ad creative created", Map.of("creative_id", creativeId));
                }
                String adsetId = String.valueOf(row.get("meta_adset_id"));
                List<String> existing = meta.findAds(token.token(), adsetId, tag);
                String adId = existing.isEmpty() ? meta.createAd(token.token(), account, adsetId, creativeId, adName) : existing.getFirst();
                if (adId == null || adId.isBlank()) {
                    fail(id, clientId, "Meta did not return an ad ID.");
                    return;
                }
                String finalCreative = creativeId;
                tx.executeWithoutResult(s -> {
                    jdbc.update("UPDATE campaign_executions SET ad_status = 'created', meta_ad_id = ?, ad_error = NULL, ad_next_check_at = NULL, "
                            + "updated_at = ? WHERE id = ?", adId, Timestamp.from(Instant.now()), id);
                    step(id, "ad_create", "ok", existing.isEmpty() ? "Ad created (paused)" : "Ad already existed in Meta; linked it",
                            Map.of("ad_id", adId, "creative_id", finalCreative));
                    audit.record(clientId, "system:ad-builder", "campaign_execution", id, "ad_created", Map.of("ad_id", adId));
                    notifications.notifyClientManagers(clientId, null, "campaign_ad_created", "info", "Ad created (paused): " + config.name(),
                            "The " + (creative.videoPath() != null ? "video" : "image") + " ad is in Meta, paused. Review it in Ads Manager before activating.",
                            "campaign_execution", id);
                });
            }
        } catch (GatewayException e) {
            fail(id, clientId, e.getMessage());
        }
    }

    record Creative(byte[] imageBytes, java.nio.file.Path videoPath, String videoMime) {
    }

    private Creative creative(UUID assetId, Integer version) {
        Map<String, Object> v = jdbc.queryForList("SELECT v.image_id, v.video_id FROM creative_versions v WHERE v.asset_id = ? AND v.version = ?",
                assetId, version).stream().findFirst().orElseThrow(() -> new IllegalStateException("Approved creative version not found"));
        byte[] image = jdbc.queryForObject("SELECT bytes FROM creative_images WHERE id = ?", byte[].class, v.get("image_id"));
        if (v.get("video_id") == null) return new Creative(image, null, null);
        Map<String, Object> m = jdbc.queryForMap("SELECT storage_key, mime_type FROM creative_media WHERE id = ?", v.get("video_id"));
        return new Creative(image, media.path((String) m.get("storage_key")), (String) m.get("mime_type"));
    }

    private void setStatus(UUID id, String status, String error) {
        jdbc.update("UPDATE campaign_executions SET ad_status = ?, ad_error = ?, updated_at = ? WHERE id = ?", status, error, Timestamp.from(Instant.now()), id);
    }

    private void fail(UUID id, UUID clientId, String error) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE campaign_executions SET ad_status = 'failed', ad_error = ?, ad_next_check_at = NULL, updated_at = ? WHERE id = ?",
                    error, Timestamp.from(Instant.now()), id);
            step(id, "ad_create", "failed", error, null);
            notifications.notifyClientManagers(clientId, null, "campaign_ad_failed", "warning", "Ad not created",
                    error + " The campaign stays paused; retry from Campaign approvals.", "campaign_execution", id);
        });
    }

    private void step(UUID id, String step, String outcome, String detail, Map<String, Object> ids) {
        jdbc.update("INSERT INTO campaign_execution_steps (id, execution_id, step, outcome, detail, meta_ids, actor_email, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, 'system:ad-builder', ?)", UUID.randomUUID(), id, step, outcome, detail,
                ids == null ? null : write(ids), Timestamp.from(Instant.now()));
    }

    private CampaignConfig read(String raw) {
        try {
            return json.readValue(raw, CampaignConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored campaign data is unreadable", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
