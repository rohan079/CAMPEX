package com.aivoice.lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Phase 3 Creative Studio API. */
public final class CreativeDtos {

    private CreativeDtos() {
    }

    public static final String AD_TYPES = "property_launch|apartment|villa|location|amenities|investment|site_visit|limited_offer|other";

    public record CopyBrief(
            @Size(max = 80) String businessType,
            @Pattern(regexp = AD_TYPES) String adType,
            @NotBlank @Size(max = 2000) String product,
            @Size(max = 500) String audience,
            @Size(max = 160) String location,
            @Size(max = 20) String objective,
            @Size(max = 40) String platform,
            @Pattern(regexp = "en|te|hi|kn|ta") String language,
            @Size(max = 80) String tone,
            @Size(max = 500) String offer,
            @Size(max = 40) String cta,
            @Size(max = 40) List<@Size(max = 300) String> facts) {
    }

    public record GenerateImageRequest(
            @NotBlank @Size(max = 160) String name,
            @Pattern(regexp = AD_TYPES) String adType,
            @NotBlank @Size(min = 3, max = 3000) String prompt,
            @Size(max = 12_000_000) String referenceBase64,
            @Pattern(regexp = "image/png|image/jpeg|image/webp") String referenceMime,
            @Size(max = 40) List<@Size(max = 300) String> facts) {
    }

    public record UploadRequest(
            @NotBlank @Size(max = 160) String name,
            @Pattern(regexp = AD_TYPES) String adType,
            @NotBlank @Size(max = 12_000_000) String imageBase64,
            @NotBlank @Pattern(regexp = "image/png|image/jpeg|image/webp") String mimeType,
            @Size(max = 40) List<@Size(max = 300) String> facts) {
    }

    /** A new version. Omitted fields carry over from the current version; an image replaces the artwork. */
    public record EditRequest(
            @Size(max = 160) String name,
            @Size(max = 80) String headline,
            @Size(max = 1500) String primaryText,
            @Size(max = 255) String description,
            @Size(max = 20) String cta,
            @Size(max = 40) List<@Size(max = 300) String> facts,
            @Size(max = 12_000_000) String imageBase64,
            @Pattern(regexp = "image/png|image/jpeg|image/webp") String imageMime,
            @Size(max = 300) String changeNote) {
    }

    public record ApproveRequest(@Size(max = 1000) String note, boolean claimsAcknowledged) {
    }

    public record ClaimCheck(List<String> unsupportedNumbers, List<Map<String, String>> flaggedPhrases) {
    }

    public record VersionDto(int version, String imageId, String imageSource, String imagePrompt, String headline,
                             String primaryText, String description, String cta, List<String> facts,
                             ClaimCheck claimCheck, String changeNote, String createdBy, Instant createdAt) {
    }

    public record ApprovalDto(String id, int version, String decision, boolean claimsAcknowledged, String decidedBy,
                              Instant decidedAt, String note) {
    }

    public record AssetSummary(String id, String clientId, String name, String adType, String status,
                               int currentVersion, Integer approvedVersion, String imageId, String headline,
                               String createdBy, Instant updatedAt) {
    }

    public record AssetDetail(AssetSummary asset, List<VersionDto> versions, List<ApprovalDto> approvals,
                              List<String> allowedActions) {
    }
}
