package com.aivoice.lead.service.performance;

import java.math.BigDecimal;

/** CRM-verified outcomes attributed to one campaign/ad set/ad (first touch) in a period. */
public record CrmOutcome(long leads, long qualified, long visitsScheduled, long visitsCompleted, long bookings,
                         BigDecimal bookingValue, BigDecimal confirmedRevenue) {

    public static final CrmOutcome EMPTY = new CrmOutcome(0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
}
