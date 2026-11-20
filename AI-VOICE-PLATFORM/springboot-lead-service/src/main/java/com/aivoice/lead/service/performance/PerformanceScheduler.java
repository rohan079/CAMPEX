package com.aivoice.lead.service.performance;

import com.aivoice.lead.config.PerformanceProperties;
import com.aivoice.lead.entity.ClientAdAccount;
import com.aivoice.lead.repository.ClientAdAccountRepository;
import com.aivoice.lead.repository.PerformanceRecommendationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Nightly: sync daily Meta snapshots, detect anomalies, expire stale recommendations. Read-only towards Meta. */
@Component
public class PerformanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(PerformanceScheduler.class);

    private final ClientAdAccountRepository adAccounts;
    private final PerformanceSyncService sync;
    private final AnomalyService anomalies;
    private final PerformanceRecommendationRepository recommendations;
    private final PerformanceService performance;
    private final PerformanceProperties props;
    private final Clock clock;

    public PerformanceScheduler(ClientAdAccountRepository adAccounts, PerformanceSyncService sync, AnomalyService anomalies,
                                PerformanceRecommendationRepository recommendations, PerformanceService performance,
                                PerformanceProperties props, Clock clock) {
        this.adAccounts = adAccounts;
        this.sync = sync;
        this.anomalies = anomalies;
        this.recommendations = recommendations;
        this.performance = performance;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${campex.performance.sync-cron:0 15 4 * * *}")
    public void nightly() {
        if (!props.scheduledSyncEnabled()) return;
        Set<UUID> clients = new LinkedHashSet<>();
        for (ClientAdAccount a : adAccounts.findAll()) clients.add(a.getClientId());
        LocalDate to = performance.today().minusDays(1);
        LocalDate from = to.minusDays(props.syncLookbackDays() - 1);
        for (UUID clientId : clients) {
            try {
                var result = sync.sync(clientId, null, from, to);
                performance.evict(clientId);
                var detection = anomalies.detect(clientId);
                log.info("Performance sync for client {}: {} snapshot rows, {} anomalies detected", clientId,
                        result.snapshots(), detection.detected());
            } catch (RuntimeException e) {
                log.warn("Performance sync failed for client {}: {}", clientId, e.getClass().getSimpleName());
            }
        }
        expire();
    }

    @Transactional
    public void expire() {
        int expired = recommendations.expireOverdue(clock.instant());
        if (expired > 0) log.info("Expired {} stale recommendation(s)", expired);
    }
}
