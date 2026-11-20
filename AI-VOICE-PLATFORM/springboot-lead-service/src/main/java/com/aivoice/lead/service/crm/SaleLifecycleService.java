package com.aivoice.lead.service.crm;

import com.aivoice.lead.dto.SaleDtos.CancelSale;
import com.aivoice.lead.dto.SaleDtos.ConfirmSale;
import com.aivoice.lead.dto.SaleDtos.CreateSale;
import com.aivoice.lead.dto.SaleDtos.SaleDetail;
import com.aivoice.lead.dto.SaleDtos.SaleHistoryDto;
import com.aivoice.lead.dto.SaleDtos.SaleListPage;
import com.aivoice.lead.dto.SaleDtos.Totals;
import com.aivoice.lead.dto.SaleDtos.UpdateSale;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadAttribution;
import com.aivoice.lead.entity.LeadAttributionTouch;
import com.aivoice.lead.entity.PipelineStatus;
import com.aivoice.lead.entity.Sale;
import com.aivoice.lead.entity.SaleStatus;
import com.aivoice.lead.entity.SaleStatusHistory;
import com.aivoice.lead.repository.ClientProjectRepository;
import com.aivoice.lead.repository.LeadAttributionRepository;
import com.aivoice.lead.repository.LeadAttributionTouchRepository;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.OffsetPageRequest;
import com.aivoice.lead.repository.SaleRepository;
import com.aivoice.lead.repository.SaleStatusHistoryRepository;
import com.aivoice.lead.repository.SalesAgentRepository;
import com.aivoice.lead.service.ClientAccessService;
import com.aivoice.lead.service.ClientAccessService.Actor;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.tracking.ConversionEventService;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.Source;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lead-to-sale lifecycle. Money rules:
 * <ul>
 *   <li>{@code booking_value} is the agreed price; a booking is never revenue by itself.</li>
 *   <li>{@code outstanding_amount = booking_value − amount_collected} (0 once cancelled/refunded).</li>
 *   <li>{@code confirmed_revenue = amount_collected − refunded_amount}, only after explicit confirmation and never for
 *       cancelled or refunded sales. ROAS uses this figure only.</li>
 * </ul>
 * Every change is written to {@code sale_status_history}.
 */
@Service
public class SaleLifecycleService {

    public record SaleFilter(String status, String campaignId, UUID agentId, UUID projectId, LocalDate from, LocalDate to) {
    }

    private final SaleRepository sales;
    private final SaleStatusHistoryRepository history;
    private final LeadRepository leads;
    private final LeadAttributionRepository attributions;
    private final LeadAttributionTouchRepository touches;
    private final SalesAgentRepository agents;
    private final ClientProjectRepository projects;
    private final CrmSetupService setup;
    private final CrmService crm;
    private final ConversionEventService conversions;
    private final ClientAccessService access;
    private final Clock clock;
    private final String currency;
    private final java.time.ZoneId zone;

    public SaleLifecycleService(SaleRepository sales, SaleStatusHistoryRepository history, LeadRepository leads,
                                LeadAttributionRepository attributions, LeadAttributionTouchRepository touches,
                                SalesAgentRepository agents, ClientProjectRepository projects, CrmSetupService setup,
                                CrmService crm, ConversionEventService conversions, ClientAccessService access,
                                Clock clock, @Value("${app.currency:INR}") String currency,
                                com.aivoice.lead.config.AppProperties props) {
        this.sales = sales;
        this.history = history;
        this.leads = leads;
        this.attributions = attributions;
        this.touches = touches;
        this.agents = agents;
        this.projects = projects;
        this.setup = setup;
        this.crm = crm;
        this.conversions = conversions;
        this.access = access;
        this.clock = clock;
        this.currency = currency;
        this.zone = props.zone();
    }

    @Transactional
    public SaleDetail create(UUID clientId, CreateSale request) {
        Actor actor = access.require(clientId, Permission.OPERATE);
        Sale sale = new Sale();
        sale.setClientId(clientId);
        Lead lead = null;
        if (hasText(request.leadId())) {
            lead = leads.findByIdAndClientId(CrmService.parse(request.leadId(), "Lead"), clientId)
                    .orElseThrow(() -> new NotFoundException("Lead not found"));
            sale.setLeadId(lead.getId());
            sale.setAgentId(lead.getAssignedAgentId());
            sale.setProjectId(lead.getProjectId());
            // Attribution rule: a sale inherits the lead's FIRST-touch campaign/ad set/ad unless set explicitly.
            LeadAttributionTouch first = firstTouch(lead.getId());
            if (first != null) {
                sale.setCampaignId(first.getCampaignId());
                sale.setAdsetId(first.getAdsetId());
                sale.setAdId(first.getAdId());
            }
        }
        if (hasText(request.campaignId())) sale.setCampaignId(request.campaignId().trim());
        if (hasText(request.adsetId())) sale.setAdsetId(request.adsetId().trim());
        if (hasText(request.adId())) sale.setAdId(request.adId().trim());
        if (hasText(request.projectId())) sale.setProjectId(setup.requireProject(clientId, CrmService.parse(request.projectId(), "Project")).getId());
        if (hasText(request.agentId())) sale.setAgentId(setup.requireAgent(clientId, CrmService.parse(request.agentId(), "Agent")).getId());
        sale.setChannel(request.channel() == null ? "sales_team" : request.channel());
        sale.setBookingValue(request.bookingValue());
        sale.setAmount(request.bookingValue());
        sale.setAmountCollected(request.amountCollected() == null ? BigDecimal.ZERO : request.amountCollected());
        SaleStatus status = request.status() != null ? SaleStatus.parse(request.status())
                : request.bookingDate() != null ? SaleStatus.BOOKED : SaleStatus.NEW;
        if (status == SaleStatus.REFUNDED || status == SaleStatus.CANCELLED) {
            throw new IllegalArgumentException("Create the sale first, then cancel or refund it");
        }
        sale.setStatus(status.name());
        sale.setBookingDate(request.bookingDate() != null ? request.bookingDate()
                : status.isBooked() ? LocalDate.now(clock.withZone(zone)) : null);
        sale.setNote(hasText(request.note()) ? request.note().trim() : null);
        sale.setSoldAt(clock.instant());
        sale.setCreatedBy(actor.email());
        recalculate(sale);
        validateAmounts(sale);
        sale = sales.saveAndFlush(sale);
        writeHistory(sale, "created", null, sale.getStatus(), snapshot(sale), null, actor.email());
        if (lead != null) {
            crm.addActivity(lead, "sale", "Sale recorded: " + sale.getStatus() + " · " + sale.getBookingValue().toPlainString()
                    + " " + currency, Map.of("sale_id", sale.getId().toString()), actor.email(), clock.instant(), sale.getAgentId());
            if (status.isBooked()) {
                crm.transition(lead, PipelineStatus.NEGOTIATION, "Booking recorded", null, actor.email(), false);
            }
        }
        return detail(sale);
    }

    @Transactional
    public SaleDetail update(UUID saleId, UpdateSale request) {
        Sale sale = requireSale(saleId);
        Actor actor = access.require(sale.getClientId(), Permission.OPERATE);
        Map<String, Object> before = snapshot(sale);
        SaleStatus from = SaleStatus.parse(sale.getStatus());
        if (from == SaleStatus.REFUNDED) {
            throw new ConflictException("A refunded sale can no longer change");
        }
        if (request.campaignId() != null) sale.setCampaignId(blankToNull(request.campaignId()));
        if (request.adsetId() != null) sale.setAdsetId(blankToNull(request.adsetId()));
        if (request.adId() != null) sale.setAdId(blankToNull(request.adId()));
        if (hasText(request.projectId())) sale.setProjectId(setup.requireProject(sale.getClientId(), CrmService.parse(request.projectId(), "Project")).getId());
        if (hasText(request.agentId())) sale.setAgentId(setup.requireAgent(sale.getClientId(), CrmService.parse(request.agentId(), "Agent")).getId());
        if (request.channel() != null) sale.setChannel(request.channel());
        if (request.bookingValue() != null) {
            sale.setBookingValue(request.bookingValue());
            sale.setAmount(request.bookingValue());
        }
        if (request.amountCollected() != null) sale.setAmountCollected(request.amountCollected());
        if (request.refundedAmount() != null) sale.setRefundedAmount(request.refundedAmount());
        if (request.bookingDate() != null) sale.setBookingDate(request.bookingDate());
        if (request.note() != null) sale.setNote(blankToNull(request.note()));
        SaleStatus to = request.status() == null ? from : SaleStatus.parse(request.status());
        if (!from.canMoveTo(to)) {
            throw new ConflictException("Cannot move a sale from " + from + " to " + to);
        }
        if (to == SaleStatus.CANCELLED) {
            throw new IllegalArgumentException("Use POST /api/sales/{id}/cancel with a reason");
        }
        sale.setStatus(to.name());
        if (to.isBooked() && sale.getBookingDate() == null) {
            sale.setBookingDate(LocalDate.now(clock.withZone(zone)));
        }
        recalculate(sale);
        validateAmounts(sale);
        sale = sales.save(sale);
        Map<String, Object> changes = diff(before, snapshot(sale));
        if (!changes.isEmpty()) {
            writeHistory(sale, from == to ? "updated" : "status_changed", from.name(), to.name(), changes,
                    request.reason(), actor.email());
        }
        if (from != to && sale.getLeadId() != null) {
            leads.findById(sale.getLeadId()).ifPresent(lead -> crm.addActivity(lead, "sale",
                    "Sale " + from + " → " + to, Map.of("sale_id", saleId.toString()), actor.email(), clock.instant(), null));
        }
        return detail(sale);
    }

    /** Confirms a booked sale as verified revenue (manager action). Emits Purchase for the linked lead. */
    @Transactional
    public SaleDetail confirm(UUID saleId, ConfirmSale request) {
        Sale sale = requireSale(saleId);
        Actor actor = access.require(sale.getClientId(), Permission.MANAGE);
        SaleStatus status = SaleStatus.parse(sale.getStatus());
        if (!status.isBooked()) {
            throw new ConflictException("Only booked sales can be confirmed (current status " + status + ")");
        }
        Map<String, Object> before = snapshot(sale);
        if (request != null && request.amountCollected() != null) {
            sale.setAmountCollected(request.amountCollected());
        }
        if (sale.getAmountCollected().signum() <= 0) {
            throw new ConflictException("Record the amount collected before confirming revenue");
        }
        if (request != null && hasText(request.note())) {
            sale.setNote(request.note().trim());
        }
        // Payment status follows the collected amount.
        SaleStatus derived = sale.getAmountCollected().compareTo(sale.getBookingValue()) >= 0
                ? SaleStatus.FULLY_PAID : SaleStatus.PARTIALLY_PAID;
        if (!status.canMoveTo(derived)) {
            throw new ConflictException("Cannot move a sale from " + status + " to " + derived);
        }
        sale.setStatus(derived.name());
        sale.setSaleConfirmedAt(clock.instant());
        sale.setConfirmedBy(actor.email());
        recalculate(sale);
        validateAmounts(sale);
        sale = sales.save(sale);
        writeHistory(sale, "confirmed", status.name(), derived.name(), diff(before, snapshot(sale)), null, actor.email());
        if (sale.getLeadId() != null) {
            Sale confirmed = sale;
            leads.findById(sale.getLeadId()).ifPresent(lead -> {
                crm.addActivity(lead, "sale", "Sale confirmed · revenue " + confirmed.getConfirmedRevenue().toPlainString()
                        + " " + currency, Map.of("sale_id", confirmed.getId().toString()), actor.email(), clock.instant(), null);
                crm.transition(lead, PipelineStatus.WON, "Sale confirmed", null, actor.email(), false);
                Map<String, Object> custom = new LinkedHashMap<>();
                custom.put("value", confirmed.getConfirmedRevenue());
                custom.put("currency", currency);
                custom.put("content_name", "property_booking");
                conversions.recordForLead(lead, EventName.Purchase, "sale-" + confirmed.getId() + "-purchase",
                        Source.manual_crm, custom);
            });
        }
        return detail(sale);
    }

    @Transactional
    public SaleDetail cancel(UUID saleId, CancelSale request) {
        Sale sale = requireSale(saleId);
        Actor actor = access.require(sale.getClientId(), Permission.MANAGE);
        SaleStatus status = SaleStatus.parse(sale.getStatus());
        if (!status.canMoveTo(SaleStatus.CANCELLED) || status == SaleStatus.CANCELLED) {
            throw new ConflictException("A " + status + " sale cannot be cancelled");
        }
        Map<String, Object> before = snapshot(sale);
        sale.setStatus(SaleStatus.CANCELLED.name());
        sale.setCancellationReason(request.reason().trim());
        recalculate(sale);
        sale = sales.save(sale);
        writeHistory(sale, "cancelled", status.name(), SaleStatus.CANCELLED.name(), diff(before, snapshot(sale)),
                request.reason(), actor.email());
        if (sale.getLeadId() != null) {
            Sale cancelled = sale;
            leads.findById(sale.getLeadId()).ifPresent(lead -> crm.addActivity(lead, "sale",
                    "Sale cancelled: " + request.reason().trim(), Map.of("sale_id", cancelled.getId().toString()),
                    actor.email(), clock.instant(), null));
        }
        return detail(sale);
    }

    @Transactional(readOnly = true)
    public SaleDetail get(UUID saleId) {
        Sale sale = requireSale(saleId);
        access.read(sale.getClientId());
        return detail(sale);
    }

    @Transactional(readOnly = true)
    public SaleListPage list(UUID clientId, SaleFilter filter, int limit, long offset) {
        access.read(clientId);
        Specification<Sale> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("clientId"), clientId));
            p.add(cb.isNull(root.get("deletedAt")));
            if (hasText(filter.status())) p.add(cb.equal(root.get("status"), filter.status()));
            if (hasText(filter.campaignId())) p.add(cb.equal(root.get("campaignId"), filter.campaignId()));
            if (filter.agentId() != null) p.add(cb.equal(root.get("agentId"), filter.agentId()));
            if (filter.projectId() != null) p.add(cb.equal(root.get("projectId"), filter.projectId()));
            if (filter.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("bookingDate"), filter.from()));
            if (filter.to() != null) p.add(cb.lessThanOrEqualTo(root.get("bookingDate"), filter.to()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        List<Sale> all = sales.findAll(spec);
        Page<Sale> page = sales.findAll(spec, new OffsetPageRequest(offset, limit, Sort.by(Sort.Direction.DESC, "soldAt")));
        Map<UUID, Lead> byLead = leads.findAllById(page.getContent().stream().map(Sale::getLeadId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Lead::getId, Function.identity()));
        Map<UUID, String> agentNames = new HashMap<>();
        agents.findByClientIdOrderByNameAsc(clientId).forEach(a -> agentNames.put(a.getId(), a.getName()));
        Map<UUID, String> projectNames = new HashMap<>();
        projects.findByClientIdOrderByNameAsc(clientId).forEach(p -> projectNames.put(p.getId(), p.getName()));
        List<SaleDetail> rows = page.getContent().stream()
                .map(s -> toDto(s, byLead.get(s.getLeadId()), agentNames, projectNames, null)).toList();
        return new SaleListPage(rows, page.getTotalElements(), totals(all));
    }

    // ---------------------------------------------------------------- rules

    /** Derives outstanding and confirmed revenue from the amounts and status. */
    static void recalculate(Sale sale) {
        SaleStatus status = SaleStatus.parse(sale.getStatus());
        BigDecimal collected = nz(sale.getAmountCollected());
        BigDecimal refunded = nz(sale.getRefundedAmount());
        sale.setOutstandingAmount(status.isClosed() ? BigDecimal.ZERO : nz(sale.getBookingValue()).subtract(collected).max(BigDecimal.ZERO));
        boolean countsAsRevenue = sale.getSaleConfirmedAt() != null && !status.isClosed();
        sale.setConfirmedRevenue(countsAsRevenue ? collected.subtract(refunded).max(BigDecimal.ZERO) : BigDecimal.ZERO);
    }

    static void validateAmounts(Sale sale) {
        SaleStatus status = SaleStatus.parse(sale.getStatus());
        BigDecimal collected = nz(sale.getAmountCollected());
        BigDecimal booking = nz(sale.getBookingValue());
        if (nz(sale.getRefundedAmount()).compareTo(collected) > 0) {
            throw new IllegalArgumentException("Refunded amount cannot exceed the amount collected");
        }
        if (collected.compareTo(booking) > 0) {
            throw new IllegalArgumentException("Amount collected cannot exceed the booking value");
        }
        switch (status) {
            case FULLY_PAID -> {
                if (collected.compareTo(booking) < 0) {
                    throw new IllegalArgumentException("FULLY_PAID needs the full booking value collected");
                }
            }
            case PARTIALLY_PAID -> {
                if (collected.signum() <= 0 || collected.compareTo(booking) >= 0) {
                    throw new IllegalArgumentException("PARTIALLY_PAID needs a collected amount below the booking value");
                }
            }
            case REFUNDED -> {
                if (nz(sale.getRefundedAmount()).signum() <= 0) {
                    throw new IllegalArgumentException("REFUNDED needs a refunded amount");
                }
            }
            default -> {
            }
        }
    }

    private LeadAttributionTouch firstTouch(UUID leadId) {
        return attributions.findByLeadId(leadId).map(LeadAttribution::getFirstTouchId)
                .flatMap(touches::findById).orElse(null);
    }

    private Sale requireSale(UUID saleId) {
        return sales.findByIdAndDeletedAtIsNull(saleId).orElseThrow(() -> new NotFoundException("Sale not found"));
    }

    private void writeHistory(Sale sale, String action, String from, String to, Map<String, Object> changes,
                              String reason, String actor) {
        SaleStatusHistory row = new SaleStatusHistory();
        row.setSaleId(sale.getId());
        row.setClientId(sale.getClientId());
        row.setAction(action);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setChanges(changes);
        row.setReason(hasText(reason) ? reason.trim() : null);
        row.setActorEmail(actor);
        row.setChangedAt(clock.instant());
        history.save(row);
    }

    static Map<String, Object> snapshot(Sale s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.getStatus());
        m.put("booking_value", plain(s.getBookingValue()));
        m.put("amount_collected", plain(s.getAmountCollected()));
        m.put("refunded_amount", plain(s.getRefundedAmount()));
        m.put("outstanding_amount", plain(s.getOutstandingAmount()));
        m.put("confirmed_revenue", plain(s.getConfirmedRevenue()));
        m.put("campaign_id", s.getCampaignId());
        m.put("adset_id", s.getAdsetId());
        m.put("ad_id", s.getAdId());
        m.put("project_id", s.getProjectId() == null ? null : s.getProjectId().toString());
        m.put("agent_id", s.getAgentId() == null ? null : s.getAgentId().toString());
        m.put("channel", s.getChannel());
        m.put("booking_date", s.getBookingDate() == null ? null : s.getBookingDate().toString());
        return m;
    }

    static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        after.forEach((key, value) -> {
            if (!Objects.equals(before.get(key), value)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", before.get(key));
                change.put("to", value);
                changes.put(key, change);
            }
        });
        return changes;
    }

    private SaleDetail detail(Sale sale) {
        Lead lead = sale.getLeadId() == null ? null : leads.findById(sale.getLeadId()).orElse(null);
        Map<UUID, String> agentNames = new HashMap<>();
        if (sale.getAgentId() != null) agents.findById(sale.getAgentId()).ifPresent(a -> agentNames.put(a.getId(), a.getName()));
        Map<UUID, String> projectNames = new HashMap<>();
        if (sale.getProjectId() != null) projects.findById(sale.getProjectId()).ifPresent(p -> projectNames.put(p.getId(), p.getName()));
        List<SaleHistoryDto> rows = history.findBySaleIdOrderByChangedAtAsc(sale.getId()).stream()
                .map(h -> new SaleHistoryDto(h.getAction(), h.getFromStatus(), h.getToStatus(), h.getChanges(),
                        h.getReason(), h.getActorEmail(), h.getChangedAt())).toList();
        return toDto(sale, lead, agentNames, projectNames, rows);
    }

    static SaleDetail toDto(Sale s, Lead lead, Map<UUID, String> agentNames, Map<UUID, String> projectNames,
                            List<SaleHistoryDto> rows) {
        return new SaleDetail(s.getId().toString(), s.getClientId().toString(),
                s.getLeadId() == null ? null : s.getLeadId().toString(),
                lead == null ? null : (hasText(lead.getFirstName()) ? lead.getFirstName() : lead.getPhoneNumber()),
                s.getCampaignId(), s.getAdsetId(), s.getAdId(),
                s.getProjectId() == null ? null : s.getProjectId().toString(), projectNames.get(s.getProjectId()),
                s.getAgentId() == null ? null : s.getAgentId().toString(), agentNames.get(s.getAgentId()),
                s.getChannel(), s.getStatus(), s.getBookingValue(), s.getAmountCollected(), s.getOutstandingAmount(),
                s.getRefundedAmount(), s.getConfirmedRevenue(), s.getBookingDate(), s.getSaleConfirmedAt(),
                s.getConfirmedBy(), s.getCancellationReason(), s.getNote(), s.isLegacyRecord(), s.getSoldAt(),
                s.getCreatedAt(), s.getUpdatedAt(), rows);
    }

    static Totals totals(List<Sale> all) {
        long bookings = all.stream().filter(s -> SaleStatus.parse(s.getStatus()).isBooked()).count();
        return new Totals(all.size(), bookings,
                sum(all, Sale::getBookingValue, true), sum(all, Sale::getAmountCollected, false),
                sum(all, Sale::getOutstandingAmount, false), sum(all, Sale::getRefundedAmount, false),
                sum(all, Sale::getConfirmedRevenue, false));
    }

    private static BigDecimal sum(List<Sale> all, Function<Sale, BigDecimal> field, boolean bookedOnly) {
        return all.stream().filter(s -> !bookedOnly || SaleStatus.parse(s.getStatus()).isBooked())
                .map(field).map(SaleLifecycleService::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return hasText(s) ? s.trim() : null;
    }
}
