package com.aivoice.lead.controller;

import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.dto.AttributionDtos.AttributedLeadPage;
import com.aivoice.lead.dto.AttributionDtos.AttributionSummary;
import com.aivoice.lead.dto.AttributionDtos.CampaignReport;
import com.aivoice.lead.dto.AttributionDtos.LeadAttributionDto;
import com.aivoice.lead.service.attribution.AttributionService;
import com.aivoice.lead.service.measurement.MeasurementService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Campaign-to-lead attribution reports. */
@RestController
public class AttributionController {

    private final AttributionService attribution;
    private final MeasurementService measurement;
    private final ZoneId zone;

    public AttributionController(AttributionService attribution, MeasurementService measurement, AppProperties props) {
        this.attribution = attribution;
        this.measurement = measurement;
        this.zone = props.zone();
    }

    @GetMapping("/api/clients/{clientId}/attribution/summary")
    public AttributionSummary summary(@PathVariable String clientId, @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        Instant[] range = Range.of(from, to, zone);
        return attribution.summary(Ids.uuid(clientId, "Client"), range[0], range[1]);
    }

    /** {@code model}: first_touch (default) | last_touch. {@code group_by}: campaign (default) | adset | ad. */
    @GetMapping("/api/clients/{clientId}/attribution/campaigns")
    public CampaignReport campaigns(@PathVariable String clientId, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "first_touch") String model,
                                    @RequestParam(defaultValue = "campaign", name = "group_by") String groupBy,
                                    @RequestParam(required = false, name = "ad_account_id") String adAccountId) {
        Instant[] range = Range.of(from, to, zone);
        return measurement.campaignReport(Ids.uuid(clientId, "Client"), range[0], range[1], model, groupBy, adAccountId);
    }

    @GetMapping("/api/clients/{clientId}/attribution/leads")
    public AttributedLeadPage leads(@PathVariable String clientId, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false, name = "campaign_id") String campaignId,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(defaultValue = "false", name = "unknown_only") boolean unknownOnly,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") long offset) {
        Instant[] range = Range.of(from, to, zone);
        return attribution.leads(Ids.uuid(clientId, "Client"), range[0], range[1], campaignId, source, unknownOnly,
                Ids.limit(limit), Math.max(offset, 0));
    }

    @GetMapping("/api/leads/{leadId}/attribution")
    public LeadAttributionDto forLead(@PathVariable String leadId) {
        return attribution.forLead(Ids.uuid(leadId, "Lead"));
    }

    /** Default reporting window: the last 30 days. */
    static final class Range {
        private Range() {
        }

        static Instant[] of(String from, String to, ZoneId zone) {
            Instant end = to == null || to.isBlank() ? Instant.now() : Ids.instant(to, zone, true);
            Instant start = from == null || from.isBlank() ? end.minus(Duration.ofDays(30)) : Ids.instant(from, zone, false);
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("from must be before to");
            }
            if (Duration.between(start, end).toDays() > 400) {
                throw new IllegalArgumentException("The date range can be at most 400 days");
            }
            return new Instant[] {start, end};
        }
    }

}
