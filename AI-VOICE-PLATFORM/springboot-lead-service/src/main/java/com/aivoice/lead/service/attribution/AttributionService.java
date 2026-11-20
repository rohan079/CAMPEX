package com.aivoice.lead.service.attribution;

import com.aivoice.lead.dto.AttributionDtos.AttributedLeadPage;
import com.aivoice.lead.dto.AttributionDtos.AttributedLeadRow;
import com.aivoice.lead.dto.AttributionDtos.AttributionInput;
import com.aivoice.lead.dto.AttributionDtos.AttributionSummary;
import com.aivoice.lead.dto.AttributionDtos.LeadAttributionDto;
import com.aivoice.lead.dto.AttributionDtos.NameCount;
import com.aivoice.lead.dto.AttributionDtos.TouchDto;
import com.aivoice.lead.entity.ConversionEvent;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadAttribution;
import com.aivoice.lead.entity.LeadAttributionTouch;
import com.aivoice.lead.repository.AdRepository;
import com.aivoice.lead.repository.ConversionEventRepository;
import com.aivoice.lead.repository.LeadAttributionRepository;
import com.aivoice.lead.repository.LeadAttributionTouchRepository;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.attribution.AttributionResolver.Resolved;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records attribution touches and keeps first-touch (immutable) and last-touch pointers per lead.
 * History lives in {@code lead_attribution_touches}; nothing there is ever updated.
 */
@Service
public class AttributionService {

    private final LeadAttributionRepository attributions;
    private final LeadAttributionTouchRepository touches;
    private final ConversionEventRepository events;
    private final AdRepository ads;
    private final LeadRepository leads;
    private final ClientAccessService access;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AttributionService(LeadAttributionRepository attributions, LeadAttributionTouchRepository touches,
                              ConversionEventRepository events, AdRepository ads, LeadRepository leads,
                              ClientAccessService access, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.attributions = attributions;
        this.touches = touches;
        this.events = events;
        this.ads = ads;
        this.leads = leads;
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Adds one touch for a lead. The first touch ever recorded stays the first touch; a newer touch moves
     * last-touch. A touch identical to the current last touch is not repeated.
     */
    @Transactional
    public LeadAttributionTouch recordTouch(Lead lead, String source, AttributionInput input) {
        AttributionInput evidence = input == null ? AttributionInput.empty() : input;
        Instant now = clock.instant();
        UUID conversionEventId = null;
        if (evidence.visitorId() != null && !evidence.visitorId().isBlank()) {
            events.linkVisitorToLead(lead.getClientId(), evidence.visitorId(), lead.getId());
            Optional<ConversionEvent> visit = visitorEvidence(lead.getClientId(), evidence.visitorId());
            if (visit.isPresent()) {
                conversionEventId = visit.get().getId();
                evidence = mergeVisitorEvidence(evidence, visit.get());
            }
        }
        Resolved resolved = AttributionResolver.resolve(source, evidence, lead.getAdId(),
                adId -> registeredAdName(lead.getClientId(), adId));

        Optional<LeadAttribution> current = attributions.lockByLeadId(lead.getId());
        if (current.isPresent()) {
            LeadAttributionTouch last = touches.findById(current.get().getLastTouchId()).orElse(null);
            if (last != null && sameSignal(last, resolved)) {
                return last;
            }
        }

        LeadAttributionTouch touch = toTouch(lead, resolved, conversionEventId, now);
        touch = touches.save(touch);
        if (current.isEmpty()) {
            LeadAttribution attribution = new LeadAttribution();
            attribution.setClientId(lead.getClientId());
            attribution.setLeadId(lead.getId());
            attribution.setFirstTouchId(touch.getId());
            attribution.setFirstTouchAt(touch.getTouchedAt());
            attribution.setLastTouchId(touch.getId());
            attribution.setLastTouchAt(touch.getTouchedAt());
            attribution.setTouchCount(1);
            attribution.setAttributionSource(touch.getSource());
            attributions.save(attribution);
        } else {
            LeadAttribution attribution = current.get();
            // A known campaign never gets replaced as last touch by an unknown/direct visit.
            if (!resolved.isUnknown() && !touch.getTouchedAt().isBefore(attribution.getLastTouchAt())) {
                attribution.setLastTouchId(touch.getId());
                attribution.setLastTouchAt(touch.getTouchedAt());
            }
            attribution.setTouchCount(attribution.getTouchCount() + 1);
            attributions.save(attribution);
        }
        return touch;
    }

    /** Most recent website event of the visitor that carries campaign, click or UTM evidence. */
    private Optional<ConversionEvent> visitorEvidence(UUID clientId, String visitorId) {
        return events.findTop20ByClientIdAndVisitorIdOrderByEventTimeDesc(clientId, visitorId).stream()
                .filter(e -> e.getCampaignId() != null || e.getAdId() != null || e.getFbc() != null
                        || e.getFbclid() != null || (e.getPayload() != null && e.getPayload().get("utm") != null))
                .findFirst();
    }

    /** Fills gaps in the submitted evidence from the visitor's tracked event; submitted values always win. */
    @SuppressWarnings("unchecked")
    static AttributionInput mergeVisitorEvidence(AttributionInput in, ConversionEvent e) {
        Map<String, Object> utm = e.getPayload() != null && e.getPayload().get("utm") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        Object landing = e.getPayload() == null ? null : e.getPayload().get("landing_page");
        return new AttributionInput(
                in.source(), or(in.campaignId(), e.getCampaignId()), in.campaignName(), or(in.adsetId(), e.getAdsetId()),
                in.adsetName(), or(in.adId(), e.getAdId()), in.adName(), in.metaFormId(), in.metaLeadId(),
                or(in.utmSource(), str(utm.get("utm_source"))), or(in.utmMedium(), str(utm.get("utm_medium"))),
                or(in.utmCampaign(), str(utm.get("utm_campaign"))), or(in.utmContent(), str(utm.get("utm_content"))),
                or(in.utmTerm(), str(utm.get("utm_term"))), or(in.fbclid(), e.getFbclid()), or(in.fbc(), e.getFbc()),
                or(in.fbp(), e.getFbp()), or(in.landingPage(), str(landing)), or(in.referrer(), e.getReferrerUrl()),
                in.visitorId(), or(in.sessionId(), e.getSessionId()));
    }

    private Optional<String> registeredAdName(UUID clientId, String adId) {
        return ads.findByExternalId(adId)
                .filter(ad -> ad.getClientId().equals(clientId))
                .map(ad -> ad.getName() == null || ad.getName().isBlank() ? ad.getExternalId() : ad.getName());
    }

    static boolean sameSignal(LeadAttributionTouch t, Resolved r) {
        return Objects.equals(t.getSource(), r.source()) && Objects.equals(t.getCampaignId(), r.campaignId())
                && Objects.equals(t.getAdsetId(), r.adsetId()) && Objects.equals(t.getAdId(), r.adId())
                && Objects.equals(t.getUtmSource(), r.utmSource()) && Objects.equals(t.getUtmCampaign(), r.utmCampaign())
                && Objects.equals(t.getMetaLeadId(), r.metaLeadId()) && Objects.equals(t.getFbc(), r.fbc());
    }

    private static LeadAttributionTouch toTouch(Lead lead, Resolved r, UUID conversionEventId, Instant at) {
        LeadAttributionTouch t = new LeadAttributionTouch();
        t.setClientId(lead.getClientId());
        t.setLeadId(lead.getId());
        t.setSource(r.source());
        t.setResolutionMethod(r.method());
        t.setCampaignId(r.campaignId());
        t.setCampaignName(r.campaignName());
        t.setAdsetId(r.adsetId());
        t.setAdsetName(r.adsetName());
        t.setAdId(r.adId());
        t.setAdName(r.adName());
        t.setMetaFormId(r.metaFormId());
        t.setMetaLeadId(r.metaLeadId());
        t.setUtmSource(r.utmSource());
        t.setUtmMedium(r.utmMedium());
        t.setUtmCampaign(r.utmCampaign());
        t.setUtmContent(r.utmContent());
        t.setUtmTerm(r.utmTerm());
        t.setFbclid(r.fbclid());
        t.setFbc(r.fbc());
        t.setFbp(r.fbp());
        t.setLandingPage(r.landingPage());
        t.setReferrer(r.referrer());
        t.setConversionEventId(conversionEventId);
        t.setTouchedAt(at);
        return t;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public LeadAttributionDto forLead(UUID leadId) {
        Lead lead = leads.findById(leadId).orElseThrow(() -> new NotFoundException("Lead not found"));
        access.read(lead.getClientId());
        List<LeadAttributionTouch> history = touches.findByLeadIdOrderByTouchedAtAsc(leadId);
        Optional<LeadAttribution> attribution = attributions.findByLeadId(leadId);
        if (attribution.isEmpty()) {
            return new LeadAttributionDto(leadId.toString(), lead.getClientId().toString(), "unknown", 0, null, null,
                    List.of());
        }
        LeadAttribution a = attribution.get();
        TouchDto first = history.stream().filter(t -> t.getId().equals(a.getFirstTouchId())).findFirst()
                .map(AttributionService::toDto).orElse(null);
        TouchDto last = history.stream().filter(t -> t.getId().equals(a.getLastTouchId())).findFirst()
                .map(AttributionService::toDto).orElse(null);
        return new LeadAttributionDto(leadId.toString(), lead.getClientId().toString(), a.getAttributionSource(),
                a.getTouchCount(), first, last, history.stream().map(AttributionService::toDto).toList());
    }

    @Transactional(readOnly = true)
    public AttributionSummary summary(UUID clientId, Instant from, Instant to) {
        access.read(clientId);
        MapSqlParameterSource p = params(clientId, from, to);
        String base = " " + """
                 from leads l
                 left join lead_attributions a on a.lead_id = l.id
                 left join lead_attribution_touches ft on ft.id = a.first_touch_id
                 left join lead_attribution_touches lt on lt.id = a.last_touch_id
                 where l.client_id = :cid and l.created_at >= :f and l.created_at < :t""";
        Map<String, Object> totals = jdbc.queryForMap("""
                select count(*) total,
                       count(*) filter (where ft.id is null or ft.resolution_method = 'unknown') unknown,
                       count(*) filter (where ft.id is not null and lt.id is not null
                                        and coalesce(ft.campaign_id, ft.ad_id, '') <> coalesce(lt.campaign_id, lt.ad_id, '')) differ
                """ + " " + base, p);
        List<NameCount> bySource = jdbc.query("select coalesce(ft.source, 'unknown') k, count(*) n" + base
                + " group by k order by n desc", p, (rs, i) -> new NameCount(rs.getString("k"), rs.getLong("n")));
        List<NameCount> byMethod = jdbc.query("select coalesce(ft.resolution_method, 'unknown') k, count(*) n" + base
                + " group by k order by n desc", p, (rs, i) -> new NameCount(rs.getString("k"), rs.getLong("n")));
        long total = ((Number) totals.get("total")).longValue();
        long unknown = ((Number) totals.get("unknown")).longValue();
        double pct = total == 0 ? 0 : Math.round((double) unknown / total * 10_000) / 100.0;
        return new AttributionSummary(total, total - unknown, unknown, pct, ((Number) totals.get("differ")).longValue(),
                bySource, byMethod);
    }

    @Transactional(readOnly = true)
    public AttributedLeadPage leads(UUID clientId, Instant from, Instant to, String campaignId, String source,
                                    boolean unknownOnly, int limit, long offset) {
        access.read(clientId);
        MapSqlParameterSource p = params(clientId, from, to).addValue("lim", limit).addValue("off", offset);
        StringBuilder where = new StringBuilder(" " + """
                 from leads l
                 left join lead_attributions a on a.lead_id = l.id
                 left join lead_attribution_touches ft on ft.id = a.first_touch_id
                 left join lead_attribution_touches lt on lt.id = a.last_touch_id
                 where l.client_id = :cid and l.created_at >= :f and l.created_at < :t""");
        if (campaignId != null && !campaignId.isBlank()) {
            where.append(" and (ft.campaign_id = :camp or lt.campaign_id = :camp)");
            p.addValue("camp", campaignId);
        }
        if (source != null && !source.isBlank()) {
            where.append(" and ft.source = :src");
            p.addValue("src", source);
        }
        if (unknownOnly) {
            where.append(" and (ft.id is null or ft.resolution_method = 'unknown')");
        }
        Long total = jdbc.queryForObject("select count(*)" + where, p, Long.class);
        List<AttributedLeadRow> rows = jdbc.query("""
                select l.id, coalesce(nullif(l.first_name, ''), l.phone_number) name, l.pipeline_status,
                       coalesce(ft.source, 'unknown') source, coalesce(ft.resolution_method, 'unknown') method,
                       ft.campaign_id fc, coalesce(ft.campaign_name, ft.utm_campaign) fcl, ft.ad_id fa,
                       lt.campaign_id lc, coalesce(lt.campaign_name, lt.utm_campaign) lcl, lt.ad_id la,
                       coalesce(a.touch_count, 0) touches, a.first_touch_at, a.last_touch_at
                """ + " " + where + " order by l.created_at desc limit :lim offset :off", p,
                (rs, i) -> new AttributedLeadRow(rs.getObject("id", UUID.class).toString(), rs.getString("name"),
                        rs.getString("pipeline_status"), rs.getString("source"), rs.getString("method"),
                        rs.getString("fc"), rs.getString("fcl"), rs.getString("fa"), rs.getString("lc"),
                        rs.getString("lcl"), rs.getString("la"), rs.getInt("touches"),
                        instant(rs.getTimestamp("first_touch_at")), instant(rs.getTimestamp("last_touch_at"))));
        return new AttributedLeadPage(rows, total == null ? 0 : total, limit, offset);
    }

    private static MapSqlParameterSource params(UUID clientId, Instant from, Instant to) {
        return new MapSqlParameterSource("cid", clientId).addValue("f", Timestamp.from(from))
                .addValue("t", Timestamp.from(to));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    static TouchDto toDto(LeadAttributionTouch t) {
        return new TouchDto(t.getId().toString(), t.getSource(), t.getResolutionMethod(), t.getCampaignId(),
                t.getCampaignName(), t.getAdsetId(), t.getAdsetName(), t.getAdId(), t.getAdName(), t.getMetaFormId(),
                t.getMetaLeadId(), t.getUtmSource(), t.getUtmMedium(), t.getUtmCampaign(), t.getUtmContent(),
                t.getUtmTerm(), t.getFbclid() != null, t.getFbc() != null, t.getFbp() != null, t.getLandingPage(),
                t.getReferrer(), t.getTouchedAt());
    }

    private static String or(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

}
