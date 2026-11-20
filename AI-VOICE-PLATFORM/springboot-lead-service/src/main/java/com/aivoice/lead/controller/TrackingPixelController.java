package com.aivoice.lead.controller;

import com.aivoice.lead.dto.TrackingDtos.PixelDto;
import com.aivoice.lead.dto.TrackingDtos.PixelRequest;
import com.aivoice.lead.service.tracking.TrackingPixelService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Meta Pixel configuration per client. Responses never include the Conversions API token. */
@RestController
@RequestMapping("/api/clients/{clientId}/tracking/pixels")
public class TrackingPixelController {

    private final TrackingPixelService pixels;

    public TrackingPixelController(TrackingPixelService pixels) {
        this.pixels = pixels;
    }

    @GetMapping
    public List<PixelDto> list(@PathVariable String clientId) {
        return pixels.list(Ids.uuid(clientId, "Client"));
    }

    @PostMapping
    public PixelDto create(@PathVariable String clientId, @Valid @RequestBody PixelRequest body) {
        return pixels.create(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/{pixelId}")
    public PixelDto get(@PathVariable String clientId, @PathVariable String pixelId) {
        return pixels.get(Ids.uuid(clientId, "Client"), Ids.uuid(pixelId, "Pixel"));
    }

    @PutMapping("/{pixelId}")
    public PixelDto update(@PathVariable String clientId, @PathVariable String pixelId,
                           @Valid @RequestBody PixelRequest body) {
        return pixels.update(Ids.uuid(clientId, "Client"), Ids.uuid(pixelId, "Pixel"), body);
    }

    @DeleteMapping("/{pixelId}")
    public Map<String, String> delete(@PathVariable String clientId, @PathVariable String pixelId) {
        pixels.delete(Ids.uuid(clientId, "Client"), Ids.uuid(pixelId, "Pixel"));
        return Map.of("status", "deleted");
    }
}
