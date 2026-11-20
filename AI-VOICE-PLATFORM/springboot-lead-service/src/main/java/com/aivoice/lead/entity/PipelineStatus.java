package com.aivoice.lead.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Operational sales pipeline for a lead. Separate from the call lifecycle ({@code leads.status}) and the
 * quality review ({@code leads.review_status}), neither of which it changes.
 */
public enum PipelineStatus {
    NEW, CONTACTED, QUALIFIED, SITE_VISIT_SCHEDULED, SITE_VISIT_COMPLETED, NEGOTIATION, WON, LOST;

    private static final Map<PipelineStatus, Set<PipelineStatus>> ALLOWED = Map.of(
            NEW, EnumSet.of(CONTACTED, QUALIFIED, SITE_VISIT_SCHEDULED, LOST),
            CONTACTED, EnumSet.of(QUALIFIED, SITE_VISIT_SCHEDULED, NEGOTIATION, LOST),
            QUALIFIED, EnumSet.of(CONTACTED, SITE_VISIT_SCHEDULED, NEGOTIATION, LOST),
            SITE_VISIT_SCHEDULED, EnumSet.of(SITE_VISIT_COMPLETED, QUALIFIED, NEGOTIATION, LOST),
            SITE_VISIT_COMPLETED, EnumSet.of(SITE_VISIT_SCHEDULED, NEGOTIATION, WON, LOST),
            NEGOTIATION, EnumSet.of(SITE_VISIT_SCHEDULED, WON, LOST),
            // A won deal can still fall through (cancelled booking); a lost lead can be reopened.
            WON, EnumSet.of(LOST),
            LOST, EnumSet.of(NEW, CONTACTED, QUALIFIED));

    public boolean canMoveTo(PipelineStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<PipelineStatus> nextStatuses() {
        return ALLOWED.get(this);
    }

    /** Position in the funnel, used to auto-advance only forwards (never backwards). */
    public boolean isBefore(PipelineStatus other) {
        return this != LOST && other != LOST && ordinal() < other.ordinal();
    }

    public static PipelineStatus parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Pipeline status is required");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown pipeline status: " + value);
        }
    }
}
