package com.aivoice.lead.controller;

import com.aivoice.lead.service.Exceptions.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Path/query parsing shared by the Phase 1 controllers. Malformed ids read as "not found", never as 500s. */
final class Ids {

    private Ids() {
    }

    static UUID uuid(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotFoundException(what + " not found");
        }
    }

    static UUID optionalUuid(String value, String what) {
        return value == null || value.isBlank() ? null : uuid(value, what);
    }

    /** Accepts an ISO date (start of day in {@code zone}) or an ISO instant. */
    static Instant instant(String value, ZoneId zone, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.length() == 10) {
                LocalDate day = LocalDate.parse(value);
                return (endOfDay ? day.plusDays(1) : day).atStartOfDay(zone).toInstant();
            }
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must be YYYY-MM-DD or ISO-8601 instants");
        }
    }

    static int limit(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
