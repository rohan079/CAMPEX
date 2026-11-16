package com.aivoice.lead.service;

import com.aivoice.lead.entity.CallStatus;
import com.aivoice.lead.entity.VoiceCall;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * When ad-popup leads get their daily call: from the day after they signed up,
 * for {@code days} days, at most one call per local calendar day.
 */
public class FollowUpPolicy {

    public record Window(LocalDate start, LocalDate end) {
    }

    private final ZoneId zone;
    private final int days;

    public FollowUpPolicy(ZoneId zone, int days) {
        this.zone = zone;
        this.days = Math.max(days, 1);
    }

    public LocalDate localDate(Instant moment) {
        return moment.atZone(zone).toLocalDate();
    }

    public Window windowFrom(Instant now) {
        LocalDate start = localDate(now).plusDays(1);
        return new Window(start, start.plusDays(days - 1L));
    }

    public boolean shouldQueue(LocalDate today, LocalDate start, LocalDate end, List<VoiceCall> calls) {
        if (start == null || end == null || today.isBefore(start) || today.isAfter(end)) {
            return false;
        }
        for (VoiceCall call : calls) {
            if (CallStatus.BLOCKING.contains(call.getStatus())) {
                return false;
            }
            if (CallStatus.CANCELLED.equals(call.getStatus()) || call.getCreatedAt() == null) {
                continue;
            }
            if (localDate(call.getCreatedAt()).equals(today)) {
                return false;
            }
        }
        return true;
    }
}
