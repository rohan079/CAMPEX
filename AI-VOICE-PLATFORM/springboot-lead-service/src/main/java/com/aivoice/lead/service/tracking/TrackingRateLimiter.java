package com.aivoice.lead.service.tracking;

import com.aivoice.lead.service.Exceptions.RateLimitedException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Fixed one-minute windows per key, in memory. Good enough for a single Railway instance; behind several instances
 * each instance enforces its own share (documented limitation).
 */
@Component
public class TrackingRateLimiter {

    private record Window(long minute, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public TrackingRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String key, int limitPerMinute) {
        long minute = clock.millis() / 60_000;
        Window window = windows.compute(key, (k, existing) ->
                existing == null || existing.minute() != minute ? new Window(minute, new AtomicInteger()) : existing);
        if (window.count().incrementAndGet() > limitPerMinute) {
            throw new RateLimitedException("Too many tracking requests", 60 - (clock.millis() / 1000) % 60);
        }
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        }
    }
}
