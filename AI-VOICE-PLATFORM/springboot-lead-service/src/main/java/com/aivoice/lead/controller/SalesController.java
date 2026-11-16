package com.aivoice.lead.controller;

import com.aivoice.lead.dto.ClientDtos.RecordSaleRequest;
import com.aivoice.lead.dto.ClientDtos.SaleDto;
import com.aivoice.lead.dto.ClientDtos.SalePage;
import com.aivoice.lead.service.SalesService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sales are recorded by the team, either on a lead or directly against a client. */
@RestController
@RequestMapping("/api/sales")
public class SalesController {

    private final SalesService sales;

    public SalesController(SalesService sales) {
        this.sales = sales;
    }

    @GetMapping
    public SalePage list(
            @RequestParam(required = false, name = "client_id") String clientId,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") long offset) {
        return sales.list(LeadController.optionalId(clientId), channel, LeadController.clamp(limit), Math.max(offset, 0));
    }

    @PostMapping
    public SaleDto record(@Valid @RequestBody RecordSaleRequest body) {
        return sales.record(body);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        sales.delete(LeadController.parse(id));
        return Map.of("status", "deleted");
    }
}
