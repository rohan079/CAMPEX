package com.aivoice.lead.controller;

import com.aivoice.lead.dto.AutomationDtos.ExecutionDto;
import com.aivoice.lead.dto.AutomationDtos.RuleDto;
import com.aivoice.lead.dto.AutomationDtos.RuleInput;
import com.aivoice.lead.dto.AutomationDtos.Settings;
import com.aivoice.lead.service.automation.AutomationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 3 automation rules. Access is enforced in the service. */
@RestController
public class AutomationController {

    private final AutomationService automation;

    public AutomationController(AutomationService automation) {
        this.automation = automation;
    }

    @GetMapping("/api/automation/settings")
    public Settings settings() {
        return automation.settings();
    }

    @PostMapping("/api/clients/{clientId}/automation-rules")
    public RuleDto create(@PathVariable String clientId, @Valid @RequestBody RuleInput body) {
        return automation.create(Ids.uuid(clientId, "Client"), body);
    }

    @GetMapping("/api/clients/{clientId}/automation-rules")
    public List<RuleDto> list(@PathVariable String clientId) {
        return automation.list(Ids.uuid(clientId, "Client"));
    }

    @PutMapping("/api/automation-rules/{id}")
    public RuleDto update(@PathVariable String id, @Valid @RequestBody RuleInput body) {
        return automation.update(rule(id), body);
    }

    @PostMapping("/api/automation-rules/{id}/enable")
    public RuleDto enable(@PathVariable String id) {
        return automation.setEnabled(rule(id), true);
    }

    @PostMapping("/api/automation-rules/{id}/disable")
    public RuleDto disable(@PathVariable String id) {
        return automation.setEnabled(rule(id), false);
    }

    @PostMapping("/api/automation-rules/{id}/run")
    public List<ExecutionDto> run(@PathVariable String id) {
        return automation.runNow(rule(id));
    }

    @GetMapping("/api/clients/{clientId}/automation-executions")
    public List<ExecutionDto> executions(@PathVariable String clientId, @RequestParam(name = "rule_id", required = false) String ruleId) {
        return automation.executions(Ids.uuid(clientId, "Client"), ruleId);
    }

    private static java.util.UUID rule(String id) {
        return Ids.uuid(id, "Rule");
    }
}
