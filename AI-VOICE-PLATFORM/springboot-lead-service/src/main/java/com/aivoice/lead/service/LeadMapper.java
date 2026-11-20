package com.aivoice.lead.service;

import com.aivoice.lead.dto.Dtos.CallDto;
import com.aivoice.lead.dto.Dtos.LatestCallDto;
import com.aivoice.lead.dto.Dtos.LeadDto;
import com.aivoice.lead.entity.CallStatus;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.VoiceCall;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LeadMapper {

    private static final Comparator<VoiceCall> NEWEST_FIRST =
            Comparator.comparing(VoiceCall::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();

    public CallDto toCallDto(VoiceCall call, Lead lead) {
        return new CallDto(
                call.getId().toString(),
                call.getLeadId().toString(),
                lead == null ? null : lead.getFirstName(),
                lead == null ? null : lead.getPhoneNumber(),
                call.getStatus(),
                call.getLanguage(),
                call.getOutcome(),
                call.getSummary(),
                call.getAttempts(),
                call.getOpenaiSessionId(),
                call.getStartedAt(),
                call.getEndedAt(),
                call.getCreatedAt());
    }

    /** A list row: newest call only. */
    public LeadDto toSummary(Lead lead, List<VoiceCall> calls) {
        return build(lead, calls, false);
    }

    /** The drawer: full call history and whether the language can still change for a live call. */
    public LeadDto toDetail(Lead lead, List<VoiceCall> calls) {
        return build(lead, calls, true);
    }

    private LeadDto build(Lead lead, List<VoiceCall> calls, boolean detailed) {
        List<VoiceCall> ordered = calls.stream().sorted(NEWEST_FIRST).toList();
        LatestCallDto latest = ordered.isEmpty() ? null : toLatest(ordered.get(0));
        List<CallDto> callDtos = detailed ? ordered.stream().map(c -> toCallDto(c, lead)).toList() : null;
        Boolean locked = detailed ? ordered.stream().anyMatch(c -> CallStatus.ACTIVE.contains(c.getStatus())) : null;
        return new LeadDto(
                lead.getId().toString(),
                lead.getClientId().toString(),
                lead.getPhoneNumber(),
                lead.getFirstName(),
                lead.getStatus(),
                lead.getReviewStatus(),
                lead.getPreferredLanguage(),
                lead.getCampaignId(),
                lead.getAdId(),
                lead.getAdHeadline(),
                lead.getAdBody(),
                lead.getSource(),
                lead.getOutreachSentAt(),
                lead.getRepliedAt(),
                lead.getFollowUpStart(),
                lead.getFollowUpEnd(),
                lead.getInitialMessage(),
                lead.getCreatedAt(),
                lead.getUpdatedAt(),
                latest,
                callDtos,
                locked,
                lead.getPipelineStatus(),
                lead.getAssignedAgentId() == null ? null : lead.getAssignedAgentId().toString());
    }

    private LatestCallDto toLatest(VoiceCall call) {
        return new LatestCallDto(call.getId().toString(), call.getStatus(), call.getLanguage(),
                call.getOutcome(), call.getStartedAt());
    }
}
