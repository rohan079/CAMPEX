package com.aivoice.lead.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Automation rules ({@code campex.automation.*}). */
@ConfigurationProperties(prefix = "campex.automation")
public record AutomationProperties(
        /** Scheduled rule evaluation. Rules can still be run manually when this is off. */
        @DefaultValue("true") boolean enabled,
        /**
         * Level 4 (PREAPPROVED): rules may pause a campaign without a person approving each change. Off by default; even
         * when on, only "pause" (reversible, spend-reducing) is possible, within each rule's daily cap.
         */
        @DefaultValue("false") boolean preapprovedActionsEnabled) {
}
