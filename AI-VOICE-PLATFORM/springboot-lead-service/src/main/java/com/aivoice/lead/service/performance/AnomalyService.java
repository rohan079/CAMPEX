package com.aivoice.lead.service.performance;

import com.aivoice.lead.config.PerformanceProperties;
import com.aivoice.lead.dto.AnomalyDtos.AnomalyDto;
import com.aivoice.lead.dto.AnomalyDtos.DetectionResult;
import com.aivoice.lead.dto.AnomalyDtos.StatusUpdate;
import com.aivoice.lead.dto.PerformanceDtos.DataNote;
import com.aivoice.lead.entity.CampaignAnomaly;
import com.aivoice.lead.entity.PerformanceSnapshot;
import com.aivoice.lead.repository.CampaignAnomalyRepository;
import com.aivoice.lead.repository.PerformanceSnapshotRepository;
import com.aivoice.lead.service.AuditService;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.performance.AnomalyDetector.Detected;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists detected anomalies with deduplication (one open alert per campaign+metric, updated on re-detection) and
 * a cooldown after resolution. Also checks Conversions API delivery failures and missing Meta data.
 */
@Service
public class AnomalyService {

    private static final Set<String> OPEN = Set.of("open", "acknowledged");
    private static final List<String> SEVERITY = List.of("INFO", "WARNING", "CRITICAL");

    private final CampaignAnomalyRepository anomalies;
    private final PerformanceSnapshotRepository snapshots;
    private final CrmOutcomeRepository crm;
    private final PerformanceService performance;
    private final PerformanceAiClient ai;
    private final ClientAccessService access;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final PerformanceProperties props;
    private final Clock clock;

    public AnomalyService(CampaignAnomalyRepository anomalies, PerformanceSnapshotRepository snapshots,
                          CrmOutcomeRepository crm, PerformanceService performance, PerformanceAiClient ai,
                          ClientAccessService access, AuditService audit, NamedParameterJdbcTemplate jdbc,
                          PerformanceProperties props, Clock clock) {
        this.anomalies = anomalies;
        this.snapshots = snapshots;
        this.crm = crm;
        this.performance = performance;
        this.ai = ai;
        this.access = access;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** Runs detection for a client. No access check: callers (controller, scheduler) are responsible. */
    @Transactional
    public DetectionResult detect(UUID clientId) {
        LocalDate asOf = performance.today().minusDays(props.metaDelayDays());
        LocalDate from = asOf.minusDays(AnomalyDetector.CURRENT_DAYS + AnomalyDetector.BASELINE_DAYS - 1);
        List<PerformanceSnapshot> snaps = snapshots.findByClientIdAndLevelAndDayBetweenOrderByDayAsc(clientId, "campaign", from, asOf);
        List<DataNote> notes = new ArrayList<>();
        List<Detected> found = new ArrayList<>();
        if (snaps.isEmpty()) {
            notes.add(new DataNote("no_snapshots", "info", "No daily Meta snapshots yet; campaign anomalies need the sync to run first."));
        } else {
            var zone = performance.zone();
            found.addAll(AnomalyDetector.detect(snaps, crm.dailyByCampaign(clientId, from.atStartOfDay(zone).toInstant(),
                    asOf.plusDays(1).atStartOfDay(zone).toInstant(), zone.getId()), asOf, props.minImpressions(),
                    props.minClicks(), props.minConversions()));
            missingData(snaps, asOf).ifPresent(found::add);
        }
        trackingFailures(clientId).ifPresent(found::add);
        int created = 0;
        int updated = 0;
        int suppressed = 0;
        Instant now = clock.instant();
        for (Detected d : found) {
            String key = d.metric() + ":" + (d.campaignId() == null ? "account" : d.campaignId());
            var open = anomalies.findFirstByClientIdAndDedupKeyAndStatusIn(clientId, key, OPEN);
            if (open.isPresent()) {
                CampaignAnomaly a = open.get();
                apply(a, d);
                if (SEVERITY.indexOf(d.severity()) > SEVERITY.indexOf(a.getSeverity())) a.setSeverity(d.severity());
                a.setOccurrences(a.getOccurrences() + 1);
                a.setLastDetectedAt(now);
                anomalies.save(a);
                updated++;
                continue;
            }
            var last = anomalies.findFirstByClientIdAndDedupKeyOrderByLastDetectedAtDesc(clientId, key);
            if (last.isPresent() && last.get().getCooldownUntil() != null && last.get().getCooldownUntil().isAfter(now)) {
                suppressed++;
                continue;
            }
            CampaignAnomaly a = new CampaignAnomaly();
            a.setClientId(clientId);
            a.setDedupKey(key);
            a.setSeverity(d.severity());
            apply(a, d);
            a.setDetectedAt(now);
            a.setLastDetectedAt(now);
            anomalies.save(a);
            created++;
        }
        return new DetectionResult(found.size(), created, updated, suppressed, notes);
    }

    private static void apply(CampaignAnomaly a, Detected d) {
        a.setCampaignId(d.campaignId());
        a.setCampaignName(d.campaignName());
        a.setMetric(d.metric());
        a.setPreviousValue(d.previous());
        a.setCurrentValue(d.current());
        a.setPctChange(d.pctChange());
        a.setZScore(d.zScore());
        a.setBaselineFrom(d.baselineFrom());
        a.setBaselineTo(d.baselineTo());
        a.setCurrentFrom(d.currentFrom());
        a.setCurrentTo(d.currentTo());
        a.setDetectionReason(d.reason());
        a.setSuggestedAction(d.action());
    }

    /** The baseline had spend but Meta returned no rows for the last two complete days. */
    static java.util.Optional<Detected> missingData(List<PerformanceSnapshot> snaps, LocalDate asOf) {
        boolean baselineSpend = snaps.stream().anyMatch(s -> s.getDay().isBefore(asOf.minusDays(1)) && s.getSpend().signum() > 0);
        boolean recent = snaps.stream().anyMatch(s -> !s.getDay().isBefore(asOf.minusDays(1)));
        if (!baselineSpend || recent) return java.util.Optional.empty();
        return java.util.Optional.of(new Detected(null, null, "meta_data_availability", "WARNING", null, null, null, null,
                null, null, asOf.minusDays(1), asOf, "Meta returned no delivery rows for the last two complete days although campaigns spent before.",
                "Check that campaigns are still delivering, the Meta connection is valid and the nightly sync succeeded."));
    }

    /** Conversions API failures in the last 24 hours. */
    java.util.Optional<Detected> trackingFailures(UUID clientId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where meta_delivery_status = 'failed') failed,
                       count(*) filter (where meta_delivery_status in ('failed', 'sent')) attempted
                from conversion_events where client_id = :cid and not is_test and updated_at >= :since""",
                new MapSqlParameterSource("cid", clientId).addValue("since", Timestamp.from(clock.instant().minus(1, ChronoUnit.DAYS))));
        long failed = ((Number) row.get("failed")).longValue();
        long attempted = ((Number) row.get("attempted")).longValue();
        if (failed < 5 || attempted == 0) return java.util.Optional.empty();
        double rate = (double) failed / attempted;
        if (rate < 0.2) return java.util.Optional.empty();
        LocalDate today = performance.today();
        return java.util.Optional.of(new Detected(null, null, "event_delivery_failures", rate >= 0.5 ? "CRITICAL" : "WARNING",
                null, BigDecimal.valueOf(failed), BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP), null, null, null,
                today.minusDays(1), today, failed + " of " + attempted + " Conversions API deliveries failed in the last 24 hours.",
                "Open Tracking & events → filter by Failed to see Meta's error messages; check the pixel token and test event code."));
    }

    @Transactional(readOnly = true)
    public List<AnomalyDto> list(UUID clientId, String status, String severity) {
        access.read(clientId);
        return anomalies.findTop200ByClientIdOrderByLastDetectedAtDesc(clientId).stream()
                .filter(a -> status == null || status.isBlank() || status.equals(a.getStatus()))
                .filter(a -> severity == null || severity.isBlank() || severity.equals(a.getSeverity()))
                .map(AnomalyService::toDto).toList();
    }

    @Transactional
    public AnomalyDto updateStatus(UUID id, StatusUpdate update) {
        CampaignAnomaly a = anomalies.findById(id).orElseThrow(() -> new NotFoundException("Anomaly not found"));
        Actor actor = access.require(a.getClientId(), Permission.OPERATE);
        if (!OPEN.contains(a.getStatus())) {
            throw new ConflictException("This anomaly is already " + a.getStatus());
        }
        String from = a.getStatus();
        a.setStatus(update.status());
        if (!"acknowledged".equals(update.status())) {
            Instant now = clock.instant();
            a.setResolvedAt(now);
            a.setResolvedBy(actor.email());
            a.setCooldownUntil(now.plus(props.anomalyCooldownHours(), ChronoUnit.HOURS));
        }
        anomalies.save(a);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from);
        details.put("to", update.status());
        details.put("metric", a.getMetric());
        details.put("note", update.note());
        audit.record(a.getClientId(), actor.email(), "campaign_anomaly", a.getId(), "status_" + update.status(), details);
        return toDto(a);
    }

    /** Asks the AI to explain an anomaly using only its detected numbers. */
    @Transactional
    public AnomalyDto explain(UUID id) {
        CampaignAnomaly a = anomalies.findById(id).orElseThrow(() -> new NotFoundException("Anomaly not found"));
        access.require(a.getClientId(), Permission.OPERATE);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", a.getId().toString());
        item.put("title", a.getMetric() + " anomaly in " + (a.getCampaignName() == null ? "the account" : a.getCampaignName()));
        item.put("reasoning", a.getDetectionReason());
        item.put("recommended_action", a.getSuggestedAction());
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (a.getPreviousValue() != null) evidence.add(Map.of("label", "baseline value", "value", a.getPreviousValue()));
        if (a.getCurrentValue() != null) evidence.add(Map.of("label", "current value", "value", a.getCurrentValue()));
        if (a.getPctChange() != null) evidence.add(Map.of("label", "relative change", "value", a.getPctChange()));
        item.put("evidence", evidence);
        Map<String, Object> context = Map.of("severity", a.getSeverity(), "metric", a.getMetric(),
                "current_window", a.getCurrentFrom() + " to " + a.getCurrentTo(),
                "baseline_window", a.getBaselineFrom() == null ? "n/a" : a.getBaselineFrom() + " to " + a.getBaselineTo());
        Map<String, Object> response = ai.explain("anomaly", context, List.of(item))
                .orElseThrow(() -> new PerformanceAiClient.AiUnavailableException("No AI provider is configured"));
        if (response.get("explanations") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m
                && Boolean.TRUE.equals(m.get("grounded")) && m.get("text") instanceof String text) {
            a.setAiExplanation(text.length() > 4000 ? text.substring(0, 4000) : text);
            anomalies.save(a);
        }
        return toDto(a);
    }

    static AnomalyDto toDto(CampaignAnomaly a) {
        return new AnomalyDto(a.getId().toString(), a.getClientId().toString(), a.getCampaignId(), a.getCampaignName(),
                a.getMetric(), a.getSeverity(), a.getPreviousValue(), a.getCurrentValue(), a.getPctChange(), a.getZScore(),
                a.getBaselineFrom(), a.getBaselineTo(), a.getCurrentFrom(), a.getCurrentTo(), a.getDetectionReason(),
                a.getSuggestedAction(), a.getAiExplanation(), a.getStatus(), a.getOccurrences(), a.getDetectedAt(),
                a.getLastDetectedAt(), a.getResolvedAt(), a.getResolvedBy());
    }
}
