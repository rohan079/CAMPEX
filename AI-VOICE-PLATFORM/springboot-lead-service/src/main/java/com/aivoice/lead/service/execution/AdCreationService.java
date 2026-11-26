package com.aivoice.lead.service.execution;

import com.aivoice.lead.dto.CampaignExecutionDtos.CampaignConfig;
import com.aivoice.lead.dto.CampaignExecutionDtos.CreativeRef;
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
import java.util.ArrayList;
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
    /** Meta advises a handful of ads per ad set; Campex creates at most six. */
    static final int MAX_ADS = 6;

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

    /** Inside the publish transaction: one row per ad (main creative first, then the extra ones). */
    void schedule(UUID executionId, CampaignConfig c) {
        Timestamp now = Timestamp.from(Instant.now());
        List<CreativeRef> refs = new ArrayList<>();
        refs.add(new CreativeRef(c.creativeAssetId(), c.creativeVersion()));
        if (c.extraCreatives() != null) refs.addAll(c.extraCreatives());
        for (int i = 0; i < refs.size() && i < MAX_ADS; i++) {
            jdbc.update("INSERT INTO campaign_execution_ads (id, execution_id, position, creative_asset_id, creative_version, status, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?) ON CONFLICT (execution_id, position) DO NOTHING",
                    UUID.randomUUID(), executionId, i, UUID.fromString(refs.get(i).assetId()), refs.get(i).version(), now, now);
        }
    }

    @Scheduled(fixedDelayString = "${campex.ads.poll-ms:15000}", initialDelayString = "${campex.ads.initial-delay-ms:20000}")
    public void tick() {
        List<UUID> due = jdbc.queryForList("SELECT id FROM campaign_execution_ads WHERE status IN ('pending', 'uploading', 'processing', 'creating') "
                + "AND (next_check_at IS NULL OR next_check_at <= now()) ORDER BY updated_at LIMIT 10", UUID.class);
        for (UUID adId : due) {
            try {
                advanceAd(adId);
            } catch (RuntimeException e) {
                log.warn("Ad creation step {} failed: {}", adId, e.getClass().getSimpleName());
            }
        }
    }

    /** "Check now" from the dashboard: advances every unfinished ad of the campaign. */
    public void advanceNow(UUID executionId) {
        UUID clientId = clientOf(executionId);
        access.require(clientId, Permission.MANAGE);
        jdbc.update("UPDATE campaign_execution_ads SET next_check_at = NULL WHERE execution_id = ? AND status IN ('pending', 'uploading', 'processing', 'creating')", executionId);
        advanceAll(executionId);
    }

    /** Kept for callers that think in executions (tests, older code paths). */
    public void advance(UUID executionId) {
        advanceAll(executionId);
    }

    /** Restarts failed ads; IDs already obtained from Meta are reused, created ads are left alone. */
    public void retry(UUID executionId) {
        UUID clientId = clientOf(executionId);
        String actor = access.require(clientId, Permission.MANAGE).email();
        Integer adset = jdbc.queryForObject("SELECT count(*) FROM campaign_executions WHERE id = ? AND meta_adset_id IS NOT NULL", Integer.class, executionId);
        int n = adset == null || adset == 0 ? 0 : jdbc.update("UPDATE campaign_execution_ads SET status = 'pending', error = NULL, attempts = 0, "
                + "next_check_at = NULL, updated_at = ? WHERE execution_id = ? AND status = 'failed'", Timestamp.from(Instant.now()), executionId);
        if (n == 0) throw new ConflictException("Only a failed ad creation can be retried");
        tx.executeWithoutResult(s -> {
            audit.record(clientId, actor, "campaign_execution", executionId, "ad_retry", Map.of("ads", n));
            summarize(executionId, clientId);
        });
        advanceAll(executionId);
    }

    private void advanceAll(UUID executionId) {
        for (UUID adId : jdbc.queryForList("SELECT id FROM campaign_execution_ads WHERE execution_id = ? AND status IN "
                + "('pending', 'uploading', 'processing', 'creating') ORDER BY position", UUID.class, executionId)) {
            advanceAd(adId);
        }
    }

    /** Runs as many steps of one ad as possible now; stops when Meta needs time (video processing) or on an error. */
    void advanceAd(UUID adId) {
        // Lease: only one worker advances an ad at a time.
        int leased = jdbc.update("UPDATE campaign_execution_ads SET next_check_at = ? WHERE id = ? AND status IN "
                + "('pending', 'uploading', 'processing', 'creating') AND (next_check_at IS NULL OR next_check_at <= now())",
                Timestamp.from(Instant.now().plus(LEASE)), adId);
        if (leased != 1) return;
        Map<String, Object> ad = jdbc.queryForMap("SELECT * FROM campaign_execution_ads WHERE id = ?", adId);
        UUID executionId = (UUID) ad.get("execution_id");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM campaign_executions WHERE id = ?", executionId);
        UUID clientId = (UUID) row.get("client_id");
        try {
            advanceLeased(ad, row, clientId);
        } catch (RuntimeException e) {
            // Never leave an ad silently stuck: anything unexpected becomes a visible, retryable failure.
            log.warn("Ad creation for {} hit an unexpected error: {}", adId, e.getClass().getSimpleName());
            fail(ad, clientId, "Campex hit an unexpected error while creating the ad (" + e.getClass().getSimpleName()
                    + "). Nothing was duplicated; use Retry ad creation.");
        }
    }

    private void advanceLeased(Map<String, Object> ad, Map<String, Object> row, UUID clientId) {
        UUID adId = (UUID) ad.get("id");
        UUID executionId = (UUID) row.get("id");
        int position = (Integer) ad.get("position");
        CampaignConfig config = read(String.valueOf(row.get("config")));
        MetaAccess token = metaAccess.resolve(clientId, null);
        if (!token.available()) {
            fail(ad, clientId, "Meta connection unavailable: " + token.note());
            return;
        }
        Creative creative = creative((UUID) ad.get("creative_asset_id"), (Integer) ad.get("creative_version"));
        String label = label(String.valueOf(row.get("publish_tag")), position);
        String account = config.adAccountId();
        String status = String.valueOf(ad.get("status"));
        String videoId = (String) ad.get("meta_video_id");
        String imageHash = (String) ad.get("meta_image_hash");
        String creativeId = (String) ad.get("meta_creative_id");
        String adName = adName(config.name(), label);
        String who = position == 0 ? "" : "Ad " + (position + 1) + ": ";
        try {
            if ("pending".equals(status) || "uploading".equals(status)) {
                setStatus(adId, executionId, clientId, "uploading");
                if (imageHash == null) {
                    imageHash = meta.uploadImage(token.token(), account, adName + " image", creative.imageBytes());
                    jdbc.update("UPDATE campaign_execution_ads SET meta_image_hash = ? WHERE id = ?", imageHash, adId);
                    step(executionId, "ad_media", "ok", who + "Image uploaded to the ad account", Map.of("image_hash", imageHash));
                }
                if (creative.videoPath() != null && videoId == null) {
                    videoId = meta.uploadVideo(token.token(), account, adName, creative.videoPath(), creative.videoMime());
                    jdbc.update("UPDATE campaign_execution_ads SET meta_video_id = ? WHERE id = ?", videoId, adId);
                    step(executionId, "ad_media", "ok", who + "Video uploaded; Meta is processing it", Map.of("video_id", videoId));
                }
                status = creative.videoPath() != null ? "processing" : "creating";
                setStatus(adId, executionId, clientId, status);
            }
            if ("processing".equals(status)) {
                String state = meta.videoStatus(token.token(), videoId);
                if ("error".equals(state)) {
                    fail(ad, clientId, "Meta could not process the video. Re-export it as H.264 MP4 and upload it again.");
                    return;
                }
                if (!"ready".equals(state)) {
                    int checks = jdbc.queryForObject("UPDATE campaign_execution_ads SET attempts = attempts + 1 WHERE id = ? RETURNING attempts",
                            Integer.class, adId);
                    if (checks > MAX_PROCESSING_CHECKS) {
                        fail(ad, clientId, "Meta is still processing the video after 30 minutes; retry later.");
                    } else {
                        jdbc.update("UPDATE campaign_execution_ads SET next_check_at = ? WHERE id = ?", Timestamp.from(Instant.now().plus(PROCESSING_POLL)), adId);
                    }
                    return;
                }
                status = "creating";
                setStatus(adId, executionId, clientId, status);
            }
            if ("creating".equals(status)) {
                if (creativeId == null) {
                    Copy copy = position == 0
                            ? new Copy(config.headline(), config.primaryText(), config.description(), config.cta())
                            : copy((UUID) ad.get("creative_asset_id"), (Integer) ad.get("creative_version"), config.cta());
                    creativeId = meta.createCreative(token.token(), new CreativeSpec(account, config.pageId(), creative.videoPath() != null ? "video" : "image",
                            adName, config.destinationUrl(), copy.primaryText(), copy.headline(), copy.description(), copy.cta(), imageHash, videoId));
                    if (creativeId == null || creativeId.isBlank()) {
                        fail(ad, clientId, "Meta did not return an ad creative ID.");
                        return;
                    }
                    jdbc.update("UPDATE campaign_execution_ads SET meta_creative_id = ? WHERE id = ?", creativeId, adId);
                    step(executionId, "ad_creative", "ok", who + "Ad creative created", Map.of("creative_id", creativeId));
                }
                String adsetId = String.valueOf(row.get("meta_adset_id"));
                List<String> existing = meta.findAds(token.token(), adsetId, "[" + label + "]");
                String metaAdId = existing.isEmpty() ? meta.createAd(token.token(), account, adsetId, creativeId, adName) : existing.getFirst();
                if (metaAdId == null || metaAdId.isBlank()) {
                    fail(ad, clientId, "Meta did not return an ad ID.");
                    return;
                }
                String finalCreative = creativeId;
                tx.executeWithoutResult(s -> {
                    jdbc.update("UPDATE campaign_execution_ads SET status = 'created', meta_ad_id = ?, error = NULL, next_check_at = NULL, "
                            + "updated_at = ? WHERE id = ?", metaAdId, Timestamp.from(Instant.now()), adId);
                    step(executionId, "ad_create", "ok", who + (existing.isEmpty() ? "Ad created (paused)" : "Ad already existed in Meta; linked it"),
                            Map.of("ad_id", metaAdId, "creative_id", finalCreative));
                    audit.record(clientId, "system:ad-builder", "campaign_execution", executionId, "ad_created",
                            Map.of("ad_id", metaAdId, "position", position + 1));
                    summarize(executionId, clientId);
                });
            }
        } catch (GatewayException e) {
            fail(ad, clientId, e.getMessage());
        }
    }

    /** Publish tag for the first ad, tag-2 … tag-6 for the others; the bracketed label is unique per ad. */
    static String label(String tag, int position) {
        return position == 0 ? tag : tag + "-" + (position + 1);
    }

    /** Meta keeps 100 characters of an ad name; the campaign name is shortened so the tag is never cut off. */
    static String adName(String campaignName, String label) {
        String suffix = " [" + label + "]";
        String name = campaignName == null ? "Campex ad" : campaignName.trim();
        int room = 100 - suffix.length();
        return (name.length() > room ? name.substring(0, room).trim() : name) + suffix;
    }

    record Copy(String headline, String primaryText, String description, String cta) {
    }

    private Copy copy(UUID assetId, Integer version, String fallbackCta) {
        Map<String, Object> v = jdbc.queryForMap("SELECT headline, primary_text, description, cta FROM creative_versions WHERE asset_id = ? AND version = ?",
                assetId, version);
        String cta = (String) v.get("cta");
        return new Copy((String) v.get("headline"), (String) v.get("primary_text"), (String) v.get("description"),
                cta == null || cta.isBlank() ? fallbackCta : cta);
    }

    /**
     * Rolls the ads up into the campaign's ad_status / ad_error / meta_* columns, and notifies once when every ad has
     * finished (all created, or some failed).
     */
    private void summarize(UUID executionId, UUID clientId) {
        List<Map<String, Object>> ads = jdbc.queryForList("SELECT * FROM campaign_execution_ads WHERE execution_id = ? ORDER BY position", executionId);
        if (ads.isEmpty()) return;
        Map<String, Object> before = jdbc.queryForMap("SELECT ad_status, name FROM campaign_executions WHERE id = ?", executionId);
        List<String> statuses = ads.stream().map(a -> String.valueOf(a.get("status"))).toList();
        String summary = null;
        for (String s : List.of("processing", "uploading", "creating", "pending")) {
            if (statuses.contains(s)) { summary = s; break; }
        }
        if (summary == null) summary = statuses.contains("failed") ? "failed" : "created";
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> a : ads) {
            if ("failed".equals(a.get("status")) && a.get("error") != null) {
                int pos = (Integer) a.get("position");
                errors.add(ads.size() == 1 ? String.valueOf(a.get("error")) : "Ad " + (pos + 1) + ": " + a.get("error"));
            }
        }
        long created = statuses.stream().filter("created"::equals).count();
        Map<String, Object> first = ads.getFirst();
        jdbc.update("UPDATE campaign_executions SET ad_status = ?, ad_error = ?, meta_ad_id = COALESCE(?, meta_ad_id), meta_video_id = ?, "
                        + "meta_creative_id = ?, meta_image_hash = ?, updated_at = ? WHERE id = ?",
                summary, errors.isEmpty() ? null : String.join(" ", errors), first.get("meta_ad_id"), first.get("meta_video_id"),
                first.get("meta_creative_id"), first.get("meta_image_hash"), Timestamp.from(Instant.now()), executionId);
        String was = String.valueOf(before.get("ad_status"));
        String name = String.valueOf(before.get("name"));
        if ("created".equals(summary) && !"created".equals(was)) {
            notifications.notifyClientManagers(clientId, null, "campaign_ad_created", "info",
                    (ads.size() == 1 ? "Ad created (paused): " : created + " ads created (paused): ") + name,
                    "The ads are in Meta, paused. Activate the campaign from Campaign approvals when you are ready.", "campaign_execution", executionId);
        } else if ("failed".equals(summary) && !"failed".equals(was)) {
            notifications.notifyClientManagers(clientId, null, "campaign_ad_failed", "warning",
                    created > 0 ? created + " of " + ads.size() + " ads created: " + name : "Ad not created",
                    String.join(" ", errors) + " The campaign stays paused; retry from Campaign approvals.", "campaign_execution", executionId);
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

    private void setStatus(UUID adId, UUID executionId, UUID clientId, String status) {
        tx.executeWithoutResult(t -> {
            jdbc.update("UPDATE campaign_execution_ads SET status = ?, error = NULL, updated_at = ? WHERE id = ?", status, Timestamp.from(Instant.now()), adId);
            summarize(executionId, clientId);
        });
    }

    private void fail(Map<String, Object> ad, UUID clientId, String error) {
        UUID adId = (UUID) ad.get("id");
        UUID executionId = (UUID) ad.get("execution_id");
        int position = (Integer) ad.get("position");
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE campaign_execution_ads SET status = 'failed', error = ?, next_check_at = NULL, updated_at = ? WHERE id = ?",
                    error, Timestamp.from(Instant.now()), adId);
            step(executionId, "ad_create", "failed", (position == 0 ? "" : "Ad " + (position + 1) + ": ") + error, null);
            summarize(executionId, clientId);
        });
    }

    private UUID clientOf(UUID executionId) {
        return jdbc.queryForList("SELECT client_id FROM campaign_executions WHERE id = ?", UUID.class, executionId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Campaign execution not found"));
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
