package com.aivoice.lead.service.tracking;

import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.MetaEventDelivery;
import com.aivoice.lead.entity.TrackingPixel;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.repository.MetaEventDeliveryRepository;
import com.aivoice.lead.repository.TrackingPixelRepository;
import com.aivoice.lead.service.tracking.MetaCapiPayloadBuilder.InvalidEventException;
import com.aivoice.lead.service.tracking.MetaConversionsApiClient.Result;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database-backed outbox worker for the Meta Conversions API.
 * <ol>
 *   <li>Claims due events with {@code FOR UPDATE SKIP LOCKED} and marks them {@code sending} with a lease, so several
 *       instances never send the same event and a crashed worker's lease expires.</li>
 *   <li>Sends them grouped by pixel and test-event code.</li>
 *   <li>Records every attempt; retryable failures back off exponentially up to {@code max-attempts}, after which the
 *       event is dead-lettered as {@code failed}.</li>
 * </ol>
 */
@Component
public class MetaEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MetaEventDispatcher.class);

    private final ConversionEventRepository events;
    private final MetaEventDeliveryRepository deliveries;
    private final TrackingPixelRepository pixels;
    private final TrackingPixelService pixelService;
    private final MetaConversionsApiClient client;
    private final NamedParameterJdbcTemplate jdbc;
    private final TrackingProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MetaEventDispatcher(ConversionEventRepository events, MetaEventDeliveryRepository deliveries,
                               TrackingPixelRepository pixels, TrackingPixelService pixelService,
                               MetaConversionsApiClient client, NamedParameterJdbcTemplate jdbc,
                               TrackingProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.events = events;
        this.deliveries = deliveries;
        this.pixels = pixels;
        this.pixelService = pixelService;
        this.client = client;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${campex.tracking.dispatch-interval-ms:5000}", initialDelayString = "15000")
    public void run() {
        if (!props.capiEnabled()) {
            return;
        }
        try {
            dispatchOnce();
        } catch (RuntimeException e) {
            log.warn("Conversions API dispatch cycle failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One claim-and-send cycle. Returns how many events were processed. */
    public int dispatchOnce() {
        List<UUID> claimed = tx.execute(status -> claim());
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        List<ConversionEvent> batch = events.findAllById(claimed);
        Map<String, List<ConversionEvent>> groups = batch.stream().collect(Collectors.groupingBy(
                e -> e.getPixelId() + "|" + Objects.toString(e.getTestEventCode(), ""), LinkedHashMap::new,
                Collectors.toList()));
        for (List<ConversionEvent> group : groups.values()) {
            deliverGroup(group);
        }
        return batch.size();
    }

    List<UUID> claim() {
        Instant now = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("lease", Timestamp.from(now.plusSeconds(props.dispatchLockSeconds())))
                .addValue("n", props.dispatchBatchSize());
        return jdbc.query("""
                with due as (
                  select id from conversion_events
                  where (meta_delivery_status in ('pending', 'retry') and (next_attempt_at is null or next_attempt_at <= :now))
                     or (meta_delivery_status = 'sending' and locked_until < :now)
                  order by received_at
                  limit :n
                  for update skip locked)
                update conversion_events e
                set meta_delivery_status = 'sending', locked_until = :lease,
                    delivery_attempts = e.delivery_attempts + 1, updated_at = :now
                from due where e.id = due.id
                returning e.id""", params, (rs, i) -> rs.getObject("id", UUID.class));
    }

    void deliverGroup(List<ConversionEvent> group) {
        ConversionEvent first = group.get(0);
        Optional<TrackingPixel> pixel = first.getPixelId() == null ? Optional.empty()
                : pixels.findById(first.getPixelId()).filter(p -> p.isEnabled() && p.getDeletedAt() == null);
        Optional<String> token = pixel.flatMap(pixelService::capiToken);
        if (pixel.isEmpty() || token.isEmpty()) {
            tx.executeWithoutResult(s -> group.forEach(e -> finish(e, "disabled", "Pixel disabled or no Conversions API token", null)));
            return;
        }

        Instant oldest = clock.instant().minus(Duration.ofDays(props.maxEventAgeDays()));
        List<ConversionEvent> sendable = new ArrayList<>();
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ConversionEvent event : group) {
            if (event.getEventTime().isBefore(oldest)) {
                tx.executeWithoutResult(s -> recordAttempt(event, nonRetryable("Event older than Meta's 7-day window")));
                continue;
            }
            try {
                payloads.add(MetaCapiPayloadBuilder.build(event));
                sendable.add(event);
            } catch (InvalidEventException e) {
                tx.executeWithoutResult(s -> recordAttempt(event, nonRetryable(e.getMessage())));
            }
        }
        if (sendable.isEmpty()) {
            return;
        }
        Result result = client.send(pixel.get().getMetaPixelId(), token.get(), payloads, first.getTestEventCode());
        if (!result.success() && !result.retryable() && sendable.size() > 1) {
            // One invalid event rejects the whole request; isolate it by sending the rest one at a time.
            for (int i = 0; i < sendable.size(); i++) {
                ConversionEvent event = sendable.get(i);
                Result single = client.send(pixel.get().getMetaPixelId(), token.get(), List.of(payloads.get(i)),
                        first.getTestEventCode());
                tx.executeWithoutResult(s -> recordAttempt(event, single));
            }
            return;
        }
        tx.executeWithoutResult(s -> sendable.forEach(event -> recordAttempt(event, result)));
    }

    private static Result nonRetryable(String message) {
        return new Result(false, false, null, null, null, null, null, message, Map.of());
    }

    void recordAttempt(ConversionEvent claimed, Result result) {
        ConversionEvent event = events.findById(claimed.getId()).orElse(null);
        if (event == null) {
            return; // deleted by retention or lead removal meanwhile
        }
        Instant now = clock.instant();
        String outcome;
        if (result.success()) {
            outcome = "sent";
        } else if (result.retryable() && event.getDeliveryAttempts() < props.maxAttempts()) {
            outcome = "retry";
        } else {
            outcome = "failed";
        }
        MetaEventDelivery delivery = new MetaEventDelivery();
        delivery.setConversionEventId(event.getId());
        delivery.setClientId(event.getClientId());
        delivery.setAttemptNumber(event.getDeliveryAttempts());
        delivery.setHttpStatus(result.httpStatus());
        delivery.setEventsReceived(result.eventsReceived());
        delivery.setFbtraceId(result.fbtraceId());
        delivery.setErrorCode(result.errorCode());
        delivery.setErrorSubcode(result.errorSubcode());
        delivery.setErrorMessage(result.errorMessage());
        delivery.setRetryable(result.retryable());
        delivery.setOutcome(outcome);
        delivery.setResponseMetadata(result.metadata() == null || result.metadata().isEmpty() ? null : result.metadata());
        delivery.setAttemptedAt(now);
        deliveries.save(delivery);

        switch (outcome) {
            case "sent" -> {
                event.setSentAt(now);
                finish(event, "sent", null, null);
            }
            case "retry" -> finish(event, "retry", result.errorMessage(), now.plus(backoff(event.getDeliveryAttempts())));
            default -> {
                log.warn("Conversions API event {} dead-lettered after {} attempt(s) (http {}, code {})",
                        event.getId(), event.getDeliveryAttempts(), result.httpStatus(), result.errorCode());
                finish(event, "failed", result.errorMessage(), null);
            }
        }
    }

    private void finish(ConversionEvent claimed, String status, String error, Instant nextAttempt) {
        ConversionEvent event = events.findById(claimed.getId()).orElse(null);
        if (event == null) {
            return;
        }
        if (claimed.getSentAt() != null) {
            event.setSentAt(claimed.getSentAt());
        }
        event.setMetaDeliveryStatus(status);
        event.setLastError(error == null ? null : (error.length() > 480 ? error.substring(0, 480) : error));
        event.setNextAttemptAt(nextAttempt);
        event.setLockedUntil(null);
        events.save(event);
    }

    /** base × 2^(attempt−1), capped. Attempt 1 → base, 2 → 2×base, … */
    Duration backoff(int attempt) {
        long seconds = (long) props.baseBackoffSeconds() << Math.min(Math.max(attempt - 1, 0), 20);
        return Duration.ofSeconds(Math.min(seconds, props.maxBackoffSeconds()));
    }
}
