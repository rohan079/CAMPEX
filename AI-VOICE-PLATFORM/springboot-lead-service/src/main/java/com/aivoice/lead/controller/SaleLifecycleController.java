package com.aivoice.lead.controller;

import com.aivoice.lead.dto.SaleDtos.CancelSale;
import com.aivoice.lead.dto.SaleDtos.ConfirmSale;
import com.aivoice.lead.dto.SaleDtos.CreateSale;
import com.aivoice.lead.dto.SaleDtos.SaleDetail;
import com.aivoice.lead.dto.SaleDtos.SaleListPage;
import com.aivoice.lead.dto.SaleDtos.UpdateSale;
import com.aivoice.lead.service.crm.SaleLifecycleService;
import com.aivoice.lead.service.crm.SaleLifecycleService.SaleFilter;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lead-to-sale lifecycle. The legacy /api/sales list/record/delete endpoints remain in SalesController. */
@RestController
public class SaleLifecycleController {

    private final SaleLifecycleService sales;

    public SaleLifecycleController(SaleLifecycleService sales) {
        this.sales = sales;
    }

    @GetMapping("/api/clients/{clientId}/sales")
    public SaleListPage list(@PathVariable String clientId, @RequestParam(required = false) String status,
                             @RequestParam(required = false, name = "campaign_id") String campaignId,
                             @RequestParam(required = false, name = "agent_id") String agentId,
                             @RequestParam(required = false, name = "project_id") String projectId,
                             @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                             @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") long offset) {
        return sales.list(Ids.uuid(clientId, "Client"), new SaleFilter(status, campaignId,
                Ids.optionalUuid(agentId, "Agent"), Ids.optionalUuid(projectId, "Project"), date(from), date(to)),
                Ids.limit(limit), Math.max(offset, 0));
    }

    @PostMapping("/api/clients/{clientId}/sales")
    public SaleDetail create(@PathVariable String clientId, @Valid @RequestBody CreateSale body) {
        return sales.create(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/sales/{saleId}")
    public SaleDetail get(@PathVariable String saleId) {
        return sales.get(Ids.uuid(saleId, "Sale"));
    }

    @PutMapping("/api/sales/{saleId}")
    public SaleDetail update(@PathVariable String saleId, @Valid @RequestBody UpdateSale body) {
        return sales.update(Ids.uuid(saleId, "Sale"), body);
    }

    @PostMapping("/api/sales/{saleId}/confirm")
    public SaleDetail confirm(@PathVariable String saleId, @Valid @RequestBody(required = false) ConfirmSale body) {
        return sales.confirm(Ids.uuid(saleId, "Sale"), body);
    }

    @PostMapping("/api/sales/{saleId}/cancel")
    public SaleDetail cancel(@PathVariable String saleId, @Valid @RequestBody CancelSale body) {
        return sales.cancel(Ids.uuid(saleId, "Sale"), body);
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must be YYYY-MM-DD");
        }
    }
}
