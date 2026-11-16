package com.aivoice.lead.service;

import com.aivoice.lead.dto.ClientDtos.RecordSaleRequest;
import com.aivoice.lead.dto.ClientDtos.SaleDto;
import com.aivoice.lead.dto.ClientDtos.SalePage;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.Sale;
import com.aivoice.lead.repository.LeadRepository;
import com.aivoice.lead.repository.OffsetPageRequest;
import com.aivoice.lead.repository.SaleRepository;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sales are entered by the team: the platform cannot see a purchase on its own. */
@Service
public class SalesService {

    private final SaleRepository sales;
    private final LeadRepository leads;
    private final ClientService clients;
    private final Clock clock;

    public SalesService(SaleRepository sales, LeadRepository leads, ClientService clients, Clock clock) {
        this.sales = sales;
        this.leads = leads;
        this.clients = clients;
        this.clock = clock;
    }

    @Transactional
    public SaleDto record(RecordSaleRequest request) {
        Sale sale = new Sale();
        Lead lead = null;
        if (hasText(request.leadId())) {
            lead = leads.findById(parse(request.leadId(), "Lead not found"))
                    .orElseThrow(() -> new NotFoundException("Lead not found"));
            // A sale on a lead always belongs to that lead's client and ad.
            sale.setClientId(lead.getClientId());
            sale.setLeadId(lead.getId());
            sale.setAdId(hasText(request.adId()) ? request.adId().trim() : lead.getAdId());
        } else {
            if (!hasText(request.clientId())) {
                throw new IllegalArgumentException("Choose a client or a lead for this sale");
            }
            sale.setClientId(clients.require(parse(request.clientId(), "Client not found")).getId());
            sale.setAdId(hasText(request.adId()) ? request.adId().trim() : null);
        }
        Instant now = clock.instant();
        Instant soldAt = request.soldAt() == null ? now : request.soldAt();
        if (soldAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new IllegalArgumentException("A sale cannot be dated in the future");
        }
        sale.setChannel(request.channel());
        sale.setAmount(request.amount());
        sale.setNote(hasText(request.note()) ? request.note().trim() : null);
        sale.setSoldAt(soldAt);
        return toDto(sales.save(sale), lead);
    }

    @Transactional(readOnly = true)
    public SalePage list(UUID clientId, String channel, int limit, long offset) {
        Specification<Sale> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (clientId != null) {
                predicates.add(cb.equal(root.get("clientId"), clientId));
            }
            if (hasText(channel)) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Sale> page = sales.findAll(spec, new OffsetPageRequest(offset, limit, Sort.by(Sort.Direction.DESC, "soldAt")));
        Map<UUID, Lead> byId = leads.findAllById(page.getContent().stream()
                        .map(Sale::getLeadId).filter(id -> id != null).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Lead::getId, Function.identity()));
        List<SaleDto> rows = page.getContent().stream().map(s -> toDto(s, byId.get(s.getLeadId()))).toList();
        return new SalePage(rows, page.getTotalElements());
    }

    @Transactional
    public void delete(UUID id) {
        Sale sale = sales.findById(id).orElseThrow(() -> new NotFoundException("Sale not found"));
        sales.delete(sale);
    }

    private SaleDto toDto(Sale s, Lead lead) {
        return new SaleDto(
                s.getId().toString(),
                s.getClientId().toString(),
                s.getLeadId() == null ? null : s.getLeadId().toString(),
                lead == null ? null : (hasText(lead.getFirstName()) ? lead.getFirstName() : lead.getPhoneNumber()),
                s.getAdId(),
                s.getChannel(),
                s.getAmount(),
                s.getNote(),
                s.getSoldAt());
    }

    private static UUID parse(String id, String message) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(message);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
