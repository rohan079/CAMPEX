package com.aivoice.lead.controller;

import com.aivoice.lead.dto.CampaignExecutionDtos.DecisionRequest;
import com.aivoice.lead.dto.CreativeDtos.ApproveRequest;
import com.aivoice.lead.dto.CreativeDtos.AssetDetail;
import com.aivoice.lead.dto.CreativeDtos.AssetSummary;
import com.aivoice.lead.dto.CreativeDtos.CopyBrief;
import com.aivoice.lead.dto.CreativeDtos.EditRequest;
import com.aivoice.lead.dto.CreativeDtos.GenerateImageRequest;
import com.aivoice.lead.dto.CreativeDtos.UploadRequest;
import com.aivoice.lead.service.creative.CreativeStudioService;
import com.aivoice.lead.service.creative.CreativeStudioService.ImageBytes;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 3 Creative Studio. Access is enforced in the service. */
@RestController
public class CreativeStudioController {

    private final CreativeStudioService creative;

    public CreativeStudioController(CreativeStudioService creative) {
        this.creative = creative;
    }

    @PostMapping("/api/clients/{clientId}/creative/copy")
    public Map<String, Object> copy(@PathVariable String clientId, @Valid @RequestBody CopyBrief body) {
        return creative.generateCopy(Ids.uuid(clientId, "Client"), body);
    }

    @PostMapping("/api/clients/{clientId}/creative/generate")
    public AssetDetail generate(@PathVariable String clientId, @Valid @RequestBody GenerateImageRequest body) {
        return creative.generateImage(Ids.uuid(clientId, "Client"), body);
    }

    @PostMapping("/api/clients/{clientId}/creative/assets")
    public AssetDetail upload(@PathVariable String clientId, @Valid @RequestBody UploadRequest body) {
        return creative.upload(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/clients/{clientId}/creative/assets")
    public List<AssetSummary> list(@PathVariable String clientId, @RequestParam(required = false) String status) {
        return creative.list(Ids.uuid(clientId, "Client"), status);
    }

    @GetMapping("/api/creative/assets/{id}")
    public AssetDetail get(@PathVariable String id) {
        return creative.get(asset(id));
    }

    @PutMapping("/api/creative/assets/{id}")
    public AssetDetail edit(@PathVariable String id, @Valid @RequestBody EditRequest body) {
        return creative.edit(asset(id), body);
    }

    @PostMapping("/api/creative/assets/{id}/submit")
    public AssetDetail submit(@PathVariable String id) {
        return creative.submit(asset(id));
    }

    @PostMapping("/api/creative/assets/{id}/approve")
    public AssetDetail approve(@PathVariable String id, @Valid @RequestBody(required = false) ApproveRequest body) {
        return creative.approve(asset(id), body);
    }

    @PostMapping("/api/creative/assets/{id}/reject")
    public AssetDetail reject(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return creative.reject(asset(id), body == null ? null : body.note());
    }

    @PostMapping("/api/creative/assets/{id}/archive")
    public AssetDetail archive(@PathVariable String id) {
        return creative.archive(asset(id));
    }

    @GetMapping("/api/creative/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String imageId) {
        ImageBytes image = creative.image(Ids.uuid(imageId, "Image"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    private static java.util.UUID asset(String id) {
        return Ids.uuid(id, "Creative");
    }
}
