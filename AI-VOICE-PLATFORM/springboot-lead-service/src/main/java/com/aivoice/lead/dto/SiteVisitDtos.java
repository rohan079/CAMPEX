package com.aivoice.lead.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Site-visit scheduling shapes. */
public final class SiteVisitDtos {

    private SiteVisitDtos() {
    }

    public record SiteVisitDto(
            String id,
            String clientId,
            String leadId,
            String leadName,
            String leadPhone,
            String projectId,
            String projectName,
            String agentId,
            String agentName,
            Instant scheduledAt,
            int durationMinutes,
            String status,
            boolean customerConfirmed,
            Instant customerConfirmedAt,
            Instant completedAt,
            String outcome,
            String followUpNotes,
            String cancellationReason,
            String rescheduledFromId,
            Instant reminderAt,
            Instant reminderSentAt,
            Instant createdAt) {
    }

    public record CreateSiteVisit(
            @NotNull Instant scheduledAt,
            @Size(max = 64) String projectId,
            @Size(max = 64) String agentId,
            @Min(15) @Max(480) Integer durationMinutes,
            /** Minutes before the visit to raise a reminder; null = 120, 0 = none. */
            @Min(0) @Max(10080) Integer reminderMinutesBefore,
            @Size(max = 2000) String notes) {
    }

    public record SiteVisitStatusUpdate(
            @NotBlank @Pattern(regexp = "CONFIRMED|COMPLETED|CANCELLED|NO_SHOW") String status,
            @Pattern(regexp = "interested|not_interested|negotiation|booked|follow_up_needed") String outcome,
            @Size(max = 5000) String followUpNotes,
            @Size(max = 500) String cancellationReason) {
    }

    public record RescheduleRequest(@NotNull Instant scheduledAt, @Size(max = 500) String reason,
                                    @Size(max = 64) String agentId) {
    }

    public record SiteVisitPage(List<SiteVisitDto> visits, long total) {
    }
}
