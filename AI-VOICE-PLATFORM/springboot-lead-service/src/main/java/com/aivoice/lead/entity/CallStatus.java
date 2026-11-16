package com.aivoice.lead.entity;

import java.util.Set;

public final class CallStatus {

    public static final String QUEUED = "queued";
    public static final String INITIATING = "initiating";
    public static final String INITIATED = "initiated";
    public static final String RINGING = "ringing";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String BLOCKED = "blocked_no_consent";
    public static final String INITIATION_UNKNOWN = "initiation_unknown";

    /** A call that is on the line, or about to be. */
    public static final Set<String> ACTIVE = Set.of(INITIATING, INITIATED, RINGING, IN_PROGRESS);

    /** Capacity includes ambiguous starts: a carrier may still be ringing after a timeout. */
    public static final Set<String> CAPACITY = Set.of(
            INITIATING, INITIATED, RINGING, IN_PROGRESS, INITIATION_UNKNOWN);

    /** A call that stops another one from being queued for the same lead. */
    public static final Set<String> BLOCKING = Set.of(
            INITIATING, INITIATED, RINGING, IN_PROGRESS, QUEUED, INITIATION_UNKNOWN);

    public static final Set<String> FINISHED = Set.of(COMPLETED, FAILED);

    private CallStatus() {
    }
}
