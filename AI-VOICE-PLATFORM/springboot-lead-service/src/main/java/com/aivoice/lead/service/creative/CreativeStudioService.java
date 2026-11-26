package com.aivoice.lead.service.creative;

import com.aivoice.lead.dto.CreativeDtos.ApprovalDto;
import com.aivoice.lead.dto.CreativeDtos.ApproveRequest;
import com.aivoice.lead.dto.CreativeDtos.AssetDetail;
import com.aivoice.lead.dto.CreativeDtos.AssetSummary;
import com.aivoice.lead.dto.CreativeDtos.ClaimCheck;
import com.aivoice.lead.dto.CreativeDtos.CopyBrief;
import com.aivoice.lead.dto.CreativeDtos.EditRequest;
import com.aivoice.lead.dto.CreativeDtos.GenerateImageRequest;
import com.aivoice.lead.dto.CreativeDtos.UploadRequest;
import com.aivoice.lead.dto.CreativeDtos.VersionDto;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.creative.CreativeAiGateway.GeneratedImage;
import com.aivoice.lead.service.execution.CampaignValidator;
import com.aivoice.lead.service.execution.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creative Studio: AI copy suggestions, artwork generation/upload, immutable versions and an approval workflow.
 *
 * <p>Status: GENERATED (new AI artwork) → EDITING (any change) → REVIEW_PENDING (submitted, claims checked) →
 * APPROVED / REJECTED; ARCHIVED hides it. An approval names one version. Prohibited claims (guarantees, assured
 * returns…) block approval; numbers that are not in the verified facts need an explicit acknowledgement that the
 * approver verified them. Artwork is never counted as a factual claim.
 */
@Service
public class CreativeStudioService {

    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final String ENTITY = "creative_asset";
    private static final Set<String> EDITABLE = Set.of("GENERATED", "EDITING", "REVIEW_PENDING", "APPROVED", "REJECTED");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ClientAccessService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final CreativeAiGateway ai;
    private final ObjectMapper json;
    private final com.aivoice.lead.service.media.MediaStorage media;

    public CreativeStudioService(JdbcTemplate jdbc, TransactionTemplate tx, ClientAccessService access, AuditService audit,
                                 NotificationService notifications, CreativeAiGateway ai, ObjectMapper json,
                                 com.aivoice.lead.service.media.MediaStorage media) {
        this.media = media;
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.ai = ai;
        this.json = json;
    }

    // ------------------------------------------------------------------------------------------------ generation

    /** Copy suggestions only; nothing is stored until the user saves a version. */
    public Map<String, Object> generateCopy(UUID clientId, CopyBrief brief) {
        access.require(clientId, Permission.MANAGE);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("business_type", orDefault(brief.businessType(), "real estate"));
        b.put("ad_type", orDefault(brief.adType(), "property_launch"));
        b.put("product", brief.product());
        b.put("audience", orDefault(brief.audience(), ""));
        b.put("location", orDefault(brief.location(), ""));
        b.put("objective", orDefault(brief.objective(), "leads"));
        b.put("platform", orDefault(brief.platform(), "facebook_instagram"));
        b.put("language", orDefault(brief.language(), "en"));
        b.put("tone", orDefault(brief.tone(), "trustworthy"));
        b.put("offer", orDefault(brief.offer(), ""));
        b.put("cta", orDefault(brief.cta(), ""));
        Map<String, Object> result = new LinkedHashMap<>(ai.copy(b, facts(brief.facts())));
        // Provider/model names are internal details; the dashboard shows "AI-generated".
        result.remove("provider");
        result.remove("model");
        return result;
    }

    public AssetDetail generateImage(UUID clientId, GenerateImageRequest req) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        if (req.referenceBase64() != null && req.referenceMime() == null) {
            throw new IllegalArgumentException("reference_mime is required with a reference image");
        }
        if (req.referenceBase64() != null) {
            decode(req.referenceBase64());
        }
        GeneratedImage image = ai.image(req.prompt(), req.referenceBase64(), req.referenceMime());
        byte[] bytes = decode(image.dataBase64());
        return createAsset(clientId, actor, req.name(), req.adType(), bytes, image.mimeType(), "ai_generated",
                image.prompt(), image.model(), facts(req.facts()), "GENERATED");
    }

    public AssetDetail upload(UUID clientId, UploadRequest req) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        byte[] bytes = decode(req.imageBase64());
        requireImage(bytes, req.mimeType());
        return createAsset(clientId, actor, req.name(), req.adType(), bytes, req.mimeType(), "uploaded", null, null,
                facts(req.facts()), "EDITING");
    }

    private AssetDetail createAsset(UUID clientId, Actor actor, String name, String adType, byte[] bytes, String mime,
                                    String source, String prompt, String model, List<String> facts, String status) {
        UUID assetId = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            Timestamp now = now();
            UUID imageId = storeImage(clientId, bytes, mime, source, prompt, model, actor.email());
            jdbc.update("INSERT INTO creative_assets (id, client_id, name, ad_type, status, current_version, created_by, "
                            + "updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)",
                    assetId, clientId, name.trim(), orDefault(adType, "property_launch"), status, actor.email(),
                    actor.email(), now, now);
            jdbc.update("INSERT INTO creative_versions (id, asset_id, version, image_id, facts, created_by, created_at, "
                            + "change_note) VALUES (?, ?, 1, ?, ?::jsonb, ?, ?, ?)",
                    UUID.randomUUID(), assetId, imageId, write(facts), actor.email(), now,
                    "ai_generated".equals(source) ? "AI-generated artwork" : "Uploaded artwork");
            audit.record(clientId, actor.email(), ENTITY, assetId, "created", Map.of("source", source));
        });
        return detail(assetId);
    }

    /**
     * Video creative: the file is streamed to the media directory, checked (MP4/MOV container, duration within the
     * limit), and stored with a thumbnail image that Meta requires for video ads.
     */
    public AssetDetail uploadVideo(UUID clientId, String name, String adType, List<String> facts,
                                   org.springframework.web.multipart.MultipartFile video,
                                   org.springframework.web.multipart.MultipartFile thumbnail) {
        Actor actor = access.require(clientId, Permission.MANAGE);
        if (video == null || video.isEmpty()) throw new IllegalArgumentException("Choose a video file");
        if (thumbnail == null || thumbnail.isEmpty()) throw new IllegalArgumentException("A thumbnail image is required for video ads");
        if (name == null || name.isBlank() || name.length() > 160) throw new IllegalArgumentException("Name the creative (max 160 characters)");
        byte[] thumbBytes;
        try {
            thumbBytes = thumbnail.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("The thumbnail could not be read");
        }
        String thumbMime = thumbnail.getContentType() == null ? "" : thumbnail.getContentType();
        requireImage(thumbBytes, thumbMime);
        com.aivoice.lead.service.media.MediaStorage.Stored stored;
        try (java.io.InputStream in = video.getInputStream()) {
            stored = media.storeTemp(clientId, in, "mp4");
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("The video could not be read");
        }
        com.aivoice.lead.service.media.Mp4Inspector.Info info;
        try {
            info = com.aivoice.lead.service.media.Mp4Inspector.inspect(stored.path());
        } catch (com.aivoice.lead.service.media.Mp4Inspector.InvalidVideoException e) {
            media.discard(stored);
            throw new IllegalArgumentException(e.getMessage());
        }
        if (info.durationMs() > media.maxVideoSeconds() * 1000L + 500) {
            media.discard(stored);
            throw new IllegalArgumentException("Videos can be at most " + media.maxVideoSeconds() + " seconds (this one is "
                    + Math.round(info.durationMs() / 1000.0) + " s)");
        }
        media.commit(stored);
        UUID assetId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        String original = video.getOriginalFilename() == null ? null
                : video.getOriginalFilename().replaceAll("[^\\w. -]", "_").substring(0, Math.min(200, video.getOriginalFilename().length()));
        List<String> cleanFacts = facts(facts);
        tx.executeWithoutResult(s -> {
            Timestamp now = now();
            jdbc.update("INSERT INTO creative_media (id, client_id, kind, storage, storage_key, mime_type, size_bytes, sha256, duration_ms, "
                            + "original_name, created_by, created_at) VALUES (?, ?, 'video', 'filesystem', ?, ?, ?, ?, ?, ?, ?, ?)",
                    mediaId, clientId, stored.key(), info.mimeType(), stored.size(), stored.sha256(), (int) info.durationMs(),
                    original, actor.email(), now);
            UUID imageId = storeImage(clientId, thumbBytes, thumbMime, "uploaded", null, null, actor.email());
            jdbc.update("INSERT INTO creative_assets (id, client_id, name, ad_type, status, current_version, media_type, created_by, "
                            + "updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'EDITING', 1, 'video', ?, ?, ?, ?)",
                    assetId, clientId, name.trim(), orDefault(adType, "property_launch"), actor.email(), actor.email(), now, now);
            jdbc.update("INSERT INTO creative_versions (id, asset_id, version, image_id, video_id, facts, created_by, created_at, change_note) "
                            + "VALUES (?, ?, 1, ?, ?, ?::jsonb, ?, ?, ?)",
                    UUID.randomUUID(), assetId, imageId, mediaId, write(cleanFacts), actor.email(), now,
                    "Uploaded video (" + Math.round(info.durationMs() / 1000.0) + " s)");
            audit.record(clientId, actor.email(), ENTITY, assetId, "created", Map.of("source", "uploaded_video",
                    "duration_ms", info.durationMs(), "size_bytes", stored.size()));
        });
        return detail(assetId);
    }

    public record VideoFile(java.nio.file.Path path, String mimeType, long size) {
    }

    /** A stored video for playback; only members of the owning client can read it. */
    public VideoFile video(UUID mediaId) {
        Map<String, Object> row = jdbc.queryForList("SELECT client_id, storage_key, mime_type, size_bytes FROM creative_media WHERE id = ?", mediaId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Video not found"));
        access.read((UUID) row.get("client_id"));
        return new VideoFile(media.path((String) row.get("storage_key")), (String) row.get("mime_type"), (Long) row.get("size_bytes"));
    }

    public com.aivoice.lead.dto.CreativeDtos.MediaLimits limits() {
        return new com.aivoice.lead.dto.CreativeDtos.MediaLimits(media.enabled(), media.maxVideoSeconds(), media.maxVideoMb());
    }

    // ------------------------------------------------------------------------------------------------ workflow

    public List<AssetSummary> list(UUID clientId, String status) {
        access.read(clientId);
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        String sql = SUMMARY_SQL + " WHERE a.client_id = ?" + (filter == null ? " AND a.status <> 'ARCHIVED'" : " AND a.status = ?")
                + " ORDER BY a.updated_at DESC LIMIT 200";
        return jdbc.query(sql, (rs, i) -> summary(rs), filter == null ? new Object[]{clientId} : new Object[]{clientId, filter});
    }

    public AssetDetail get(UUID assetId) {
        access.read(clientOf(assetId));
        return detail(assetId);
    }

    public record ImageBytes(byte[] bytes, String mimeType) {
    }

    public ImageBytes image(UUID imageId) {
        UUID clientId = jdbc.queryForList("SELECT client_id FROM creative_images WHERE id = ?", UUID.class, imageId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Image not found"));
        access.read(clientId);
        return jdbc.query("SELECT bytes, mime_type FROM creative_images WHERE id = ?",
                (rs, i) -> new ImageBytes(rs.getBytes("bytes"), rs.getString("mime_type")), imageId).getFirst();
    }

    public AssetDetail edit(UUID assetId, EditRequest req) {
        UUID clientId = clientOf(assetId);
        Actor actor = access.require(clientId, Permission.MANAGE);
        byte[] newImage = req.imageBase64() == null ? null : decode(req.imageBase64());
        if (newImage != null) {
            requireImage(newImage, req.imageMime());
        }
        if (req.cta() != null && !req.cta().isBlank() && !CampaignValidator.CTAS.contains(req.cta().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported call to action");
        }
        tx.executeWithoutResult(s -> {
            Map<String, Object> asset = lockAsset(assetId);
            String status = (String) asset.get("status");
            if (!EDITABLE.contains(status)) {
                throw new ConflictException("An archived creative cannot be edited");
            }
            Map<String, Object> cur = currentVersion(assetId, (Integer) asset.get("current_version"));
            UUID imageId = newImage == null ? (UUID) cur.get("image_id")
                    : storeImage(clientId, newImage, req.imageMime(), "uploaded", null, null, actor.email());
            int version = (Integer) asset.get("current_version") + 1;
            Timestamp now = now();
            // A new version keeps the video (if any); only the copy, facts or thumbnail change here.
            jdbc.update("INSERT INTO creative_versions (id, asset_id, version, image_id, video_id, headline, primary_text, description, "
                            + "cta, facts, change_note, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                    UUID.randomUUID(), assetId, version, imageId, cur.get("video_id"),
                    pick(req.headline(), cur.get("headline")), pick(req.primaryText(), cur.get("primary_text")),
                    pick(req.description(), cur.get("description")),
                    req.cta() == null ? cur.get("cta") : blankToNull(req.cta().trim().toUpperCase(Locale.ROOT)),
                    req.facts() == null ? String.valueOf(cur.get("facts")) : write(facts(req.facts())),
                    blankToNull(req.changeNote()), actor.email(), now);
            jdbc.update("UPDATE creative_assets SET current_version = ?, status = 'EDITING', name = COALESCE(?, name), "
                            + "updated_by = ?, updated_at = ? WHERE id = ?",
                    version, blankToNull(req.name()), actor.email(), now, assetId);
            audit.record(clientId, actor.email(), ENTITY, assetId, "edited", Map.of("version", version, "from_status", status));
        });
        return detail(assetId);
    }

    /** Runs the deterministic claim check on the current version and asks for review. */
    public AssetDetail submit(UUID assetId) {
        UUID clientId = clientOf(assetId);
        Actor actor = access.require(clientId, Permission.MANAGE);
        Map<String, Object> asset = jdbc.queryForMap("SELECT * FROM creative_assets WHERE id = ?", assetId);
        int version = (Integer) asset.get("current_version");
        Map<String, Object> cur = currentVersion(assetId, version);
        if (blank(cur.get("headline")) || blank(cur.get("primary_text"))) {
            throw new ConflictException("Add a headline and primary text before submitting for review");
        }
        if (cur.get("image_id") == null) {
            throw new ConflictException("Add artwork before submitting for review");
        }
        List<String> texts = new ArrayList<>();
        for (String k : List.of("headline", "primary_text", "description")) {
            if (!blank(cur.get(k))) texts.add(String.valueOf(cur.get(k)));
        }
        Map<String, Object> check = ai.check(texts, readFacts(String.valueOf(cur.get("facts"))));
        tx.executeWithoutResult(s -> {
            Map<String, Object> locked = lockAsset(assetId);
            if (!Objects.equals(locked.get("current_version"), version)) {
                throw new ConflictException("The creative changed while it was being checked; submit again");
            }
            if (!Set.of("GENERATED", "EDITING", "REJECTED").contains((String) locked.get("status"))) {
                throw new ConflictException("Only a draft creative can be submitted for review");
            }
            jdbc.update("UPDATE creative_versions SET claim_check = ?::jsonb WHERE asset_id = ? AND version = ?",
                    write(check), assetId, version);
            jdbc.update("UPDATE creative_assets SET status = 'REVIEW_PENDING', updated_by = ?, updated_at = ? WHERE id = ?",
                    actor.email(), now(), assetId);
            audit.record(clientId, actor.email(), ENTITY, assetId, "submitted", Map.of("version", version, "claim_check", check));
            notifications.notifyClientManagers(clientId, actor.email(), "creative_approval_requested", "info",
                    "Creative review requested: " + locked.get("name"), "Check the copy against the verified facts.",
                    ENTITY, assetId);
        });
        return detail(assetId);
    }

    public AssetDetail approve(UUID assetId, ApproveRequest req) {
        UUID clientId = clientOf(assetId);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            Map<String, Object> asset = lockAsset(assetId);
            if (!"REVIEW_PENDING".equals(asset.get("status"))) {
                throw new ConflictException("Only a creative awaiting review can be approved");
            }
            int version = (Integer) asset.get("current_version");
            ClaimCheck check = claimCheck(currentVersion(assetId, version).get("claim_check"));
            if (check == null) {
                throw new ConflictException("Submit the creative so its claims are checked before approval");
            }
            if (!check.flaggedPhrases().isEmpty()) {
                throw new ConflictException("Remove prohibited claims before approval: "
                        + String.join(", ", check.flaggedPhrases().stream().map(f -> f.get("text")).toList()));
            }
            boolean ack = req != null && req.claimsAcknowledged();
            if (!check.unsupportedNumbers().isEmpty() && !ack) {
                throw new ConflictException("Confirm you verified these figures before approving: "
                        + String.join(", ", check.unsupportedNumbers()));
            }
            jdbc.update("INSERT INTO creative_approvals (id, asset_id, client_id, version, decision, claims_acknowledged, "
                            + "decided_by, decided_at, note) VALUES (?, ?, ?, ?, 'approved', ?, ?, ?, ?)",
                    UUID.randomUUID(), assetId, clientId, version, ack, actor.email(), now(),
                    req == null ? null : blankToNull(req.note()));
            jdbc.update("UPDATE creative_assets SET status = 'APPROVED', approved_version = ?, updated_by = ?, updated_at = ? "
                    + "WHERE id = ?", version, actor.email(), now(), assetId);
            audit.record(clientId, actor.email(), ENTITY, assetId, "approved", Map.of("version", version, "claims_acknowledged", ack));
        });
        return detail(assetId);
    }

    public AssetDetail reject(UUID assetId, String note) {
        UUID clientId = clientOf(assetId);
        Actor actor = access.require(clientId, Permission.MANAGE);
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Say what needs to change");
        }
        tx.executeWithoutResult(s -> {
            Map<String, Object> asset = lockAsset(assetId);
            if (!"REVIEW_PENDING".equals(asset.get("status"))) {
                throw new ConflictException("Only a creative awaiting review can be rejected");
            }
            int version = (Integer) asset.get("current_version");
            jdbc.update("INSERT INTO creative_approvals (id, asset_id, client_id, version, decision, decided_by, decided_at, note) "
                    + "VALUES (?, ?, ?, ?, 'rejected', ?, ?, ?)", UUID.randomUUID(), assetId, clientId, version, actor.email(), now(), note.trim());
            jdbc.update("UPDATE creative_assets SET status = 'REJECTED', updated_by = ?, updated_at = ? WHERE id = ?",
                    actor.email(), now(), assetId);
            audit.record(clientId, actor.email(), ENTITY, assetId, "rejected", Map.of("version", version));
            notifications.notifyClientManagers(clientId, (String) asset.get("created_by"), "creative_rejected", "warning",
                    "Changes requested: " + asset.get("name"), note.trim(), ENTITY, assetId);
        });
        return detail(assetId);
    }

    public AssetDetail archive(UUID assetId) {
        UUID clientId = clientOf(assetId);
        Actor actor = access.require(clientId, Permission.MANAGE);
        tx.executeWithoutResult(s -> {
            lockAsset(assetId);
            jdbc.update("UPDATE creative_assets SET status = 'ARCHIVED', updated_by = ?, updated_at = ? WHERE id = ?",
                    actor.email(), now(), assetId);
            audit.record(clientId, actor.email(), ENTITY, assetId, "archived", Map.of());
        });
        return detail(assetId);
    }

    /** Approved copy for each approved creative of a client, used by campaign validation. */
    public Map<String, CampaignValidator.ApprovedCreative> approvedCreatives(UUID clientId) {
        Map<String, CampaignValidator.ApprovedCreative> out = new LinkedHashMap<>();
        jdbc.query("SELECT a.id, a.approved_version, v.headline, v.primary_text FROM creative_assets a "
                        + "JOIN creative_versions v ON v.asset_id = a.id AND v.version = a.approved_version "
                        + "WHERE a.client_id = ? AND a.approved_version IS NOT NULL AND a.status <> 'ARCHIVED'",
                rs -> {
                    out.put(rs.getString("id"), new CampaignValidator.ApprovedCreative(rs.getInt("approved_version"),
                            rs.getString("headline"), rs.getString("primary_text")));
                }, clientId);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final String SUMMARY_SQL = "SELECT a.*, v.image_id, v.headline, v.video_id, m.duration_ms FROM creative_assets a "
            + "LEFT JOIN creative_versions v ON v.asset_id = a.id AND v.version = a.current_version "
            + "LEFT JOIN creative_media m ON m.id = v.video_id";

    private AssetSummary summary(ResultSet rs) throws SQLException {
        return new AssetSummary(rs.getString("id"), rs.getString("client_id"), rs.getString("name"), rs.getString("ad_type"),
                rs.getString("status"), rs.getInt("current_version"), (Integer) rs.getObject("approved_version"),
                rs.getString("image_id"), rs.getString("headline"), rs.getString("created_by"),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("media_type"), rs.getString("video_id"),
                (Integer) rs.getObject("duration_ms"));
    }

    private AssetDetail detail(UUID assetId) {
        AssetSummary summary = jdbc.query(SUMMARY_SQL + " WHERE a.id = ?", (rs, i) -> summary(rs), assetId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Creative not found"));
        List<VersionDto> versions = jdbc.query("SELECT v.*, i.source, i.prompt, m.duration_ms FROM creative_versions v "
                        + "LEFT JOIN creative_images i ON i.id = v.image_id LEFT JOIN creative_media m ON m.id = v.video_id "
                        + "WHERE v.asset_id = ? ORDER BY v.version DESC",
                (rs, i) -> new VersionDto(rs.getInt("version"), rs.getString("image_id"), rs.getString("source"),
                        rs.getString("prompt"), rs.getString("headline"), rs.getString("primary_text"),
                        rs.getString("description"), rs.getString("cta"), readFacts(rs.getString("facts")),
                        claimCheck(rs.getString("claim_check")), rs.getString("change_note"), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("video_id"),
                        (Integer) rs.getObject("duration_ms")), assetId);
        List<ApprovalDto> approvals = jdbc.query("SELECT * FROM creative_approvals WHERE asset_id = ? ORDER BY decided_at DESC",
                (rs, i) -> new ApprovalDto(rs.getString("id"), rs.getInt("version"), rs.getString("decision"),
                        rs.getBoolean("claims_acknowledged"), rs.getString("decided_by"),
                        rs.getTimestamp("decided_at").toInstant(), rs.getString("note")), assetId);
        return new AssetDetail(summary, versions, approvals, allowedActions(summary.status()));
    }

    static List<String> allowedActions(String status) {
        List<String> a = new ArrayList<>();
        if (EDITABLE.contains(status)) a.add("edit");
        if (Set.of("GENERATED", "EDITING", "REJECTED").contains(status)) a.add("submit");
        if ("REVIEW_PENDING".equals(status)) { a.add("approve"); a.add("reject"); }
        if (!"ARCHIVED".equals(status)) a.add("archive");
        return a;
    }

    private UUID storeImage(UUID clientId, byte[] bytes, String mime, String source, String prompt, String model, String actor) {
        requireImage(bytes, mime);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO creative_images (id, client_id, mime_type, bytes, size_bytes, sha256, source, prompt, model, "
                        + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, clientId, mime, bytes, bytes.length, sha256(bytes), source, prompt, model, actor, now());
        return id;
    }

    /** Checks size and that the bytes really are the declared image type (magic numbers). */
    static void requireImage(byte[] bytes, String mime) {
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Images must be between 1 byte and 8 MB");
        }
        boolean ok = switch (mime == null ? "" : mime) {
            case "image/png" -> bytes.length > 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            case "image/jpeg" -> bytes.length > 3 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xD8;
            case "image/webp" -> bytes.length > 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
        if (!ok) {
            throw new IllegalArgumentException("The file is not a valid PNG, JPEG or WebP image");
        }
    }

    private static byte[] decode(String base64) {
        try {
            String clean = base64.contains(",") && base64.startsWith("data:") ? base64.substring(base64.indexOf(',') + 1) : base64;
            return Base64.getMimeDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image data is not valid base64");
        }
    }

    private Map<String, Object> lockAsset(UUID assetId) {
        return jdbc.queryForList("SELECT * FROM creative_assets WHERE id = ? FOR UPDATE", assetId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Creative not found"));
    }

    private Map<String, Object> currentVersion(UUID assetId, int version) {
        return jdbc.queryForMap("SELECT * FROM creative_versions WHERE asset_id = ? AND version = ?", assetId, version);
    }

    private UUID clientOf(UUID assetId) {
        return jdbc.queryForList("SELECT client_id FROM creative_assets WHERE id = ?", UUID.class, assetId).stream()
                .findFirst().orElseThrow(() -> new NotFoundException("Creative not found"));
    }

    private ClaimCheck claimCheck(Object raw) {
        if (raw == null) return null;
        try {
            Map<String, Object> m = json.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() { });
            List<String> numbers = new ArrayList<>();
            if (m.get("unsupported_numbers") instanceof List<?> l) l.forEach(v -> numbers.add(String.valueOf(v)));
            List<Map<String, String>> flagged = new ArrayList<>();
            if (m.get("flagged_phrases") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> f) {
                        flagged.add(Map.of("text", String.valueOf(f.get("text")), "reason", String.valueOf(f.get("reason"))));
                    }
                }
            }
            return new ClaimCheck(numbers, flagged);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> readFacts(String raw) {
        try {
            return raw == null ? List.of() : json.readValue(raw, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static List<String> facts(List<String> facts) {
        return facts == null ? List.of() : facts.stream().map(String::trim).filter(f -> !f.isEmpty()).distinct().limit(40).toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object pick(String value, Object fallback) {
        return value == null ? fallback : blankToNull(value);
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
