package com.aivoice.lead.service.automation;

import com.aivoice.lead.config.AutomationProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Evaluates enabled rules that are due (hourly or daily). Each rule is isolated: one failure never stops the rest. */
@Component
public class AutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

    private final AutomationService automation;
    private final AutomationProperties props;

    public AutomationScheduler(AutomationService automation, AutomationProperties props) {
        this.automation = automation;
        this.props = props;
    }

    @Scheduled(cron = "${campex.automation.cron:0 */15 * * * *}")
    public void tick() {
        if (!props.enabled()) return;
        for (UUID rule : automation.dueRules()) {
            try {
                automation.evaluate(rule, "scheduler");
            } catch (RuntimeException e) {
                log.warn("Automation rule {} failed: {}", rule, e.getClass().getSimpleName());
            }
        }
    }
}
