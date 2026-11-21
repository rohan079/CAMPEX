package com.aivoice.lead.controller;

import com.aivoice.lead.dto.CampaignExecutionDtos.CampaignConfig;
import com.aivoice.lead.dto.CampaignExecutionDtos.DecisionRequest;
import com.aivoice.lead.dto.CampaignExecutionDtos.ExecutionDetail;
import com.aivoice.lead.dto.CampaignExecutionDtos.ExecutionSummary;
import com.aivoice.lead.dto.CampaignExecutionDtos.NotificationDto;
import com.aivoice.lead.dto.CampaignExecutionDtos.PublishRequest;
import com.aivoice.lead.dto.CampaignExecutionDtos.ValidationReport;
import com.aivoice.lead.service.execution.CampaignExecutionService;
import com.aivoice.lead.service.execution.NotificationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 3 campaign execution (see docs/PHASE3_CAMPAIGN_EXECUTION.md). Access is enforced in the service. */
@RestController
public class CampaignExecutionController {

    private final CampaignExecutionService executions;
    private final NotificationService notifications;

    public CampaignExecutionController(CampaignExecutionService executions, NotificationService notifications) {
        this.executions = executions;
        this.notifications = notifications;
    }

    @PostMapping("/api/clients/{clientId}/campaign-executions")
    public ExecutionDetail create(@PathVariable String clientId, @Valid @RequestBody CampaignConfig body,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return executions.create(Ids.uuid(clientId, "Client"), body, idempotencyKey);
    }

    @GetMapping("/api/clients/{clientId}/campaign-executions")
    public List<ExecutionSummary> list(@PathVariable String clientId, @RequestParam(required = false) String status) {
        return executions.list(Ids.uuid(clientId, "Client"), status);
    }

    @GetMapping("/api/campaign-executions/{id}")
    public ExecutionDetail get(@PathVariable String id) {
        return executions.get(execution(id));
    }

    @PutMapping("/api/campaign-executions/{id}")
    public ExecutionDetail update(@PathVariable String id, @Valid @RequestBody CampaignConfig body) {
        return executions.update(execution(id), body);
    }

    @PostMapping("/api/campaign-executions/{id}/validate")
    public ValidationReport validate(@PathVariable String id) {
        return executions.validate(execution(id));
    }

    @PostMapping("/api/campaign-executions/{id}/submit")
    public ExecutionDetail submit(@PathVariable String id) {
        return executions.submit(execution(id));
    }

    @PostMapping("/api/campaign-executions/{id}/approve")
    public ExecutionDetail approve(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return executions.approve(execution(id), body == null ? null : body.note());
    }

    @PostMapping("/api/campaign-executions/{id}/reject")
    public ExecutionDetail reject(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return executions.reject(execution(id), body == null ? null : body.note());
    }

    @PostMapping("/api/campaign-executions/{id}/publish")
    public ExecutionDetail publish(@PathVariable String id, @RequestBody(required = false) PublishRequest body) {
        return executions.publish(execution(id), body != null && body.confirm());
    }

    @PostMapping("/api/campaign-executions/{id}/retry")
    public ExecutionDetail retry(@PathVariable String id) {
        return executions.retry(execution(id));
    }

    @PostMapping("/api/campaign-executions/{id}/reconcile")
    public ExecutionDetail reconcile(@PathVariable String id) {
        return executions.reconcile(execution(id));
    }

    @PostMapping("/api/campaign-executions/{id}/cancel")
    public ExecutionDetail cancel(@PathVariable String id) {
        return executions.cancel(execution(id));
    }

    @GetMapping("/api/notifications")
    public Map<String, Object> notifications(@RequestParam(defaultValue = "false") boolean unread,
                                             @RequestParam(defaultValue = "50") int limit) {
        List<NotificationDto> items = notifications.mine(unread, limit);
        return Map.of("items", items, "unread_count", notifications.unreadCount());
    }

    @PostMapping("/api/notifications/{id}/read")
    public Map<String, Object> read(@PathVariable String id) {
        notifications.markRead(Ids.uuid(id, "Notification"));
        return Map.of("ok", true);
    }

    @PostMapping("/api/notifications/read-all")
    public Map<String, Object> readAll() {
        return Map.of("updated", notifications.markAllRead());
    }

    private static java.util.UUID execution(String id) {
        return Ids.uuid(id, "Campaign execution");
    }
}
