package com.aivoice.lead.controller;

import com.aivoice.lead.dto.Dtos.AdLeadRequest;
import com.aivoice.lead.dto.Dtos.AdLeadResponse;
import com.aivoice.lead.dto.Dtos.DashboardDto;
import com.aivoice.lead.dto.Dtos.LanguageUpdate;
import com.aivoice.lead.dto.Dtos.LeadDto;
import com.aivoice.lead.dto.Dtos.LeadPage;
import com.aivoice.lead.dto.Dtos.LeadReviewStats;
import com.aivoice.lead.dto.Dtos.LeadReviewUpdate;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.LeadService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard reads and the public ad-popup endpoint. */
@RestController
@RequestMapping("/api")
public class LeadController {

    private final LeadService leads;

    public LeadController(LeadService leads) {
        this.leads = leads;
    }

    @GetMapping("/dashboard")
    public DashboardDto dashboard(@RequestParam(required = false, name = "client_id") String clientId) {
        return leads.dashboard(optionalId(clientId));
    }

    @GetMapping("/leads")
    public LeadPage list(
            @RequestParam(required = false, name = "client_id") String clientId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "review_status") String reviewStatus,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") long offset) {
        return leads.listLeads(optionalId(clientId), status, reviewStatus, language, query,
                clamp(limit), Math.max(offset, 0));
    }

    @GetMapping("/leads/review-stats")
    public LeadReviewStats reviewStats(@RequestParam(required = false, name = "client_id") String clientId) {
        return leads.leadReviewStats(optionalId(clientId));
    }

    @GetMapping("/leads/{id}")
    public LeadDto get(@PathVariable String id) {
        return leads.getLead(parse(id));
    }

    @PatchMapping("/leads/{id}/language")
    public LeadDto updateLanguage(@PathVariable String id, @Valid @RequestBody LanguageUpdate body) {
        return leads.updateLanguage(parse(id), body.language());
    }

    @PatchMapping("/leads/{id}/review")
    public LeadDto updateReview(@PathVariable String id, @Valid @RequestBody LeadReviewUpdate body) {
        return leads.updateReviewStatus(parse(id), body.reviewStatus());
    }

    @PostMapping("/ad-leads")
    public AdLeadResponse createAdLead(@Valid @RequestBody AdLeadRequest body) {
        return leads.registerAdLead(body);
    }

    static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }

    /** An optional client filter; blank means "all clients". */
    static UUID optionalId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Client not found");
        }
    }

    static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Lead not found");
        }
    }
}
