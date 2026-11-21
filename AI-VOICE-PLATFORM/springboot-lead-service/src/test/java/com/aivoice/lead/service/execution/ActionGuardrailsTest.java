package com.aivoice.lead.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.aivoice.lead.service.execution.ActionGuardrails.Check;
import com.aivoice.lead.service.execution.ActionGuardrails.Policy;
import com.aivoice.lead.service.execution.ActionGuardrails.Result;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionGuardrailsTest {

    private static final Set<String> ACCOUNTS = Set.of("act_555");
    private static final Policy NONE = new Policy(null, null);

    private static Map<String, Object> adset(String status, Object daily) {
        return daily == null ? Map.of("id", "1", "account_id", "555", "status", status)
                : Map.of("id", "1", "account_id", "555", "status", status, "daily_budget", daily);
    }

    private static Set<String> errors(Result r) {
        return Set.copyOf(r.checks().stream().filter(c -> "error".equals(c.severity())).map(Check::code).toList());
    }

    @Test
    void pausesOnlyActiveObjectsInMappedAccounts() {
        assertThat(ActionGuardrails.evaluate("pause", "ad", adset("ACTIVE", null), null, ACCOUNTS, NONE).proposed()).isEqualTo(Map.of("status", "PAUSED"));
        assertThat(errors(ActionGuardrails.evaluate("pause", "ad", adset("PAUSED", null), null, ACCOUNTS, NONE))).contains("no_op");
        assertThat(errors(ActionGuardrails.evaluate("pause", "ad", adset("ACTIVE", null), null, Set.of("act_999"), NONE))).contains("account");
    }

    @Test
    void resumingWarnsAboutSpendAndPausedParents() {
        Map<String, Object> ad = Map.of("account_id", "555", "status", "PAUSED", "effective_status", "CAMPAIGN_PAUSED");
        Result r = ActionGuardrails.evaluate("resume", "ad", ad, null, ACCOUNTS, NONE);
        assertThat(r.allowed()).isTrue();
        assertThat(r.checks()).extracting(Check::code).contains("spend", "parent_paused");
    }

    @Test
    void budgetChangesStayWithinThePolicy() {
        assertThat(ActionGuardrails.evaluate("set_daily_budget", "adset", adset("ACTIVE", "35000"), 38500L, ACCOUNTS, NONE).allowed()).isTrue();
        assertThat(errors(ActionGuardrails.evaluate("set_daily_budget", "adset", adset("ACTIVE", "35000"), 50000L, ACCOUNTS, NONE))).contains("max_change");
        assertThat(errors(ActionGuardrails.evaluate("set_daily_budget", "adset", adset("ACTIVE", "35000"), 50000L, ACCOUNTS, new Policy(50, null)))).isEmpty();
        assertThat(errors(ActionGuardrails.evaluate("set_daily_budget", "adset", adset("ACTIVE", "35000"), 38500L, ACCOUNTS,
                new Policy(20, new BigDecimal("380"))))).contains("daily_limit");
        assertThat(errors(ActionGuardrails.evaluate("set_daily_budget", "adset", adset("ACTIVE", null), 38500L, ACCOUNTS, NONE))).contains("no_daily_budget");
        assertThat(errors(ActionGuardrails.evaluate("set_daily_budget", "ad", adset("ACTIVE", "35000"), 38500L, ACCOUNTS, NONE))).contains("level");
        assertThat(errors(ActionGuardrails.evaluate("set_daily_budget", "adset", adset("ACTIVE", "35000"), 35000L, ACCOUNTS, NONE))).contains("no_op");
    }

    @Test
    void verificationComparesOnlyTheChangedFields() {
        Map<String, Object> proposed = Map.of("daily_budget", 38500L);
        assertThat(ActionGuardrails.matches(proposed, Map.of("daily_budget", "38500", "status", "ACTIVE"))).isTrue();
        assertThat(ActionGuardrails.matches(proposed, Map.of("daily_budget", "35000"))).isFalse();
        assertThat(ActionGuardrails.unchanged(proposed, Map.of("daily_budget", "35000"), Map.of("daily_budget", "35000", "status", "PAUSED"))).isTrue();
        assertThat(ActionGuardrails.unchanged(proposed, Map.of("daily_budget", "35000"), Map.of("daily_budget", "36000"))).isFalse();
    }
}
