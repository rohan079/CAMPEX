package com.aivoice.lead.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Lead-to-sale lifecycle. A booking is never treated as realized revenue. */
public enum SaleStatus {
    NEW, BOOKING_PENDING, BOOKED, PAYMENT_PENDING, PARTIALLY_PAID, FULLY_PAID, CANCELLED, REFUNDED;

    private static final Map<SaleStatus, Set<SaleStatus>> ALLOWED = Map.of(
            NEW, EnumSet.of(BOOKING_PENDING, BOOKED, CANCELLED),
            BOOKING_PENDING, EnumSet.of(BOOKED, CANCELLED),
            BOOKED, EnumSet.of(PAYMENT_PENDING, PARTIALLY_PAID, FULLY_PAID, CANCELLED),
            PAYMENT_PENDING, EnumSet.of(PARTIALLY_PAID, FULLY_PAID, CANCELLED),
            PARTIALLY_PAID, EnumSet.of(FULLY_PAID, CANCELLED, REFUNDED),
            FULLY_PAID, EnumSet.of(PARTIALLY_PAID, CANCELLED, REFUNDED),
            CANCELLED, EnumSet.of(REFUNDED),
            REFUNDED, EnumSet.noneOf(SaleStatus.class));

    public boolean canMoveTo(SaleStatus next) {
        return this == next || ALLOWED.get(this).contains(next);
    }

    /** Statuses at which a booking exists (counted as a booking in KPIs). */
    public boolean isBooked() {
        return this == BOOKED || this == PAYMENT_PENDING || this == PARTIALLY_PAID || this == FULLY_PAID;
    }

    public boolean isClosed() {
        return this == CANCELLED || this == REFUNDED;
    }

    public static SaleStatus parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Sale status is required");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown sale status: " + value);
        }
    }
}
