package com.aivoice.lead.controller;

import com.aivoice.lead.dto.CampaignActionDtos.ActionDto;
import com.aivoice.lead.dto.CampaignActionDtos.ExecuteRequest;
import com.aivoice.lead.dto.CampaignActionDtos.PreviewRequest;
import com.aivoice.lead.dto.CampaignExecutionDtos.DecisionRequest;
import com.aivoice.lead.service.execution.CampaignActionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 3 optimization actions. Access and guardrails are enforced in the service. */
@RestController
public class CampaignActionController {

    private final CampaignActionService actions;

    public CampaignActionController(CampaignActionService actions) {
        this.actions = actions;
    }

    @PostMapping("/api/clients/{clientId}/campaign-actions/preview")
    public ActionDto preview(@PathVariable String clientId, @Valid @RequestBody PreviewRequest body) {
        return actions.preview(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/clients/{clientId}/campaign-actions")
    public List<ActionDto> list(@PathVariable String clientId, @RequestParam(required = false) String status) {
        return actions.list(Ids.uuid(clientId, "Client"), status);
    }

    @GetMapping("/api/campaign-actions/{id}")
    public ActionDto get(@PathVariable String id) {
        return actions.get(action(id));
    }

    @PostMapping("/api/campaign-actions/{id}/approve")
    public ActionDto approve(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return actions.approve(action(id), body == null ? null : body.note());
    }

    @PostMapping("/api/campaign-actions/{id}/reject")
    public ActionDto reject(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return actions.reject(action(id), body == null ? null : body.note());
    }

    @PostMapping("/api/campaign-actions/{id}/cancel")
    public ActionDto cancel(@PathVariable String id) {
        return actions.cancel(action(id));
    }

    @PostMapping("/api/campaign-actions/{id}/execute")
    public ActionDto execute(@PathVariable String id, @RequestBody(required = false) ExecuteRequest body) {
        return actions.execute(action(id), body != null && body.confirm());
    }

    private static java.util.UUID action(String id) {
        return Ids.uuid(id, "Change");
    }
}
