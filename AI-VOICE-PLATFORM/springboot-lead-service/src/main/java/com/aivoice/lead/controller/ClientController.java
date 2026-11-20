package com.aivoice.lead.controller;

import com.aivoice.lead.dto.ClientDtos.AdDto;
import com.aivoice.lead.dto.ClientDtos.ClientDto;
import com.aivoice.lead.dto.ClientDtos.CreateClientRequest;
import com.aivoice.lead.dto.ClientDtos.ReachImportRequest;
import com.aivoice.lead.dto.ClientDtos.ReachImportResult;
import com.aivoice.lead.dto.ClientDtos.RegisterAdRequest;
import com.aivoice.lead.dto.ClientDtos.UpdateClientMetaAppRequest;
import com.aivoice.lead.dto.ClientDtos.UpdateClientVoiceSettingsRequest;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The advertisers we run marketing for, their ads, and the reach numbers imported for those ads. */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientService clients;
    private final ClientAccessService access;

    public ClientController(ClientService clients, ClientAccessService access) {
        this.clients = clients;
        this.access = access;
    }

    @GetMapping
    public List<ClientDto> list() {
        return clients.list();
    }

    @PostMapping
    public ClientDto create(@Valid @RequestBody CreateClientRequest body) {
        ClientDto created = clients.create(body);
        // Phase 1 tenant model: whoever creates a client owns it.
        access.grantOwner(java.util.UUID.fromString(created.id()), access.currentActor());
        return created;
    }

    @PatchMapping("/{id}/voice-settings")
    public ClientDto voiceSettings(
            @PathVariable String id,
            @Valid @RequestBody UpdateClientVoiceSettingsRequest body) {
        return clients.updateVoiceSettings(requireId(id), body);
    }

    @PatchMapping("/{id}/meta-app")
    public ClientDto metaAppSettings(
            @PathVariable String id,
            @Valid @RequestBody UpdateClientMetaAppRequest body) {
        return clients.updateMetaAppSettings(requireId(id), body);
    }

    @GetMapping("/{id}/ads")
    public List<AdDto> ads(@PathVariable String id) {
        return clients.listAds(requireId(id));
    }

    /** Tells the platform which client owns a Meta ad id, so click-to-WhatsApp leads land on the right client. */
    @PostMapping("/{id}/ads")
    public AdDto registerAd(@PathVariable String id, @Valid @RequestBody RegisterAdRequest body) {
        return clients.registerAd(requireId(id), body);
    }

    /** Insert or replace reach rows, one per ad, location and day. */
    @PostMapping("/{id}/ad-reach")
    public ReachImportResult importReach(@PathVariable String id, @Valid @RequestBody ReachImportRequest body) {
        return new ReachImportResult(clients.importReach(requireId(id), body.rows()));
    }

    private static UUID requireId(String id) {
        UUID parsed = LeadController.optionalId(id);
        if (parsed == null) {
            throw new com.aivoice.lead.service.Exceptions.NotFoundException("Client not found");
        }
        return parsed;
    }
}
