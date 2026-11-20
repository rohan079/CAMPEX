package com.aivoice.lead.service.tracking;

import com.aivoice.lead.config.TrackingProperties;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.repository.TrackingConsentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Configurable retention: clears IP/user agent early, then deletes old events and consent records. */
@Component
public class TrackingRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(TrackingRetentionJob.class);

    private final ConversionEventRepository events;
    private final TrackingConsentRepository consents;
    private final TrackingProperties props;
    private final Clock clock;

    public TrackingRetentionJob(ConversionEventRepository events, TrackingConsentRepository consents,
                                TrackingProperties props, Clock clock) {
        this.events = events;
        this.consents = consents;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${campex.tracking.retention-cron:0 30 3 * * *}")
    @Transactional
    public void purge() {
        Instant now = clock.instant();
        int scrubbed = events.purgeNetworkIdentifiers(now.minus(Duration.ofDays(props.piiRetentionDays())));
        int deleted = events.deleteOlderThan(now.minus(Duration.ofDays(props.retentionDays())));
        int consentRows = consents.deleteOlderThan(now.minus(Duration.ofDays(props.retentionDays())));
        if (scrubbed + deleted + consentRows > 0) {
            log.info("Tracking retention: scrubbed {} event(s), deleted {} event(s) and {} consent record(s)",
                    scrubbed, deleted, consentRows);
        }
    }
}
