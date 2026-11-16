package com.aivoice.lead.entity;

import java.util.Set;

/** Status and source values shared by leads and calls. Kept as strings so the dashboard can show them as-is. */
public final class LeadStatus {

    public static final String SOURCE_WHATSAPP = "whatsapp";
    public static final String SOURCE_AD_POPUP = "ad_popup";
    public static final String SOURCE_CTWA = "ctwa";
    public static final Set<String> AD_SOURCES = Set.of(SOURCE_AD_POPUP, SOURCE_CTWA);

    public static final String AWAITING_CONSENT = "awaiting_consent";
    public static final String AWAITING_REPLY = "awaiting_reply";
    public static final String REPLIED = "replied";
    public static final String MESSAGE_FAILED = "message_failed";
    public static final String CALL_QUEUED = "call_queued";
    public static final String CALLED_OR_CALLING = "called_or_calling";
    public static final String FOLLOW_UP_COMPLETE = "follow_up_complete";
    public static final String DO_NOT_CALL = "do_not_call";

    private LeadStatus() {
    }
}
