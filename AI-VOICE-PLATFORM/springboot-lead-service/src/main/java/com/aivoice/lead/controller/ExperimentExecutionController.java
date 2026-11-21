package com.aivoice.lead.controller;

import com.aivoice.lead.dto.ExperimentExecutionDtos.CreativeLink;
import com.aivoice.lead.dto.ExperimentExecutionDtos.Execution;
import com.aivoice.lead.service.execution.ExperimentExecutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Phase 3 experiment execution on top of the Phase 2 experiment endpoints. */
@RestController
public class ExperimentExecutionController {

    private final ExperimentExecutionService execution;

    public ExperimentExecutionController(ExperimentExecutionService execution) {
        this.execution = execution;
    }

    @GetMapping("/api/experiments/{id}/execution")
    public Execution get(@PathVariable String id) {
        return execution.get(Ids.uuid(id, "Experiment"));
    }

    @PutMapping("/api/experiments/{id}/variants/{variantId}/creative")
    public Execution linkCreative(@PathVariable String id, @PathVariable String variantId, @RequestBody CreativeLink body) {
        return execution.linkCreative(Ids.uuid(id, "Experiment"), Ids.uuid(variantId, "Variant"), body == null ? null : body.creativeAssetId());
    }

    @PostMapping("/api/experiments/{id}/launch")
    public Execution launch(@PathVariable String id) {
        return execution.launch(Ids.uuid(id, "Experiment"));
    }

    @PostMapping("/api/experiments/{id}/follow-up")
    public Execution followUp(@PathVariable String id) {
        return execution.followUp(Ids.uuid(id, "Experiment"));
    }
}
