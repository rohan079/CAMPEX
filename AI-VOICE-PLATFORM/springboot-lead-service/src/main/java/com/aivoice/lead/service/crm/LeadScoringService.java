package com.aivoice.lead.service.crm;

import com.aivoice.lead.dto.CrmDtos.ScoreFactor;
import com.aivoice.lead.dto.CrmDtos.ScoringRule;
import com.aivoice.lead.entity.ClientProject;
import com.aivoice.lead.entity.Lead;
import com.aivoice.lead.entity.LeadQualification;
import com.aivoice.lead.service.Exceptions.InvalidPhoneException;
import com.aivoice.lead.service.PhoneNumbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explainable, rule-based lead score (0–100). Each factor has a configurable weight and reports why it did or did
 * not score; no model output is involved. score = round(100 × earned points ÷ total weight).
 */
public final class LeadScoringService {

    public static final List<ScoringRule> DEFAULT_RULES = List.of(
            new ScoringRule("budget_matches_project", 25),
            new ScoringRule("location_matches_project", 20),
            new ScoringRule("timeline_immediate", 20),
            new ScoringRule("genuine_interest", 15),
            new ScoringRule("contact_valid", 10),
            new ScoringRule("financing_ready", 10));

    static final Map<String, String> LABELS = Map.of(
            "budget_matches_project", "Budget matches project pricing",
            "location_matches_project", "Preferred location matches project",
            "timeline_immediate", "Purchase timeline is immediate",
            "genuine_interest", "Genuine purchase interest confirmed",
            "contact_valid", "Contact details are valid",
            "financing_ready", "Financing is arranged");

    public record Result(int score, List<ScoreFactor> factors) {
    }

    private LeadScoringService() {
    }

    public static Result score(Lead lead, LeadQualification q, ClientProject project, List<ScoringRule> rules) {
        List<ScoreFactor> factors = new ArrayList<>();
        int totalWeight = 0;
        int earned = 0;
        for (ScoringRule rule : rules) {
            if (rule.weight() <= 0) {
                continue;
            }
            ScoreFactor factor = evaluate(rule, lead, q, project);
            factors.add(factor);
            totalWeight += rule.weight();
            earned += factor.points();
        }
        int score = totalWeight == 0 ? 0 : Math.round(100f * earned / totalWeight);
        return new Result(Math.min(100, Math.max(0, score)), factors);
    }

    static ScoreFactor evaluate(ScoringRule rule, Lead lead, LeadQualification q, ClientProject project) {
        int w = rule.weight();
        String label = LABELS.getOrDefault(rule.factor(), rule.factor());
        return switch (rule.factor()) {
            case "budget_matches_project" -> {
                if (project == null || (project.getMinPrice() == null && project.getMaxPrice() == null)) {
                    yield new ScoreFactor(rule.factor(), label, w, 0, false, "No project pricing to compare against");
                }
                if (q.getBudgetMin() == null && q.getBudgetMax() == null) {
                    yield new ScoreFactor(rule.factor(), label, w, 0, false, "Budget not captured");
                }
                BigDecimal budgetLow = q.getBudgetMin() != null ? q.getBudgetMin() : q.getBudgetMax();
                BigDecimal budgetHigh = q.getBudgetMax() != null ? q.getBudgetMax() : q.getBudgetMin();
                BigDecimal priceLow = project.getMinPrice() != null ? project.getMinPrice() : project.getMaxPrice();
                BigDecimal priceHigh = project.getMaxPrice() != null ? project.getMaxPrice() : project.getMinPrice();
                boolean overlap = budgetHigh.compareTo(priceLow) >= 0 && budgetLow.compareTo(priceHigh) <= 0;
                yield new ScoreFactor(rule.factor(), label, w, overlap ? w : 0, overlap, overlap
                        ? "Budget " + budgetLow.toPlainString() + "–" + budgetHigh.toPlainString() + " overlaps project price range"
                        : "Budget is outside the project price range");
            }
            case "location_matches_project" -> {
                String preferred = norm(q.getPreferredLocation());
                String location = project == null ? null : norm(project.getLocation());
                if (preferred == null || location == null) {
                    yield new ScoreFactor(rule.factor(), label, w, 0, false,
                            preferred == null ? "Preferred location not captured" : "Project has no location");
                }
                boolean match = location.contains(preferred) || preferred.contains(location);
                yield new ScoreFactor(rule.factor(), label, w, match ? w : 0, match,
                        match ? "Prefers " + q.getPreferredLocation().trim() : "Prefers a different location");
            }
            case "timeline_immediate" -> {
                String timeline = q.getPurchaseTimeline();
                int points = "IMMEDIATE".equals(timeline) ? w : "WITHIN_3_MONTHS".equals(timeline) ? w / 2 : 0;
                yield new ScoreFactor(rule.factor(), label, w, points, points == w,
                        timeline == null ? "Timeline not captured" : "Timeline: " + timeline.toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            case "genuine_interest" -> {
                boolean yes = Boolean.TRUE.equals(q.getGenuineInterest());
                yield new ScoreFactor(rule.factor(), label, w, yes ? w : 0, yes,
                        q.getGenuineInterest() == null ? "Not assessed" : yes ? "Confirmed by the sales team" : "Not confirmed");
            }
            case "contact_valid" -> {
                boolean phone = validPhone(lead.getPhoneNumber());
                boolean email = lead.getEmail() != null && lead.getEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
                boolean ok = phone || email;
                yield new ScoreFactor(rule.factor(), label, w, ok ? w : 0, ok,
                        ok ? (phone ? "Valid phone number" : "Valid email") : "No valid phone or email");
            }
            case "financing_ready" -> {
                String f = q.getFinancingPreference();
                int points = "SELF_FUNDED".equals(f) || "LOAN_PREAPPROVED".equals(f) ? w : "HOME_LOAN".equals(f) ? w / 2 : 0;
                yield new ScoreFactor(rule.factor(), label, w, points, points == w,
                        f == null ? "Financing not captured" : "Financing: " + f.toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            default -> new ScoreFactor(rule.factor(), label, w, 0, false, "Unknown factor");
        };
    }

    public static List<Map<String, Object>> toJson(List<ScoreFactor> factors) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScoreFactor f : factors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("factor", f.factor());
            row.put("label", f.label());
            row.put("weight", f.weight());
            row.put("points", f.points());
            row.put("matched", f.matched());
            row.put("reason", f.reason());
            rows.add(row);
        }
        return rows;
    }

    public static List<ScoreFactor> fromJson(List<Map<String, Object>> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(r -> new ScoreFactor(String.valueOf(r.get("factor")), String.valueOf(r.get("label")),
                ((Number) r.getOrDefault("weight", 0)).intValue(), ((Number) r.getOrDefault("points", 0)).intValue(),
                Boolean.TRUE.equals(r.get("matched")), String.valueOf(r.get("reason")))).toList();
    }

    private static boolean validPhone(String phone) {
        try {
            return phone != null && PhoneNumbers.normalize(phone).length() <= 16;
        } catch (InvalidPhoneException e) {
            return false;
        }
    }

    private static String norm(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
