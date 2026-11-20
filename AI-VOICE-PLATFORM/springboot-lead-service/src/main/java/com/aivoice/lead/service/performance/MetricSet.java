package com.aivoice.lead.service.performance;

import com.aivoice.lead.service.performance.MetaPerformanceClient.InsightRow;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Additive performance totals for one object (or an aggregate) and the ratios derived from them. Ratios are always
 * recomputed from totals — never averaged — so account ROAS is total revenue ÷ total spend, not a sum of ROAS values.
 * Reach is not additive: an aggregate keeps reach only if it was set from a single period-level Meta row.
 */
public final class MetricSet {

    public BigDecimal spend = BigDecimal.ZERO;
    public long impressions;
    public Long reach;
    public BigDecimal frequency;
    public long clicks;
    public long linkClicks;
    public long metaLeads;
    public long crmLeads;
    public long qualifiedLeads;
    public long siteVisitsScheduled;
    public long siteVisitsCompleted;
    public long bookings;
    public BigDecimal bookingValue = BigDecimal.ZERO;
    public BigDecimal confirmedRevenue = BigDecimal.ZERO;
    private int metaRows;

    public void addMeta(InsightRow row) {
        metaRows++;
        spend = spend.add(row.spend());
        impressions += row.impressions();
        clicks += row.clicks();
        linkClicks += row.linkClicks();
        metaLeads += row.metaLeads();
        if (metaRows == 1) {
            reach = row.reach();
            frequency = row.frequency();
        } else {
            reach = null;
            frequency = null;
        }
    }

    public void addCrm(CrmOutcome crm) {
        crmLeads += crm.leads();
        qualifiedLeads += crm.qualified();
        siteVisitsScheduled += crm.visitsScheduled();
        siteVisitsCompleted += crm.visitsCompleted();
        bookings += crm.bookings();
        bookingValue = bookingValue.add(crm.bookingValue());
        confirmedRevenue = confirmedRevenue.add(crm.confirmedRevenue());
    }

    public void addAll(MetricSet other) {
        spend = spend.add(other.spend);
        impressions += other.impressions;
        clicks += other.clicks;
        linkClicks += other.linkClicks;
        metaLeads += other.metaLeads;
        crmLeads += other.crmLeads;
        qualifiedLeads += other.qualifiedLeads;
        siteVisitsScheduled += other.siteVisitsScheduled;
        siteVisitsCompleted += other.siteVisitsCompleted;
        bookings += other.bookings;
        bookingValue = bookingValue.add(other.bookingValue);
        confirmedRevenue = confirmedRevenue.add(other.confirmedRevenue);
        metaRows += 2;
        reach = null;
        frequency = null;
    }

    /** Overrides reach/frequency with an exact period-level value from Meta. */
    public void setPeriodReach(Long reach, BigDecimal frequency) {
        this.reach = reach;
        this.frequency = frequency;
    }

    public BigDecimal ctr() {
        return ratio(linkClicks, impressions);
    }

    public BigDecimal cpc() {
        return money(spend, linkClicks);
    }

    public BigDecimal cpm() {
        return impressions == 0 ? null : spend.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal metaCostPerLead() {
        return money(spend, metaLeads);
    }

    public BigDecimal costPerCrmLead() {
        return money(spend, crmLeads);
    }

    public BigDecimal costPerQualifiedLead() {
        return money(spend, qualifiedLeads);
    }

    public BigDecimal costPerSiteVisit() {
        return money(spend, siteVisitsCompleted);
    }

    public BigDecimal costPerBooking() {
        return money(spend, bookings);
    }

    /** Meta-reported leads ÷ link clicks. */
    public BigDecimal conversionRate() {
        return ratio(metaLeads, linkClicks);
    }

    public BigDecimal qualifiedRate() {
        return ratio(qualifiedLeads, crmLeads);
    }

    public BigDecimal roas() {
        return spend.signum() == 0 ? null : confirmedRevenue.divide(spend, 4, RoundingMode.HALF_UP);
    }

    static BigDecimal ratio(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 6, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal amount, long count) {
        return count == 0 || amount == null ? null : amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
}
