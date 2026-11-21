package com.aivoice.lead.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.aivoice.lead.dto.ExperimentExecutionDtos.Check;
import com.aivoice.lead.dto.ExperimentExecutionDtos.VariantState;
import com.aivoice.lead.service.execution.ExperimentExecutionService.Exp;
import com.aivoice.lead.service.execution.ExperimentExecutionService.Variant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExperimentReadinessTest {

    static Exp exp(String variable, LocalDate end) {
        return new Exp(UUID.randomUUID(), UUID.randomUUID(), "draft", null, variable, "900", LocalDate.of(2026, 11, 1), end, 1000, null);
    }

    static Variant ad(String role, String id) {
        return new Variant(UUID.randomUUID(), role, role, id, null, null, null);
    }

    static Variant adset(String role, String id) {
        return new Variant(UUID.randomUUID(), role, role, null, id, null, null);
    }

    static VariantState state(String campaign) {
        return new VariantState("v", "control", "c", "ad", "1", null, "ACTIVE", "ACTIVE", campaign, null, null, null);
    }

    static Set<String> codes(List<Check> checks, String severity) {
        return Set.copyOf(checks.stream().filter(c -> c.severity().equals(severity)).map(Check::code).toList());
    }

    @Test
    void aWellDesignedCreativeTestHasNoErrors() {
        List<Check> checks = ExperimentExecutionService.designChecks(exp("headline", LocalDate.of(2026, 11, 14)),
                List.of(ad("control", "1"), ad("test", "2")), List.of(state("900"), state("900")));
        assertThat(codes(checks, "error")).isEmpty();
        assertThat(codes(checks, "warning")).isEmpty();
    }

    @Test
    void designMistakesAreCaught() {
        assertThat(codes(ExperimentExecutionService.designChecks(exp("image", LocalDate.of(2026, 11, 14)),
                List.of(adset("control", "1"), adset("test", "2")), List.of()), "error")).contains("level");
        assertThat(codes(ExperimentExecutionService.designChecks(exp("headline", LocalDate.of(2026, 11, 14)),
                List.of(ad("control", "1"), ad("test", "1")), List.of()), "error")).contains("same_object");
        List<Check> shortTest = ExperimentExecutionService.designChecks(exp("headline", LocalDate.of(2026, 11, 3)),
                List.of(ad("control", "1"), ad("test", "2")), List.of(state("900"), state("901")));
        assertThat(codes(shortTest, "warning")).contains("duration", "campaigns");
        assertThat(codes(ExperimentExecutionService.designChecks(exp("headline", null), List.of(ad("control", "1"), ad("test", "2")), List.of()),
                "warning")).contains("duration");
    }

    @Test
    void variantChecksCoverAccountDeliveryAndCampaign() {
        Exp e = exp("headline", LocalDate.of(2026, 11, 14));
        Set<String> mapped = Set.of("act_555");
        assertThat(codes(ExperimentExecutionService.variantChecks(e, ad("test", "2"),
                Map.of("account_id", "555", "status", "ACTIVE", "effective_status", "DISAPPROVED", "campaign_id", "901"), mapped), "error"))
                .containsExactlyInAnyOrder("delivery", "campaign");
        assertThat(codes(ExperimentExecutionService.variantChecks(e, ad("test", "2"),
                Map.of("account_id", "555", "status", "PAUSED", "campaign_id", "900"), mapped), "info")).contains("paused");
    }
}
