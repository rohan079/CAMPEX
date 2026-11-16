package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.entity.CallStatus;
import com.aivoice.lead.entity.VoiceCall;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class FollowUpPolicyTest {

    private final FollowUpPolicy policy = new FollowUpPolicy(ZoneId.of("Asia/Kolkata"), 7);

    private static VoiceCall call(String status, String createdAt) {
        VoiceCall call = new VoiceCall();
        call.setStatus(status);
        call.setCreatedAt(Instant.parse(createdAt));
        return call;
    }

    @Test
    void windowStartsTomorrowInBusinessTimezoneAndLastsSevenDays() {
        // 20:00 UTC on 29 Oct is already 30 Oct 01:30 in India, so the window starts 31 Oct.
        FollowUpPolicy.Window window = policy.windowFrom(Instant.parse("2026-10-29T20:00:00Z"));
        assertEquals(LocalDate.of(2026, 10, 31), window.start());
        assertEquals(LocalDate.of(2026, 11, 6), window.end());
    }

    @Test
    void noCallOnTheDayOfSignUp() {
        LocalDate start = LocalDate.of(2026, 10, 31);
        LocalDate end = LocalDate.of(2026, 11, 6);
        assertFalse(policy.shouldQueue(LocalDate.of(2026, 10, 30), start, end, List.of()));
        assertTrue(policy.shouldQueue(LocalDate.of(2026, 10, 31), start, end, List.of()));
        assertTrue(policy.shouldQueue(LocalDate.of(2026, 11, 6), start, end, List.of()));
        assertFalse(policy.shouldQueue(LocalDate.of(2026, 11, 7), start, end, List.of()));
    }

    @Test
    void atMostOneCallPerLocalDay() {
        LocalDate day = LocalDate.of(2026, 11, 1);
        LocalDate start = LocalDate.of(2026, 10, 31);
        LocalDate end = LocalDate.of(2026, 11, 6);
        // A failed call earlier the same local day still counts as that day's attempt.
        List<VoiceCall> sameDay = List.of(call(CallStatus.FAILED, "2026-11-01T04:00:00Z"));
        assertFalse(policy.shouldQueue(day, start, end, sameDay));
        // Yesterday's call does not.
        List<VoiceCall> yesterday = List.of(call(CallStatus.COMPLETED, "2026-10-31T04:00:00Z"));
        assertTrue(policy.shouldQueue(day, start, end, yesterday));
        // Cancelled calls never count.
        List<VoiceCall> cancelled = List.of(call(CallStatus.CANCELLED, "2026-11-01T04:00:00Z"));
        assertTrue(policy.shouldQueue(day, start, end, cancelled));
    }

    @Test
    void anActiveOrQueuedCallBlocksAnotherOne() {
        LocalDate day = LocalDate.of(2026, 11, 1);
        LocalDate start = LocalDate.of(2026, 10, 31);
        LocalDate end = LocalDate.of(2026, 11, 6);
        assertFalse(policy.shouldQueue(day, start, end, List.of(call(CallStatus.QUEUED, "2026-10-31T04:00:00Z"))));
        assertFalse(policy.shouldQueue(day, start, end, List.of(call(CallStatus.RINGING, "2026-10-31T04:00:00Z"))));
        assertFalse(policy.shouldQueue(day, start, end,
                List.of(call(CallStatus.INITIATION_UNKNOWN, "2026-10-31T04:00:00Z"))));
    }
}
