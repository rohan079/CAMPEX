package com.aivoice.lead.service.crm;

/**
 * SQL fragments defining funnel stages once, so the CRM, KPIs and attribution reports count the same way.
 * A lead "reached" a stage if its current pipeline status is at/after that stage, or its status history shows it
 * passed through that stage (so a lead that later became LOST still counts as qualified).
 */
public final class FunnelSql {

    private FunnelSql() {
    }

    public static final String CONTACTED_OR_LATER =
            "('CONTACTED','QUALIFIED','SITE_VISIT_SCHEDULED','SITE_VISIT_COMPLETED','NEGOTIATION','WON')";
    public static final String QUALIFIED_OR_LATER =
            "('QUALIFIED','SITE_VISIT_SCHEDULED','SITE_VISIT_COMPLETED','NEGOTIATION','WON')";
    public static final String BOOKED_STATUSES = "('BOOKED','PAYMENT_PENDING','PARTIALLY_PAID','FULLY_PAID')";

    /** {@code alias} is the leads table alias. */
    public static String reached(String alias, String stages) {
        return "(" + alias + ".pipeline_status in " + stages
                + " or exists (select 1 from lead_status_history h where h.lead_id = " + alias + ".id and h.to_status in "
                + stages + "))";
    }

    public static String contacted(String alias) {
        return "(" + alias + ".first_contacted_at is not null or " + reached(alias, CONTACTED_OR_LATER) + ")";
    }

    public static String qualified(String alias) {
        return reached(alias, QUALIFIED_OR_LATER);
    }
}
