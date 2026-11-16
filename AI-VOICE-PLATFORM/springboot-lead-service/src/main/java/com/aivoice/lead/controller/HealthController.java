package com.aivoice.lead.controller;

import com.aivoice.lead.config.AppProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final AppProperties props;

    public HealthController(AppProperties props) {
        this.props = props;
    }

    /** The dashboard's connection indicator polls this. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "lead-service", "environment", props.environment());
    }
}
