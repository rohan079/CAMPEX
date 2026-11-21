package com.aivoice.lead.service.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic checks for one proposed change to a live Meta object. Inputs are the state Meta reported just now
 * and the client's budget policy; nothing here calls Meta or an AI model.
 */
public final class ActionGuardrails {

    public static final Set<String> ACTIONS = Set.of("pause", "resume", "set_daily_budget");
    public static final Set<String> LEVELS = Set.of("campaign", "adset", "ad");
    static final int DEFAULT_MAX_CHANGE_PCT = 20;

    public record Check(String code, String severity, String message) {
    }

    /** {@code proposed}: the fields that will be sent to Meta (status and/or daily_budget in minor units). */
    public record Result(boolean allowed, Map<String, Object> proposed, List<Check> checks) {
    }

    public record Policy(Integer maxChangePct, BigDecimal dailySpendLimit) {
    }

    private ActionGuardrails() {
    }

    public static Result evaluate(String action, String level, Map<String, Object> before, Long dailyBudgetMinor,
                                  Set<String> mappedAccounts, Policy policy) {
        List<Check> checks = new ArrayList<>();
        Map<String, Object> proposed = new LinkedHashMap<>();
        String account = before.get("account_id") == null ? null : "act_" + String.valueOf(before.get("account_id")).replaceFirst("^act_", "");
        if (account == null || !mappedAccounts.contains(account)) {
            checks.add(new Check("account", "error", "This object is not in an ad account mapped to the client."));
        }
        String status = text(before.get("status"));
        switch (action) {
            case "pause" -> {
                if (!"ACTIVE".equals(status)) {
                    checks.add(new Check("no_op", "error", "It is not active (status " + status + "), so there is nothing to pause."));
                }
                proposed.put("status", "PAUSED");
            }
            case "resume" -> {
                if (!"PAUSED".equals(status)) {
                    checks.add(new Check("no_op", "error", "It is not paused (status " + status + "), so it cannot be resumed."));
                }
                proposed.put("status", "ACTIVE");
                checks.add(new Check("spend", "warning", "Resuming starts delivery and spending immediately."));
                String effective = text(before.get("effective_status"));
                if (effective != null && effective.contains("CAMPAIGN_PAUSED") || "ADSET_PAUSED".equals(effective)) {
                    checks.add(new Check("parent_paused", "warning", "A parent campaign or ad set is paused, so it will not deliver yet."));
                }
            }
            case "set_daily_budget" -> budget(level, before, dailyBudgetMinor, policy, checks, proposed);
            default -> checks.add(new Check("action", "error", "Unsupported action."));
        }
        boolean allowed = checks.stream().noneMatch(c -> "error".equals(c.severity()));
        return new Result(allowed, proposed, List.copyOf(checks));
    }

    private static void budget(String level, Map<String, Object> before, Long to, Policy policy, List<Check> checks,
                               Map<String, Object> proposed) {
        if ("ad".equals(level)) {
            checks.add(new Check("level", "error", "Ads have no budget; change the ad set or campaign."));
            return;
        }
        Long from = number(before.get("daily_budget"));
        Long lifetime = number(before.get("lifetime_budget"));
        if (from == null || from <= 0) {
            checks.add(new Check("no_daily_budget", "error", lifetime != null && lifetime > 0
                    ? "This " + level + " uses a lifetime budget; Campex only changes daily budgets."
                    : "This " + level + " has no daily budget at this level (the budget is set on the "
                    + ("campaign".equals(level) ? "ad sets" : "campaign") + ")."));
            return;
        }
        if (to == null || to < 100) {
            checks.add(new Check("budget", "error", "Choose a new daily budget of at least 100 minor units."));
            return;
        }
        if (Objects.equals(from, to)) {
            checks.add(new Check("no_op", "error", "The new budget equals the current budget."));
            return;
        }
        int maxPct = policy.maxChangePct() == null ? DEFAULT_MAX_CHANGE_PCT : policy.maxChangePct();
        BigDecimal pct = BigDecimal.valueOf(Math.abs(to - from) * 100).divide(BigDecimal.valueOf(from), 1, RoundingMode.HALF_UP);
        if (pct.compareTo(BigDecimal.valueOf(maxPct)) > 0) {
            checks.add(new Check("max_change", "error", "A " + pct.stripTrailingZeros().toPlainString() + "% change exceeds the "
                    + maxPct + "% limit in the client's budget policy. Make smaller steps."));
        }
        if (policy.dailySpendLimit() != null && BigDecimal.valueOf(to).movePointLeft(2).compareTo(policy.dailySpendLimit()) > 0) {
            checks.add(new Check("daily_limit", "error", "The new budget exceeds the client's daily spend limit of "
                    + policy.dailySpendLimit().stripTrailingZeros().toPlainString() + "."));
        }
        if (to > from) {
            checks.add(new Check("spend", "warning", "Spending can rise by up to " + (to - from) / 100 + " per day."));
        }
        proposed.put("daily_budget", to);
    }

    /** True when every proposed field is reflected in the state Meta reports. */
    public static boolean matches(Map<String, Object> proposed, Map<String, Object> state) {
        for (Map.Entry<String, Object> e : proposed.entrySet()) {
            Object actual = state.get(e.getKey());
            if ("daily_budget".equals(e.getKey())) {
                if (!Objects.equals(number(e.getValue()), number(actual))) return false;
            } else if (!Objects.equals(String.valueOf(e.getValue()), text(actual))) {
                return false;
            }
        }
        return true;
    }

    /** True when the fields this action touches still have the values seen at preview time. */
    public static boolean unchanged(Map<String, Object> proposed, Map<String, Object> before, Map<String, Object> now) {
        for (String key : proposed.keySet()) {
            Object a = before.get(key);
            Object b = now.get(key);
            boolean same = "daily_budget".equals(key) ? Objects.equals(number(a), number(b)) : Objects.equals(text(a), text(b));
            if (!same) return false;
        }
        return true;
    }

    static Long number(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
